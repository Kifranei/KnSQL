package dev.knsql.jdbc

import dev.knsql.data.DatabaseClient
import dev.knsql.data.SqliteFiles
import dev.knsql.data.prepareDatabaseNetwork
import dev.knsql.i18n.tr
import dev.knsql.model.ConnectionDraft
import dev.knsql.model.Engine
import dev.knsql.model.MetaNode
import dev.knsql.model.NodeKind
import dev.knsql.model.OpenedSession
import dev.knsql.model.OracleDriver
import dev.knsql.model.QueryResult
import dev.knsql.model.BoundStatement
import dev.knsql.model.EditableData
import dev.knsql.model.TableRef
import dev.knsql.model.TableStructure
import dev.knsql.sql.singleSourceTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private class LiveConnection(
    val engine: Engine,
    @Volatile var connection: Connection,
    val readOnly: Boolean,
    val statement: AtomicReference<Statement?> = AtomicReference(null),
)

object JdbcDatabaseClient : DatabaseClient {
    private val sessions = ConcurrentHashMap<String, LiveConnection>()

    override suspend fun test(draft: ConnectionDraft): String = withContext(Dispatchers.IO) {
        val connection = connect(draft)
        try {
            val meta = connection.metaData
            val info = compactServerInfo(meta.databaseProductName, meta.databaseProductVersion)
            val driver = runCatching { "${meta.driverName} ${meta.driverVersion}" }.getOrNull()
            if (driver.isNullOrBlank()) info else "$info\n$driver"
        } finally {
            connection.close()
        }
    }

    override suspend fun open(draft: ConnectionDraft): OpenedSession = withContext(Dispatchers.IO) {
        val connection = connect(draft)
        if (draft.readOnly) runCatching { connection.isReadOnly = true }
        val meta = connection.metaData
        val info = compactServerInfo(meta.databaseProductName, meta.databaseProductVersion)
        val handle = java.util.UUID.randomUUID().toString()
        val live = LiveConnection(draft.engine, connection, draft.readOnly)
        sessions[handle] = live
        OpenedSession(handle, info, loadRoots(live))
    }

    override suspend fun children(handle: String, parent: MetaNode): List<MetaNode> = withContext(Dispatchers.IO) {
        val live = sessions[handle] ?: error(tr("连接已关闭"))
        when (parent.kind) {
            NodeKind.Session -> loadRoots(live)
            NodeKind.Catalog -> loadUnderCatalog(live, parent.catalog ?: parent.label)
            NodeKind.Schema -> loadRelations(live, parent.catalog, parent.schema ?: parent.label)
            NodeKind.Table, NodeKind.View -> loadColumns(live, parent)
            NodeKind.Column -> emptyList()
        }
    }

    override suspend fun execute(
        handle: String,
        sql: String,
        maxRows: Int,
        timeoutSec: Int,
    ): List<QueryResult> = withContext(Dispatchers.IO) {
        val live = sessions[handle] ?: error(tr("连接已关闭"))
        val statement = live.connection.createStatement()
        live.statement.set(statement)
        try {
            statement.queryTimeout = timeoutSec.coerceAtLeast(1)
            statement.maxRows = if (maxRows <= 0) 0 else maxRows + 1
            // sqlite-jdbc rejects a fetch size above maxRows.
            runCatching { statement.fetchSize = if (maxRows <= 0) 100 else minOf(100, maxRows + 1) }
            val started = System.nanoTime()
            val hasResult = statement.execute(sql)
            val results = mutableListOf<QueryResult>()
            var result = hasResult
            var update = statement.updateCount
            while (true) {
                if (result) {
                    statement.resultSet.use { set ->
                        if (set != null) results += readResult(set, maxRows, started, sql)
                    }
                } else if (update >= 0) {
                    results += QueryResult(
                        columns = emptyList(),
                        rows = emptyList(),
                        updateCount = update,
                        elapsedMs = (System.nanoTime() - started) / 1_000_000,
                        truncated = false,
                    )
                } else {
                    break
                }
                result = statement.moreResults
                update = statement.updateCount
                if (!result && update == -1) break
            }
            if (results.isEmpty()) {
                results += QueryResult(emptyList(), emptyList(), 0, (System.nanoTime() - started) / 1_000_000, false)
            }
            results
        } finally {
            live.statement.compareAndSet(statement, null)
            statement.close()
        }
    }

    override suspend fun ddl(handle: String, node: MetaNode, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val live = sessions[handle] ?: error(tr("连接已关闭"))
            val context = coroutineContext
            generateDdl(live.engine, live.connection, node) { message ->
                context.ensureActive()
                onProgress(message)
            }
        }

    override suspend fun ping(handle: String) = withContext(Dispatchers.IO) {
        val live = sessions[handle] ?: error(tr("连接已关闭"))
        live.connection.createStatement().use { statement ->
            statement.queryTimeout = 10
            statement.executeQuery(if (live.engine.oracleLike) "SELECT 1 FROM DUAL" else "SELECT 1").use { it.next() }
        }
        Unit
    }

    override suspend fun reconnect(handle: String, draft: ConnectionDraft) = withContext(Dispatchers.IO) {
        val live = sessions[handle] ?: error(tr("连接已关闭"))
        val fresh = connect(draft)
        if (live.readOnly) runCatching { fresh.isReadOnly = true }
        val stale = live.connection
        live.connection = fresh
        runCatching { stale.close() }
        Unit
    }

    // Both calls talk to the server, and Android forbids network access on the main thread.
    override suspend fun columnComments(handle: String, table: TableRef): Map<String, String> =
        withContext(Dispatchers.IO) {
            val live = sessions[handle] ?: error(tr("连接已关闭"))
            val connection = live.connection
            when (live.engine) {
                Engine.Oracle -> pairs(
                    connection,
                    "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS " +
                        "WHERE OWNER IN (?, UPPER(?)) AND TABLE_NAME IN (?, UPPER(?)) AND COMMENTS IS NOT NULL",
                    table.schema ?: currentOracleSchema(connection), table.schema ?: currentOracleSchema(connection),
                    table.name, table.name,
                )
                Engine.MySql, Engine.MariaDb -> pairs(
                    connection,
                    "SELECT COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ? AND COLUMN_COMMENT <> ''",
                    table.catalog ?: table.schema, table.name,
                )
                Engine.SqlServer -> sqlServerColumnComments(connection, table.catalog, table.schema, table.name)
                Engine.Dameng -> damengColumnComments(connection, table)
                Engine.PostgreSql -> pairs(
                    connection,
                    "SELECT a.attname, d.description FROM pg_catalog.pg_attribute a " +
                        "JOIN pg_catalog.pg_class c ON c.oid = a.attrelid " +
                        "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace " +
                        "JOIN pg_catalog.pg_description d ON d.objoid = c.oid AND d.objsubid = a.attnum " +
                        "WHERE n.nspname = COALESCE(?, current_schema()) AND c.relname = ? AND a.attnum > 0",
                    table.schema, table.name,
                )
                Engine.Sqlite -> emptyMap()
            }.mapKeys { it.key.uppercase() }
        }

    override suspend fun tableStructure(handle: String, table: TableRef): TableStructure = withContext(Dispatchers.IO) {
        val live = sessions[handle] ?: error(tr("连接已关闭"))
        loadStructure(live.engine, live.connection, table)
    }

    override suspend fun loadEditable(handle: String, structure: TableStructure, maxRows: Int): EditableData =
        withContext(Dispatchers.IO) {
            val live = sessions[handle] ?: error(tr("连接已关闭"))
            loadEditableRows(live.connection, structure, maxRows)
        }

    override suspend fun applyChanges(handle: String, statements: List<BoundStatement>): Int = withContext(Dispatchers.IO) {
        val live = sessions[handle] ?: error(tr("连接已关闭"))
        applyInTransaction(live.connection, statements)
    }

    override suspend fun alterTable(handle: String, structure: TableStructure, statements: List<String>) =
        withContext(Dispatchers.IO) {
            val live = sessions[handle] ?: error(tr("连接已关闭"))
            runDdl(live.engine, live.connection, structure, statements)
        }

    override fun cancel(handle: String) {
        val statement = sessions[handle]?.statement?.get() ?: return
        thread(isDaemon = true, name = "knsql-cancel") { runCatching { statement.cancel() } }
    }

    override fun close(handle: String) {
        val live = sessions.remove(handle) ?: return
        thread(isDaemon = true, name = "knsql-close") {
            runCatching { live.statement.get()?.cancel() }
            runCatching { live.connection.close() }
        }
    }

    private fun connect(draft: ConnectionDraft): Connection {
        val port = draft.port.toIntOrNull() ?: draft.engine.defaultPort
        try {
            return connectOnce(draft)
        } catch (first: java.sql.SQLException) {
            if (!first.isNetworkFailure()) throw first
            // A VPN such as Tailscale can take a moment to come back after the device wakes up, and
            // the network it runs on may have changed, so bind again and give it one more try.
            Thread.sleep(1500)
            try {
                return connectOnce(draft)
            } catch (second: java.sql.SQLException) {
                if (!second.isNetworkFailure()) throw second
                throw java.sql.SQLException(
                    tr("连不上 {0}:{1}。如果数据库在 Tailscale 或其他 VPN 后面，确认 VPN 已连接后重试。", draft.host.trim(), port) +
                        "\n" + second.message,
                    second,
                )
            }
        }
    }

    private fun connectOnce(draft: ConnectionDraft): Connection {
        prepareDatabaseNetwork()
        registerDrivers()
        val port = draft.port.toIntOrNull() ?: draft.engine.defaultPort
        val props = Properties()
        if (draft.username.isNotEmpty()) props["user"] = draft.username
        if (draft.password.isNotEmpty()) props["password"] = draft.password
        val url = when (draft.engine) {
            Engine.MySql -> mysqlUrl(draft.host.trim(), port, draft.database.trim(), draft.encrypt)
            Engine.MariaDb -> {
                ensureDriver("org.mariadb.jdbc.Driver", "MariaDB")
                mariaDbUrl(draft.host.trim(), port, draft.database.trim(), draft.encrypt)
            }
            Engine.PostgreSql -> {
                ensureDriver("org.postgresql.Driver", "PostgreSQL")
                postgresUrl(draft.host.trim(), port, draft.database.trim(), draft.encrypt)
            }
            Engine.Sqlite -> {
                ensureDriver("org.sqlite.JDBC", "SQLite")
                props.remove("user")
                props.remove("password")
                val file = SqliteFiles.resolve(draft.database)
                if (draft.readOnly) {
                    // xerial cannot switch to read-only after opening, so the file is opened read-only.
                    props["open_mode"] = "1"
                    if (!file.exists()) throw java.sql.SQLException(tr("找不到数据库文件 {0}", file.path))
                } else {
                    file.parentFile?.mkdirs()
                }
                props["foreign_keys"] = "true"
                props["busy_timeout"] = "5000"
                "jdbc:sqlite:" + file.path
            }
            Engine.SqlServer -> sqlServerUrl(
                draft.host.trim(), port, draft.instance.trim(), draft.database.trim(), draft.encrypt, draft.trustServerCertificate,
            )
            Engine.Dameng -> {
                ensureDriver("dm.jdbc.driver.DmDriver", "DM")
                props["connectTimeout"] = "15000"
                props["socketTimeout"] = "660000"
                props["keepAlive"] = "true"
                damengUrl(draft.host.trim(), port, draft.database.trim())
            }
            Engine.Oracle -> {
                if (draft.sysdba) props["internal_logon"] = "sysdba"
                props["oracle.net.CONNECT_TIMEOUT"] = "15000"
                props["oracle.jdbc.ReadTimeout"] = "660000"
                props["oracle.net.keepAlive"] = "true"
                return openOracle(draft, port, props)
            }
        }
        return DriverManager.getConnection(url, props)
    }

    /**
     * ojdbc 23 speaks to Oracle 19c and newer only; ojdbc 19 (relocated under dev.knsql.ojdbc19)
     * covers 11g R2 to 18c. Both answer jdbc:oracle: URLs, so they are called directly instead of
     * through DriverManager.
     */
    private fun openOracle(draft: ConnectionDraft, port: Int, props: Properties): Connection {
        val host = draft.host.trim()
        val database = draft.database.trim()
        return when (draft.oracleDriver) {
            OracleDriver.Modern -> openOracle(oracleDriver(legacy = false), host, port, database, draft.oracleSid, props)
            OracleDriver.Legacy -> openOracle(oracleDriver(legacy = true), host, port, database, draft.oracleSid, props)
            OracleDriver.Auto -> {
                val modern = try {
                    openOracle(oracleDriver(legacy = false), host, port, database, draft.oracleSid, props)
                } catch (first: java.sql.SQLException) {
                    // Wrong password, locked account, unknown service or an unreachable host won't get
                    // better with an older driver; anything else (protocol / version errors) might.
                    if (first.isNetworkFailure() || first.isOracleLoginError()) throw first
                    try {
                        return openOracle(oracleDriver(legacy = true), host, port, database, draft.oracleSid, props)
                    } catch (second: java.sql.SQLException) {
                        throw java.sql.SQLException(
                            first.message + "\n" + tr("改用 ojdbc 19 也失败：{0}", second.message),
                            second,
                        )
                    }
                }
                val major = runCatching { modern.metaData.databaseMajorVersion }.getOrDefault(99)
                if (major >= 19) return modern
                // ojdbc 23 logged in to a pre-19c server, which Oracle doesn't support: prefer ojdbc 19.
                val legacy = runCatching {
                    openOracle(oracleDriver(legacy = true), host, port, database, draft.oracleSid, props)
                }.getOrNull() ?: return modern
                runCatching { modern.close() }
                legacy
            }
        }
    }

    private fun openOracle(
        driver: Driver,
        host: String,
        port: Int,
        database: String,
        sid: Boolean,
        props: Properties,
    ): Connection {
        fun open(url: String): Connection =
            driver.connect(url, props) ?: throw java.sql.SQLException(tr("驱动不接受地址 {0}", url))
        val primary = oracleUrl(host, port, database, sid)
        try {
            return open(primary)
        } catch (first: java.sql.SQLException) {
            val text = first.message.orEmpty()
            val wrongName = text.contains("ORA-12514") || text.contains("ORA-12505")
            if (!wrongName) throw first
            val alternate = oracleUrl(host, port, database, !sid)
            try {
                return open(alternate)
            } catch (second: java.sql.SQLException) {
                throw java.sql.SQLException(
                    text + "\n" + tr("另一种方式 {0} 也失败：{1}", alternate, second.message),
                    second,
                )
            }
        }
    }

    private val oracleDrivers = ConcurrentHashMap<Boolean, Driver>()

    private fun oracleDriver(legacy: Boolean): Driver = oracleDrivers.getOrPut(legacy) {
        val name = if (legacy) "dev.knsql.ojdbc19.oracle.jdbc.OracleDriver" else "oracle.jdbc.OracleDriver"
        try {
            Class.forName(name).getDeclaredConstructor().newInstance() as Driver
        } catch (error: Throwable) {
            val label = if (legacy) "Oracle (ojdbc 19)" else "Oracle (ojdbc 23)"
            throw java.sql.SQLException(tr("{0} 驱动没有在这台设备上加载成功。{1}", label, error.explain()), error)
        }
    }

    private fun ensureDriver(className: String, label: String) {
        try {
            Class.forName(className)
        } catch (error: Throwable) {
            throw java.sql.SQLException(tr("{0} 驱动没有在这台设备上加载成功。{1}", label, error.explain()), error)
        }
    }

    private fun loadRoots(live: LiveConnection): List<MetaNode> {
        val meta = live.connection.metaData
        return when (live.engine) {
            Engine.Oracle -> oracleSchemas(live)
            Engine.MySql, Engine.MariaDb -> catalogs(meta).map { MetaNode(it, NodeKind.Catalog, catalog = it) }
            Engine.SqlServer -> catalogs(meta).map { MetaNode(it, NodeKind.Catalog, catalog = it) }
            Engine.Dameng -> damengSchemas(live)
            Engine.PostgreSql -> postgresSchemas(live)
            Engine.Sqlite -> loadRelations(live, null, null)
        }
    }

    private fun loadUnderCatalog(live: LiveConnection, catalog: String): List<MetaNode> {
        val schemas = schemas(live.connection.metaData, catalog)
        if (live.engine.mysqlLike || schemas.isEmpty() || schemas.all { it.equals(catalog, true) }) {
            return loadRelations(live, catalog, null)
        }
        return schemas.map { MetaNode(it, NodeKind.Schema, catalog = catalog, schema = it) }
    }

    private fun loadRelations(live: LiveConnection, catalog: String?, schema: String?): List<MetaNode> {
        if (live.engine == Engine.Oracle) return oracleRelations(live, schema)
        val meta = live.connection.metaData
        val nodes = mutableListOf<MetaNode>()
        val types = if (live.engine == Engine.PostgreSql) {
            arrayOf("TABLE", "VIEW", "PARTITIONED TABLE", "MATERIALIZED VIEW", "FOREIGN TABLE")
        } else {
            arrayOf("TABLE", "VIEW")
        }
        meta.getTables(catalog, schema, "%", types).use { set ->
            var count = 0
            while (set.next() && count < 800) {
                val name = set.getString("TABLE_NAME") ?: continue
                if (live.engine == Engine.Sqlite && name.startsWith("sqlite_")) continue
                val type = set.getString("TABLE_TYPE") ?: "TABLE"
                val kind = if (type.contains("VIEW", true)) NodeKind.View else NodeKind.Table
                val remark = runCatching { set.getString("REMARKS") }.getOrNull()?.takeIf { it.isNotBlank() }
                nodes += MetaNode(
                    label = name,
                    kind = kind,
                    catalog = catalog,
                    schema = if (live.engine == Engine.Sqlite) null else schema ?: set.getString("TABLE_SCHEM"),
                    relation = name,
                    relationType = type,
                    comment = remark,
                )
                count++
            }
        }
        if (live.engine == Engine.Dameng && schema != null && nodes.none { it.comment != null }) {
            val remarks = runCatching {
                pairs(live.connection, "SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ?", schema)
            }.getOrDefault(emptyMap())
            if (remarks.isNotEmpty()) {
                nodes.replaceAll { node -> remarks[node.label]?.let { node.copy(comment = it) } ?: node }
            }
        }
        if (live.engine == Engine.SqlServer && schema != null) {
            val remarks = runCatching { sqlServerTableComments(live.connection, catalog, schema) }.getOrDefault(emptyMap())
            if (remarks.isNotEmpty()) {
                nodes.replaceAll { node -> remarks[node.label]?.let { node.copy(comment = it) } ?: node }
            }
        }
        return nodes.sortedWith(compareBy({ it.kind != NodeKind.Table }, { it.label.lowercase() }))
    }

    private fun loadColumns(live: LiveConnection, parent: MetaNode): List<MetaNode> {
        if (live.engine == Engine.Oracle) return oracleColumns(live, parent)
        val nodes = mutableListOf<MetaNode>()
        live.connection.metaData.getColumns(parent.catalog, parent.schema, parent.relation, "%").use { set ->
            while (set.next()) {
                val name = set.getString("COLUMN_NAME") ?: continue
                val type = set.getString("TYPE_NAME") ?: ""
                nodes += MetaNode(
                    label = "$name  $type",
                    kind = NodeKind.Column,
                    catalog = parent.catalog,
                    schema = parent.schema,
                    relation = name,
                    expandable = false,
                    comment = runCatching { set.getString("REMARKS") }.getOrNull()?.takeIf { it.isNotBlank() },
                )
            }
        }
        if (live.engine == Engine.SqlServer && nodes.none { it.comment != null }) {
            val remarks = runCatching {
                sqlServerColumnComments(live.connection, parent.catalog, parent.schema, parent.relation.orEmpty())
            }.getOrDefault(emptyMap())
            if (remarks.isNotEmpty()) {
                nodes.replaceAll { node -> remarks[node.relation]?.let { node.copy(comment = it) } ?: node }
            }
        }
        return nodes.map { it.copy(relation = parent.relation) }
    }

    /** Schemas of the connected PostgreSQL database; system schemas stay hidden, the current one comes first. */
    private fun postgresSchemas(live: LiveConnection): List<MetaNode> {
        val current = runCatching { queryOne(live.connection, "SELECT current_schema()") }.getOrNull()
        return schemas(live.connection.metaData, null)
            .filter { name ->
                name == current || !(
                    name == "information_schema" || name == "pg_catalog" ||
                        name.startsWith("pg_toast") || name.startsWith("pg_temp")
                    )
            }
            .sortedWith(compareBy<String> { if (it == current) 0 else 1 }.thenBy { it })
            .map { MetaNode(it, NodeKind.Schema, schema = it) }
    }

    /** DM separates users from schemas, so schemas come from JDBC metadata rather than ALL_USERS. */
    private fun damengSchemas(live: LiveConnection): List<MetaNode> {
        val current = runCatching { currentOracleSchema(live.connection) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: sessionSchema(live.connection)
        val names = schemas(live.connection.metaData, null).ifEmpty {
            runCatching { queryColumn(live.connection, "SELECT DISTINCT OWNER FROM ALL_OBJECTS ORDER BY OWNER") }
                .getOrDefault(emptyList())
        }
        return names
            .filter { it.uppercase() !in damengHidden || it.equals(current, true) }
            .sortedWith(compareBy<String> { if (it.equals(current, true)) 0 else 1 }.thenBy { it })
            .map { MetaNode(it, NodeKind.Schema, schema = it) }
    }

    /** Column comments of a DM table: the Oracle-compatible view first, JDBC REMARKS otherwise. */
    private fun damengColumnComments(connection: Connection, table: TableRef): Map<String, String> {
        val schema = table.schema ?: runCatching { currentOracleSchema(connection) }.getOrNull()
            ?: sessionSchema(connection)
        val viaView = runCatching {
            pairs(
                connection,
                "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS " +
                    "WHERE OWNER IN (?, UPPER(?)) AND TABLE_NAME IN (?, UPPER(?)) AND COMMENTS IS NOT NULL",
                schema, schema, table.name, table.name,
            )
        }.getOrNull()
        if (!viaView.isNullOrEmpty()) return viaView
        val output = mutableMapOf<String, String>()
        connection.metaData.getColumns(null, schema, table.name, "%").use { set ->
            while (set.next()) {
                val name = set.getString("COLUMN_NAME") ?: continue
                runCatching { set.getString("REMARKS") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { output[name] = it }
            }
        }
        return output
    }

    private fun oracleSchemas(live: LiveConnection): List<MetaNode> {
        val current = queryOne(live.connection, "SELECT USER FROM DUAL")
        return queryColumn(live.connection, "SELECT USERNAME FROM ALL_USERS ORDER BY USERNAME")
            .filter { it !in oracleHidden }
            .sortedWith(compareBy<String> { if (it.equals(current, true)) 0 else 1 }.thenBy { it })
            .map { MetaNode(it, NodeKind.Schema, schema = it) }
    }

    private fun oracleRelations(live: LiveConnection, schema: String?): List<MetaNode> {
        val owner = schema?.trim().orEmpty()
        if (owner.isEmpty()) return emptyList()
        val nodes = mutableListOf<MetaNode>()
        rows(
            live.connection,
            "SELECT t.TABLE_NAME, c.COMMENTS FROM ALL_TABLES t LEFT JOIN ALL_TAB_COMMENTS c " +
                "ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME WHERE t.OWNER = ? ORDER BY t.TABLE_NAME",
            owner,
        ).take(800).forEach { (name, comment) ->
            nodes += MetaNode(name, NodeKind.Table, schema = owner, relation = name, relationType = "TABLE", comment = comment)
        }
        rows(
            live.connection,
            "SELECT v.VIEW_NAME, c.COMMENTS FROM ALL_VIEWS v LEFT JOIN ALL_TAB_COMMENTS c " +
                "ON c.OWNER = v.OWNER AND c.TABLE_NAME = v.VIEW_NAME WHERE v.OWNER = ? ORDER BY v.VIEW_NAME",
            owner,
        ).take(400).forEach { (name, comment) ->
            nodes += MetaNode(name, NodeKind.View, schema = owner, relation = name, relationType = "VIEW", comment = comment)
        }
        return nodes
    }

    private fun oracleColumns(live: LiveConnection, parent: MetaNode): List<MetaNode> {
        val owner = parent.schema?.trim().orEmpty()
        val relation = parent.relation?.trim().orEmpty()
        if (owner.isEmpty() || relation.isEmpty()) return emptyList()
        val nodes = mutableListOf<MetaNode>()
        live.connection.prepareStatement(
            "SELECT t.COLUMN_NAME, t.DATA_TYPE, c.COMMENTS FROM ALL_TAB_COLUMNS t LEFT JOIN ALL_COL_COMMENTS c " +
                "ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME AND c.COLUMN_NAME = t.COLUMN_NAME " +
                "WHERE t.OWNER = ? AND t.TABLE_NAME = ? ORDER BY t.COLUMN_ID",
        ).use { statement ->
            statement.setString(1, owner)
            statement.setString(2, relation)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val name = rows.getString(1) ?: continue
                    val type = rows.getString(2) ?: ""
                    nodes += MetaNode(
                        label = "$name  $type",
                        kind = NodeKind.Column,
                        schema = owner,
                        relation = relation,
                        expandable = false,
                        comment = rows.getString(3)?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        return nodes
    }

    /** First column plus an optional non-blank second column, for name / comment lookups. */
    private fun rows(connection: Connection, sql: String, vararg binds: String?): List<Pair<String, String?>> {
        connection.prepareStatement(sql).use { statement ->
            binds.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { set ->
                val output = mutableListOf<Pair<String, String?>>()
                while (set.next()) {
                    val name = set.getString(1) ?: continue
                    output += name to set.getString(2)?.takeIf { it.isNotBlank() }
                }
                return output
            }
        }
    }

    private fun pairs(connection: Connection, sql: String, vararg binds: String?): Map<String, String> =
        rows(connection, sql, *binds).mapNotNull { (name, value) -> value?.let { name to it } }.toMap()

    private fun currentOracleSchema(connection: Connection): String =
        queryOne(connection, "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL").orEmpty()

    private fun sqlServerPrefix(catalog: String?): String =
        if (catalog.isNullOrBlank()) "" else "[" + catalog.replace("]", "]]") + "]."

    /** SQL Server keeps comments as MS_Description extended properties, which JDBC REMARKS leaves empty. */
    private fun sqlServerTableComments(connection: Connection, catalog: String?, schema: String): Map<String, String> {
        val db = sqlServerPrefix(catalog)
        return pairs(
            connection,
            "SELECT o.name, CAST(ep.value AS nvarchar(4000)) FROM ${db}sys.objects o " +
                "JOIN ${db}sys.schemas s ON s.schema_id = o.schema_id " +
                "JOIN ${db}sys.extended_properties ep ON ep.class = 1 AND ep.major_id = o.object_id " +
                "AND ep.minor_id = 0 AND ep.name = 'MS_Description' WHERE s.name = ?",
            schema,
        )
    }

    private fun sqlServerColumnComments(
        connection: Connection,
        catalog: String?,
        schema: String?,
        table: String,
    ): Map<String, String> {
        val db = sqlServerPrefix(catalog)
        fun q(name: String) = "[" + name.replace("]", "]]") + "]"
        val objectName = db + (schema?.let { q(it) + "." } ?: "") + q(table)
        return pairs(
            connection,
            "SELECT c.name, CAST(ep.value AS nvarchar(4000)) FROM ${db}sys.columns c " +
                "JOIN ${db}sys.extended_properties ep ON ep.class = 1 AND ep.major_id = c.object_id " +
                "AND ep.minor_id = c.column_id AND ep.name = 'MS_Description' WHERE c.object_id = OBJECT_ID(?)",
            objectName,
        )
    }

    private fun queryOne(connection: Connection, sql: String): String? {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                return if (rows.next()) rows.getString(1) else null
            }
        }
    }

    private fun queryColumn(connection: Connection, sql: String, vararg binds: String): List<String> {
        connection.prepareStatement(sql).use { statement ->
            binds.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows ->
                val names = mutableListOf<String>()
                while (rows.next()) rows.getString(1)?.let(names::add)
                return names
            }
        }
    }

    private fun catalogs(meta: java.sql.DatabaseMetaData): List<String> {
        val names = mutableListOf<String>()
        meta.catalogs.use { set ->
            while (set.next()) set.getString(1)?.let(names::add)
        }
        return names.sorted()
    }

    private fun schemas(meta: java.sql.DatabaseMetaData, catalog: String?): List<String> {
        val names = mutableListOf<String>()
        meta.getSchemas(catalog, null).use { set ->
            while (set.next()) set.getString("TABLE_SCHEM")?.let(names::add)
        }
        if (names.isEmpty() && catalog == null) {
            meta.schemas.use { set ->
                while (set.next()) set.getString("TABLE_SCHEM")?.let(names::add)
            }
        }
        return names.distinct().sorted()
    }

    private fun readResult(set: ResultSet, maxRows: Int, started: Long, sql: String): QueryResult {
        val meta = set.metaData
        val count = meta.columnCount
        val columns = (1..count).map { meta.getColumnLabel(it) }
        val source = resultSource(meta, count) ?: singleSourceTable(sql)
        val rows = mutableListOf<List<String?>>()
        var truncated = false
        while (set.next()) {
            if (maxRows > 0 && rows.size >= maxRows) {
                truncated = true
                break
            }
            rows += (1..count).map { index -> formatCell(set, index) }
        }
        return QueryResult(
            columns = columns,
            rows = rows,
            updateCount = -1,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            truncated = truncated,
            source = source,
        )
    }

    /** The one table every column reports, when the driver fills in ResultSetMetaData table names. */
    private fun resultSource(meta: java.sql.ResultSetMetaData, count: Int): TableRef? {
        val refs = (1..count).mapNotNull { index ->
            val table = runCatching { meta.getTableName(index) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TableRef(
                runCatching { meta.getCatalogName(index) }.getOrNull()?.takeIf { it.isNotBlank() },
                runCatching { meta.getSchemaName(index) }.getOrNull()?.takeIf { it.isNotBlank() },
                table,
            )
        }.distinct()
        return refs.singleOrNull()
    }

    private fun formatCell(set: ResultSet, index: Int): String? {
        val value = set.getObject(index) ?: return null
        if (value is ByteArray) return "<binary ${value.size} bytes>"
        if (value is java.sql.Clob) return value.getSubString(1, minOf(value.length(), 20_000L).toInt())
        if (value is java.sql.Blob) return "<binary ${value.length()} bytes>"
        val text = value.toString()
        return if (text.length > 20_000) text.take(20_000) + "…" else text
    }
}

private val oracleHidden = setOf(
    "SYS", "SYSTEM", "XDB", "CTXSYS", "MDSYS", "OLAPSYS", "ORDSYS", "ORDDATA", "OUTLN",
    "DBSNMP", "APPQOSSYS", "WMSYS", "OJVMSYS", "GSMADMIN_INTERNAL", "AUDSYS", "DVSYS",
    "LBACSYS", "GGSYS", "DBSFWUSER", "REMOTE_SCHEDULER_AGENT", "ANONYMOUS",
    // 11g / 12c
    "EXFSYS", "SYSMAN", "MGMT_VIEW", "OWBSYS", "OWBSYS_AUDIT", "FLOWS_FILES", "APEX_PUBLIC_USER",
    "SPATIAL_CSW_ADMIN_USR", "SPATIAL_WFS_ADMIN_USR", "SI_INFORMTN_SCHEMA", "ORACLE_OCM", "DIP",
    "XS\$NULL", "MDDATA", "ORDPLUGINS", "SYSBACKUP", "SYSDG", "SYSKM", "SYSRAC",
)

/** DM system schemas; SYSDBA stays visible because many installs keep their tables there. */
private val damengHidden = setOf("SYS", "SYSAUDITOR", "SYSSSO", "CTISYS", "SYSJOB")

/**
 * MariaDB Connector/J, for MySQL 5.5 to 9.x and MariaDB. allowPublicKeyRetrieval lets MySQL 8's
 * default caching_sha2_password (and sha256_password) log in without TLS; with [encrypt] the link
 * uses TLS and trusts the server certificate, like the SQL Server option.
 */
private fun mysqlUrl(host: String, port: Int, database: String, encrypt: Boolean): String {
    val path = database.ifBlank { "" }
    val ssl = if (encrypt) "trust" else "disable"
    return "jdbc:mariadb://$host:$port/$path?connectTimeout=15000&socketTimeout=660000&tcpKeepAlive=true" +
        "&useUnicode=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&sslMode=$ssl"
}

/** MariaDB 10.x / 11.x through the same MariaDB Connector/J as MySQL. */
private fun mariaDbUrl(host: String, port: Int, database: String, encrypt: Boolean): String =
    mysqlUrl(host, port, database, encrypt)

/**
 * pgjdbc. GSS encryption is switched off because it needs org.ietf.jgss, which Android lacks; with
 * [encrypt] the link requires TLS without checking the certificate chain (sslmode=require).
 * stringtype=unspecified lets text bind values land in uuid / json / enum / date columns.
 */
internal fun postgresUrl(host: String, port: Int, database: String, encrypt: Boolean): String {
    val db = java.net.URLEncoder.encode(database.ifBlank { "postgres" }, "UTF-8")
    val ssl = if (encrypt) "require" else "disable"
    return "jdbc:postgresql://$host:$port/$db?connectTimeout=15&socketTimeout=660&tcpKeepAlive=true" +
        "&stringtype=unspecified&gssEncMode=disable&sslmode=$ssl&ApplicationName=KnSQL"
}

/** DM 7 / DM 8. The optional database field is the default schema. */
private fun damengUrl(host: String, port: Int, schema: String): String {
    val query = if (schema.isBlank()) "" else "?schema=" + java.net.URLEncoder.encode(schema, "UTF-8")
    return "jdbc:dm://$host:$port$query"
}

private fun oracleUrl(host: String, port: Int, database: String, sid: Boolean): String {
    return if (sid) "jdbc:oracle:thin:@$host:$port:$database" else "jdbc:oracle:thin:@//$host:$port/$database"
}

/**
 * SQL Server. A named instance (HOST\\SQLEXPRESS) is found through the SQL Browser service on UDP 1434;
 * an explicit port wins over the instance name in mssql-jdbc, so the default 1433 is left out then.
 */
internal fun sqlServerUrl(
    host: String,
    port: Int,
    instance: String,
    database: String,
    encrypt: Boolean,
    trustServerCertificate: Boolean,
    microsoft: Boolean = microsoftSqlServerDriver(),
): String {
    val named = instance.isNotBlank()
    val usePort = !named || port != Engine.SqlServer.defaultPort
    if (microsoft) {
        val address = buildString {
            append(host)
            if (named) append("\\").append(instance)
            if (usePort) append(":").append(port)
        }
        val db = if (database.isBlank()) "" else ";databaseName=$database"
        val secure = if (encrypt) "true" else "false"
        return "jdbc:sqlserver://$address$db;encrypt=$secure;trustServerCertificate=$trustServerCertificate;loginTimeout=8"
    }
    Class.forName("net.sourceforge.jtds.jdbc.Driver")
    val db = if (database.isBlank()) "" else "/$database"
    val address = if (usePort) "$host:$port" else host
    val named2 = if (named) ";instance=$instance" else ""
    return "jdbc:jtds:sqlserver://$address$db$named2;loginTimeout=8;socketKeepAlive=true"
}

private fun microsoftSqlServerDriver(): Boolean = runCatching {
    Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")
    true
}.getOrDefault(false)

private fun Throwable.explain(): String = generateSequence(this) { it.cause }
    .take(5)
    .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

private fun registerDrivers() {
    listOf(
        "org.mariadb.jdbc.Driver",
        "oracle.jdbc.OracleDriver",
        "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        "net.sourceforge.jtds.jdbc.Driver",
        "dm.jdbc.driver.DmDriver",
        "org.postgresql.Driver",
        "org.sqlite.JDBC",
    ).forEach { name -> runCatching { Class.forName(name) } }
}

/**
 * One line for the title bar. Oracle reports "Oracle Database 19c Enterprise Edition Release
 * 19.0.0.0.0 - Production\nVersion 19.22.0.0.0", which becomes
 * "Oracle Database 19c Enterprise Edition 19.22.0.0.0".
 */
internal fun compactServerInfo(product: String?, version: String?): String {
    val name = product?.trim().orEmpty()
    val lines = version.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return name
    val patch = lines.drop(1).lastOrNull { it.startsWith("Version ") }?.removePrefix("Version ")
    var headline = lines.first()
    if (patch != null) headline = headline.replace(Regex("\\s+Release\\s+\\S+.*$"), "")
    headline = headline.removeSuffix("- Production").trim()
    val full = if (name.isEmpty() || headline.startsWith(name, ignoreCase = true)) headline else "$name $headline"
    return listOfNotNull(full, patch).joinToString(" ")
}

/** Wrong credentials, a locked / expired account or an unknown service: another driver won't help. */
private fun java.sql.SQLException.isOracleLoginError(): Boolean {
    val text = generateSequence<Throwable>(this) { it.cause }.take(6).joinToString(" ") { it.message.orEmpty() }
    return listOf("ORA-01017", "ORA-28000", "ORA-28001", "ORA-01045", "ORA-01031", "ORA-12514", "ORA-12505", "ORA-12154")
        .any { it in text }
}

/** The server could not be reached at all, as opposed to a login or SQL error. */
private fun java.sql.SQLException.isNetworkFailure(): Boolean {
    val text = generateSequence<Throwable>(this) { it.cause }.take(6)
        .joinToString(" ") { "${it.javaClass.name} ${it.message}" }
        .lowercase()
    return listOf(
        "ora-12170", "ora-12541", "ora-12543", "ora-17002", "ora-12153", "connect timed out", "connection timed out",
        "sockettimeoutexception", "connectexception", "noroutetohost", "network is unreachable",
        "communications link failure", "unknownhostexception", "tcp/ip connection to the host",
    ).any { it in text }
}
