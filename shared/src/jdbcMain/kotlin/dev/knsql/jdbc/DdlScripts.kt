package dev.knsql.jdbc

import dev.knsql.i18n.tr
import dev.knsql.i18n.trc
import dev.knsql.model.Engine
import dev.knsql.model.MetaNode
import dev.knsql.model.NodeKind
import java.sql.Connection
import java.sql.SQLException

/**
 * Builds DDL for one relation or for a whole schema / database.
 *
 * [step] is called between objects: it reports progress and throws when the caller has been
 * cancelled, so long exports stop at the next object boundary.
 */
internal fun generateDdl(
    engine: Engine,
    connection: Connection,
    node: MetaNode,
    step: (String) -> Unit,
): String = when (engine) {
    Engine.Oracle -> OracleDdl(connection, step).generate(node)
    Engine.MySql, Engine.MariaDb -> MySqlDdl(connection, step).generate(node)
    Engine.SqlServer -> SqlServerDdl(connection, step).generate(node)
    Engine.Dameng -> DamengDdl(connection, step).generate(node)
    Engine.PostgreSql -> PostgresDdl(connection, step).generate(node)
    Engine.Sqlite -> SqliteDdl(connection, step).generate(node)
}

private fun Connection.strings(sql: String, vararg binds: String): List<List<String?>> {
    prepareStatement(sql).use { statement ->
        binds.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows ->
            val width = rows.metaData.columnCount
            val output = mutableListOf<List<String?>>()
            while (rows.next()) output += (1..width).map { rows.getString(it) }
            return output
        }
    }
}

private fun Connection.plain(sql: String): List<List<String?>> {
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            val width = rows.metaData.columnCount
            val output = mutableListOf<List<String?>>()
            while (rows.next()) output += (1..width).map { rows.getString(it) }
            return output
        }
    }
}

private fun SQLException.brief(): String = message.orEmpty().lineSequence().firstOrNull().orEmpty().trim()

private fun section(title: String): String = "\n-- ----------------------------------------\n-- $title\n-- ----------------------------------------\n"

private fun requireName(value: String?, missing: String): String =
    value?.trim()?.takeIf { it.isNotEmpty() } ?: error(missing)

// ---------------------------------------------------------------- Oracle

private class OracleDdl(private val connection: Connection, private val step: (String) -> Unit) {
    fun generate(node: MetaNode): String = when (node.kind) {
        NodeKind.Table -> {
            configure(scriptMode = false)
            relation(requireName(node.schema, tr("缺少 Schema")), requireName(node.relation, tr("缺少表名")))
        }
        NodeKind.View -> {
            configure(scriptMode = false)
            view(requireName(node.schema, tr("缺少 Schema")), requireName(node.relation, tr("缺少视图名")))
        }
        NodeKind.Schema -> {
            configure(scriptMode = true)
            schema(requireName(node.schema ?: node.label, tr("缺少 Schema")))
        }
        else -> error(tr("选择一个表、视图或 Schema"))
    }

    private fun configure(scriptMode: Boolean) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                BEGIN
                  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'DEFAULT');
                  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'PRETTY', TRUE);
                  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'SQLTERMINATOR', TRUE);
                  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'STORAGE', FALSE);
                  DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM, 'REF_CONSTRAINTS', ${if (scriptMode) "FALSE" else "TRUE"});
                END;
                """.trimIndent(),
            )
        }
    }

    private fun one(type: String, name: String, owner: String): String =
        connection.strings("SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL", type, name, owner)
            .firstOrNull()?.firstOrNull()?.trim().orEmpty()

    /** Fetches DDL for many objects of one type, falling back to one call per object on failure. */
    private fun many(type: String, owner: String, names: List<String>, label: String): List<String> {
        val output = mutableListOf<String>()
        var done = 0
        names.chunked(100).forEach { chunk ->
            step("$label ${done}/${names.size}")
            val placeholders = chunk.joinToString(",") { "?" }
            val fetched = try {
                connection.strings(
                    "SELECT COLUMN_VALUE, DBMS_METADATA.GET_DDL(?, COLUMN_VALUE, ?) " +
                        "FROM TABLE(SYS.ODCIVARCHAR2LIST($placeholders))",
                    type, owner, *chunk.toTypedArray(),
                ).associate { (it[0] ?: "") to it[1].orEmpty().trim() }
            } catch (_: SQLException) {
                null
            }
            chunk.forEach { name ->
                val ddl = fetched?.get(name) ?: try {
                    step("$label ${done}/${names.size}")
                    one(type, name, owner)
                } catch (error: SQLException) {
                    "-- ${tr("无法导出 {0} {1}.{2}：{3}", type, owner, name, error.brief())}"
                }
                if (ddl.isNotBlank()) output += ddl
                done++
            }
        }
        return output
    }

    private fun relation(owner: String, table: String): String {
        step(tr("读取表 {0}.{1}", owner, table))
        val parts = mutableListOf(one("TABLE", table, owner))
        step(tr("读取索引"))
        val indexes = connection.strings(indexSql(tableFilter = true), owner, owner, table).mapNotNull { it[0] }
        parts += many("INDEX", owner, indexes, tr("索引"))
        parts += comments(owner, table)
        step(tr("读取触发器"))
        connection.strings(
            "SELECT OWNER, TRIGGER_NAME FROM ALL_TRIGGERS WHERE TABLE_OWNER = ? AND TABLE_NAME = ? ORDER BY TRIGGER_NAME",
            owner, table,
        ).forEach { row ->
            val trigger = row[1] ?: return@forEach
            parts += try {
                one("TRIGGER", trigger, row[0] ?: owner)
            } catch (error: SQLException) {
                "-- ${tr("无法导出触发器 {0}：{1}", trigger, error.brief())}"
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun view(owner: String, name: String): String {
        step(tr("读取视图 {0}.{1}", owner, name))
        return (listOf(one("VIEW", name, owner)) + comments(owner, name)).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun comments(owner: String, table: String?): List<String> {
        step(tr("读取注释"))
        val filter = if (table == null) "" else " AND c.TABLE_NAME = ?"
        val binds = if (table == null) arrayOf(owner) else arrayOf(owner, table)
        val output = mutableListOf<String>()
        connection.strings(
            "SELECT c.TABLE_NAME, c.COMMENTS FROM ALL_TAB_COMMENTS c WHERE c.OWNER = ? AND c.COMMENTS IS NOT NULL" +
                filter + " ORDER BY c.TABLE_NAME",
            *binds,
        ).forEach { row ->
            val name = row[0] ?: return@forEach
            output += "COMMENT ON TABLE ${quote(owner)}.${quote(name)} IS ${literal(row[1].orEmpty())};"
        }
        connection.strings(
            "SELECT c.TABLE_NAME, c.COLUMN_NAME, c.COMMENTS FROM ALL_COL_COMMENTS c " +
                "LEFT JOIN ALL_TAB_COLUMNS t ON t.OWNER = c.OWNER AND t.TABLE_NAME = c.TABLE_NAME AND t.COLUMN_NAME = c.COLUMN_NAME " +
                "WHERE c.OWNER = ? AND c.COMMENTS IS NOT NULL" + filter + " ORDER BY c.TABLE_NAME, t.COLUMN_ID",
            *binds,
        ).forEach { row ->
            val name = row[0] ?: return@forEach
            val column = row[1] ?: return@forEach
            output += "COMMENT ON COLUMN ${quote(owner)}.${quote(name)}.${quote(column)} IS ${literal(row[2].orEmpty())};"
        }
        return if (output.isEmpty()) emptyList() else listOf(output.joinToString("\n"))
    }

    private fun schema(owner: String): String {
        val out = StringBuilder()
        step(tr("列出 {0} 的对象", owner))
        fun names(type: String): List<String> = connection.strings(
            "SELECT OBJECT_NAME FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_TYPE = ? AND GENERATED = 'N' " +
                "AND OBJECT_NAME NOT LIKE 'BIN$%' AND OBJECT_NAME NOT LIKE 'ISEQ$$%' ORDER BY OBJECT_NAME",
            owner, type,
        ).mapNotNull { it[0] }

        fun emit(title: String, ddls: List<String>) {
            if (ddls.isEmpty()) return
            out.append(section(tr("{0}（{1}）", title, ddls.size)))
            ddls.forEach { out.append(it.trim()).append("\n\n") }
        }

        val tables = connection.strings(
            "SELECT t.TABLE_NAME FROM ALL_TABLES t WHERE t.OWNER = ? AND t.NESTED = 'NO' AND t.SECONDARY = 'N' " +
                "AND (t.IOT_TYPE IS NULL OR t.IOT_TYPE = 'IOT') AND t.DROPPED = 'NO' AND t.TABLE_NAME NOT LIKE 'BIN$%' " +
                "AND NOT EXISTS (SELECT 1 FROM ALL_MVIEWS m WHERE m.OWNER = t.OWNER AND m.MVIEW_NAME = t.TABLE_NAME) " +
                "ORDER BY t.TABLE_NAME",
            owner,
        ).mapNotNull { it[0] }
        val tableSet = tables.toHashSet()

        emit(tr("序列"), many("SEQUENCE", owner, names("SEQUENCE"), tr("序列")))
        emit(tr("类型"), many("TYPE", owner, names("TYPE"), tr("类型")))
        emit(trc("ddl", "表"), many("TABLE", owner, tables, trc("ddl", "表")))
        step(tr("列出索引"))
        val indexes = connection.strings(indexSql(tableFilter = false), owner, owner)
            .filter { it[1] in tableSet }
            .mapNotNull { it[0] }
        emit(tr("索引"), many("INDEX", owner, indexes, tr("索引")))
        val comments = comments(owner, null)
        if (comments.isNotEmpty()) emit(tr("注释"), comments)
        step(tr("列出外键"))
        val foreign = connection.strings(
            "SELECT CONSTRAINT_NAME, TABLE_NAME FROM ALL_CONSTRAINTS WHERE OWNER = ? AND CONSTRAINT_TYPE = 'R' " +
                "AND CONSTRAINT_NAME NOT LIKE 'BIN$%' ORDER BY TABLE_NAME, CONSTRAINT_NAME",
            owner,
        ).filter { it[1] in tableSet }.mapNotNull { it[0] }
        emit(tr("外键"), many("REF_CONSTRAINT", owner, foreign, tr("外键")))
        emit(trc("ddl", "视图"), many("VIEW", owner, names("VIEW"), trc("ddl", "视图")))
        emit(tr("物化视图"), many("MATERIALIZED_VIEW", owner, names("MATERIALIZED VIEW"), tr("物化视图")))
        emit(tr("函数"), many("FUNCTION", owner, names("FUNCTION"), tr("函数")))
        emit(tr("存储过程"), many("PROCEDURE", owner, names("PROCEDURE"), tr("存储过程")))
        emit(tr("包"), many("PACKAGE", owner, names("PACKAGE"), tr("包")))
        emit(tr("触发器"), many("TRIGGER", owner, names("TRIGGER"), tr("触发器")))
        emit(tr("同义词"), many("SYNONYM", owner, names("SYNONYM"), tr("同义词")))
        if (out.isEmpty()) return "-- ${tr("{0} 下没有可导出的对象，或者当前账号没有查看权限。", owner)}"
        return out.toString().trim()
    }

    private fun indexSql(tableFilter: Boolean): String =
        "SELECT i.INDEX_NAME, i.TABLE_NAME FROM ALL_INDEXES i WHERE i.OWNER = ? AND i.TABLE_OWNER = ? " +
            (if (tableFilter) "AND i.TABLE_NAME = ? " else "") +
            "AND i.GENERATED = 'N' AND i.INDEX_TYPE NOT IN ('LOB', 'IOT - TOP') AND i.DROPPED = 'NO' " +
            "AND i.INDEX_NAME NOT LIKE 'BIN$%' " +
            "AND NOT EXISTS (SELECT 1 FROM ALL_CONSTRAINTS c WHERE c.OWNER = i.TABLE_OWNER AND c.TABLE_NAME = i.TABLE_NAME " +
            "AND c.INDEX_NAME = i.INDEX_NAME AND c.CONSTRAINT_TYPE IN ('P', 'U')) " +
            "ORDER BY i.TABLE_NAME, i.INDEX_NAME"

    private fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    private fun literal(text: String) = "'" + text.replace("'", "''") + "'"
}

// ---------------------------------------------------------------- MySQL / MariaDB

// ---------------------------------------------------------------- Dameng (DM)

/**
 * DM 8 implements DBMS_METADATA; older or trimmed installs may not, so every object falls back to
 * DM's own TABLEDEF, the Oracle-compatible ALL_VIEWS / ALL_SOURCE views, and finally to DDL built
 * from JDBC metadata.
 */
private class DamengDdl(private val connection: Connection, private val step: (String) -> Unit) {
    fun generate(node: MetaNode): String = when (node.kind) {
        NodeKind.Table -> table(requireName(node.schema, tr("缺少 Schema")), requireName(node.relation, tr("缺少表名")))
        NodeKind.View -> view(requireName(node.schema, tr("缺少 Schema")), requireName(node.relation, tr("缺少视图名")))
        NodeKind.Schema -> schema(requireName(node.schema ?: node.label, tr("缺少 Schema")))
        else -> error(tr("选择一个表、视图或 Schema"))
    }

    private fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    private fun literal(text: String) = "'" + text.replace("'", "''") + "'"

    private fun first(sql: String, vararg binds: String): String? = runCatching {
        connection.strings(sql, *binds).firstOrNull()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun metadata(type: String, owner: String, name: String): String? =
        first("SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL", type, name, owner)

    private fun terminated(sql: String): String = sql.trim().let { if (it.endsWith(";") || it.endsWith("/")) it else "$it;" }

    private fun table(owner: String, name: String): String {
        step(tr("读取表 {0}.{1}", owner, name))
        val ddl = metadata("TABLE", owner, name)
            ?: first("SELECT TABLEDEF(?, ?) FROM DUAL", owner, name)
            ?: built(owner, name)
        val parts = mutableListOf(terminated(ddl))
        if (!ddl.contains("COMMENT ON", ignoreCase = true)) parts += comments(owner, name)
        return parts.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** CREATE TABLE from JDBC metadata, when the server offers no DDL function. */
    private fun built(owner: String, name: String): String {
        val structure = loadStructure(Engine.Dameng, connection, dev.knsql.model.TableRef(null, owner, name))
        val lines = structure.columns.map { column ->
            buildString {
                append("    ").append(quote(column.name)).append(' ').append(column.type)
                if (column.autoIncrement) append(" IDENTITY(1, 1)")
                column.defaultValue?.let { append(" DEFAULT ").append(it) }
                if (!column.nullable) append(" NOT NULL")
            }
        }.toMutableList()
        val keys = structure.columns.filter { it.primaryKey }.map { quote(it.name) }
        if (keys.isNotEmpty()) lines += "    PRIMARY KEY (" + keys.joinToString(", ") + ")"
        return "CREATE TABLE ${quote(owner)}.${quote(name)}\n(\n" + lines.joinToString(",\n") + "\n);"
    }

    private fun comments(owner: String, table: String): List<String> {
        val target = "${quote(owner)}.${quote(table)}"
        val output = mutableListOf<String>()
        first("SELECT COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?", owner, table)?.let {
            output += "COMMENT ON TABLE $target IS ${literal(it)};"
        }
        runCatching {
            connection.strings(
                "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ? AND COMMENTS IS NOT NULL",
                owner, table,
            )
        }.getOrDefault(emptyList()).forEach { row ->
            val column = row[0] ?: return@forEach
            val text = row[1]?.takeIf { it.isNotBlank() } ?: return@forEach
            output += "COMMENT ON COLUMN $target.${quote(column)} IS ${literal(text)};"
        }
        return output
    }

    private fun view(owner: String, name: String): String {
        step(tr("读取视图 {0}.{1}", owner, name))
        val text = metadata("VIEW", owner, name)
            ?: first("SELECT TEXT FROM ALL_VIEWS WHERE OWNER = ? AND VIEW_NAME = ?", owner, name)?.let { body ->
                if (body.startsWith("CREATE", ignoreCase = true)) body
                else "CREATE OR REPLACE VIEW ${quote(owner)}.${quote(name)} AS\n$body"
            }
            ?: return "-- ${tr("无法导出 {0} {1}.{2}：{3}", "VIEW", owner, name, "DBMS_METADATA / ALL_VIEWS")}"
        return terminated(text)
    }

    /** Functions, procedures, packages and triggers: DBMS_METADATA, else the source lines. */
    private fun source(type: String, owner: String, name: String): String {
        metadata(type.replace(' ', '_'), owner, name)?.let { return terminated(it) + "\n/" }
        val lines = runCatching {
            connection.strings(
                "SELECT TEXT FROM ALL_SOURCE WHERE OWNER = ? AND NAME = ? AND TYPE = ? ORDER BY LINE",
                owner, name, type,
            ).mapNotNull { it[0] }
        }.getOrDefault(emptyList())
        if (lines.isEmpty()) return "-- ${tr("无法导出 {0} {1}.{2}：{3}", type, owner, name, "ALL_SOURCE")}"
        val body = lines.joinToString("") { if (it.endsWith("\n")) it else it + "\n" }.trim()
        val create = if (body.startsWith("CREATE", ignoreCase = true)) body else "CREATE OR REPLACE $body"
        return "$create\n/"
    }

    private fun schema(owner: String): String {
        val out = StringBuilder()
        step(tr("列出 {0} 的对象", owner))
        val objects = runCatching {
            connection.strings(
                "SELECT OBJECT_TYPE, OBJECT_NAME FROM ALL_OBJECTS WHERE OWNER = ? ORDER BY OBJECT_TYPE, OBJECT_NAME",
                owner,
            )
        }.getOrDefault(emptyList())
        fun names(type: String) = objects.filter { it[0].equals(type, true) }.mapNotNull { it[1] }
        val tables = names("TABLE").ifEmpty {
            val found = mutableListOf<String>()
            connection.metaData.getTables(null, owner, "%", arrayOf("TABLE")).use { set ->
                while (set.next()) set.getString("TABLE_NAME")?.let(found::add)
            }
            found
        }
        val views = names("VIEW").ifEmpty {
            val found = mutableListOf<String>()
            connection.metaData.getTables(null, owner, "%", arrayOf("VIEW")).use { set ->
                while (set.next()) set.getString("TABLE_NAME")?.let(found::add)
            }
            found
        }

        fun emit(title: String, items: List<String>, render: (String) -> String) {
            if (items.isEmpty()) return
            out.append(section(tr("{0}（{1}）", title, items.size)))
            items.forEachIndexed { index, item ->
                step("$title $index/${items.size}")
                val ddl = try {
                    render(item)
                } catch (error: SQLException) {
                    "-- ${tr("无法导出 {0} {1}.{2}：{3}", title, owner, item, error.brief())}"
                }
                out.append(ddl.trim()).append("\n\n")
            }
        }

        emit(tr("序列"), names("SEQUENCE")) { name ->
            metadata("SEQUENCE", owner, name)?.let(::terminated) ?: "CREATE SEQUENCE ${quote(owner)}.${quote(name)};"
        }
        emit(trc("ddl", "表"), tables) { table(owner, it) }
        emit(trc("ddl", "视图"), views) { view(owner, it) }
        emit(tr("函数"), names("FUNCTION")) { source("FUNCTION", owner, it) }
        emit(tr("存储过程"), names("PROCEDURE")) { source("PROCEDURE", owner, it) }
        emit(tr("包"), names("PACKAGE")) { source("PACKAGE", owner, it) }
        emit(tr("触发器"), names("TRIGGER")) { source("TRIGGER", owner, it) }
        if (out.isEmpty()) return "-- ${tr("{0} 下没有可导出的对象，或者当前账号没有查看权限。", owner)}"
        return out.toString().trim()
    }
}

private class MySqlDdl(private val connection: Connection, private val step: (String) -> Unit) {
    fun generate(node: MetaNode): String {
        val database = node.catalog ?: node.schema ?: connection.catalog
        return when (node.kind) {
            NodeKind.Table -> table(requireName(database, tr("缺少数据库名")), requireName(node.relation, tr("缺少表名")), withTriggers = true)
            NodeKind.View -> view(requireName(database, tr("缺少数据库名")), requireName(node.relation, tr("缺少视图名")))
            NodeKind.Catalog, NodeKind.Schema -> database(requireName(database ?: node.label, tr("缺少数据库名")))
            else -> error(tr("选择一个表、视图或数据库"))
        }
    }

    private fun q(name: String) = "`" + name.replace("`", "``") + "`"

    private fun create(sql: String, column: Int): String =
        connection.plain(sql).firstOrNull()?.getOrNull(column - 1)?.trim().orEmpty()

    private fun table(db: String, name: String, withTriggers: Boolean): String {
        step(tr("读取表 {0}.{1}", db, name))
        val parts = mutableListOf(create("SHOW CREATE TABLE ${q(db)}.${q(name)}", 2) + ";")
        if (withTriggers) parts += triggers(db, name)
        return parts.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun view(db: String, name: String): String {
        step(tr("读取视图 {0}.{1}", db, name))
        return create("SHOW CREATE VIEW ${q(db)}.${q(name)}", 2) + ";"
    }

    private fun triggers(db: String, table: String?): List<String> {
        val filter = if (table == null) "" else " AND EVENT_OBJECT_TABLE = ?"
        val binds = if (table == null) arrayOf(db) else arrayOf(db, table)
        return connection.strings(
            "SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ?$filter ORDER BY TRIGGER_NAME",
            *binds,
        ).mapNotNull { it[0] }.map { trigger ->
            step(tr("读取触发器 {0}", trigger))
            block(runCatching { create("SHOW CREATE TRIGGER ${q(db)}.${q(trigger)}", 3) }, tr("触发器 {0}", trigger))
        }
    }

    private fun block(result: Result<String>, what: String): String {
        val body = result.getOrElse { return "-- ${tr("无法导出 {0}：{1}", what, (it as? SQLException)?.brief() ?: it.message)}" }
        if (body.isBlank()) return "-- ${tr("无法导出 {0}：当前账号没有查看定义的权限", what)}"
        return "DELIMITER $$\n$body $$\nDELIMITER ;"
    }

    private fun database(db: String): String {
        val out = StringBuilder()
        step(tr("读取数据库 {0}", db))
        val header = runCatching { create("SHOW CREATE DATABASE ${q(db)}", 2) }.getOrDefault("CREATE DATABASE ${q(db)}")
        out.append(header.replaceFirst("CREATE DATABASE ", "CREATE DATABASE IF NOT EXISTS ")).append(";\n")
        out.append("USE ${q(db)};\n\nSET FOREIGN_KEY_CHECKS = 0;\n")

        val relations = connection.strings(
            "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME",
            db,
        )
        val tables = relations.filter { it[1] == "BASE TABLE" }.mapNotNull { it[0] }
        val views = relations.filter { it[1] == "VIEW" }.mapNotNull { it[0] }
        if (tables.isNotEmpty()) {
            out.append(section(tr("{0}（{1}）", trc("ddl", "表"), tables.size)))
            tables.forEachIndexed { index, name ->
                step("${trc("ddl", "表")} $index/${tables.size}")
                out.append(runCatching { table(db, name, withTriggers = false) }
                    .getOrElse { "-- ${tr("无法导出表 {0}：{1}", name, it.message)}" }).append("\n\n")
            }
        }
        if (views.isNotEmpty()) {
            out.append(section(tr("{0}（{1}）", trc("ddl", "视图"), views.size)))
            views.forEachIndexed { index, name ->
                step("${trc("ddl", "视图")} $index/${views.size}")
                out.append(runCatching { view(db, name) }.getOrElse { "-- ${tr("无法导出视图 {0}：{1}", name, it.message)}" }).append("\n\n")
            }
        }
        val routines = connection.strings(
            "SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ? " +
                "ORDER BY ROUTINE_TYPE, ROUTINE_NAME",
            db,
        )
        if (routines.isNotEmpty()) {
            out.append(section(tr("{0}（{1}）", tr("函数与存储过程"), routines.size)))
            routines.forEachIndexed { index, row ->
                val name = row[0] ?: return@forEachIndexed
                val kind = if (row[1] == "FUNCTION") "FUNCTION" else "PROCEDURE"
                step("${tr("例程")} $index/${routines.size}")
                out.append(block(runCatching { create("SHOW CREATE $kind ${q(db)}.${q(name)}", 3) }, "$kind $name"))
                    .append("\n\n")
            }
        }
        val triggers = triggers(db, null)
        if (triggers.isNotEmpty()) {
            out.append(section(tr("{0}（{1}）", tr("触发器"), triggers.size)))
            triggers.forEach { out.append(it).append("\n\n") }
        }
        out.append("SET FOREIGN_KEY_CHECKS = 1;")
        return out.toString()
    }
}

// ---------------------------------------------------------------- SQL Server

private class SqlServerDdl(private val connection: Connection, private val step: (String) -> Unit) {
    fun generate(node: MetaNode): String {
        val database = node.catalog
        val previous = runCatching { connection.catalog }.getOrNull()
        if (!database.isNullOrBlank() && !database.equals(previous, true)) connection.catalog = database
        try {
            return when (node.kind) {
                NodeKind.Table -> table(node.schema ?: "dbo", requireName(node.relation, tr("缺少表名")), withForeignKeys = true)
                NodeKind.View -> module(node.schema ?: "dbo", requireName(node.relation, tr("缺少视图名")))
                NodeKind.Schema -> script(node.schema ?: node.label)
                NodeKind.Catalog -> script(null)
                else -> error(tr("选择一个表、视图、Schema 或数据库"))
            }
        } finally {
            if (!previous.isNullOrBlank() && !previous.equals(runCatching { connection.catalog }.getOrNull(), true)) {
                runCatching { connection.catalog = previous }
            }
        }
    }

    private fun q(name: String) = "[" + name.replace("]", "]]") + "]"

    private fun objectName(schema: String, name: String) = "${q(schema)}.${q(name)}"

    private fun module(schema: String, name: String): String {
        step(tr("读取 {0}.{1}", schema, name))
        val definition = connection.strings(
            "SELECT m.definition FROM sys.sql_modules m WHERE m.object_id = OBJECT_ID(?)",
            objectName(schema, name),
        ).firstOrNull()?.firstOrNull()
        return if (definition.isNullOrBlank()) {
            "-- ${tr("无法读取 {0}.{1} 的定义，可能已加密或没有 VIEW DEFINITION 权限", schema, name)}"
        } else {
            definition.trim() + "\nGO"
        }
    }

    private fun typeName(type: String, maxLength: Int, precision: Int, scale: Int): String {
        val base = q(type)
        return when (type.lowercase()) {
            "varchar", "char", "varbinary", "binary" -> base + if (maxLength == -1) "(max)" else "($maxLength)"
            "nvarchar", "nchar" -> base + if (maxLength == -1) "(max)" else "(${maxLength / 2})"
            "decimal", "numeric" -> "$base($precision, $scale)"
            "datetime2", "time", "datetimeoffset" -> "$base($scale)"
            "float" -> if (precision in 1..52) "$base($precision)" else base
            else -> base
        }
    }

    private fun table(schema: String, name: String, withForeignKeys: Boolean): String {
        step(tr("读取表 {0}.{1}", schema, name))
        val id = objectName(schema, name)
        val columns = connection.strings(
            """
            SELECT c.name, t.name, CAST(c.max_length AS varchar(12)), CAST(c.precision AS varchar(12)),
                   CAST(c.scale AS varchar(12)), CAST(c.is_nullable AS varchar(1)), CAST(c.is_identity AS varchar(1)),
                   CAST(ic.seed_value AS varchar(40)), CAST(ic.increment_value AS varchar(40)),
                   dc.name, dc.definition, cc.definition, CAST(cc.is_persisted AS varchar(1))
            FROM sys.columns c
            JOIN sys.types t ON t.user_type_id = c.user_type_id
            LEFT JOIN sys.identity_columns ic ON ic.object_id = c.object_id AND ic.column_id = c.column_id
            LEFT JOIN sys.default_constraints dc ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id
            LEFT JOIN sys.computed_columns cc ON cc.object_id = c.object_id AND cc.column_id = c.column_id
            WHERE c.object_id = OBJECT_ID(?)
            ORDER BY c.column_id
            """.trimIndent(),
            id,
        )
        if (columns.isEmpty()) return "-- ${tr("找不到表 {0}.{1}，或者没有查看权限", schema, name)}"
        val lines = columns.map { row ->
            val column = q(row[0].orEmpty())
            val computed = row[11]
            if (computed != null) {
                "    $column AS $computed" + if (row[12] == "1") " PERSISTED" else ""
            } else {
                buildString {
                    append("    ").append(column).append(' ')
                    append(typeName(row[1].orEmpty(), row[2]?.toIntOrNull() ?: 0, row[3]?.toIntOrNull() ?: 0, row[4]?.toIntOrNull() ?: 0))
                    if (row[6] == "1") append(" IDENTITY(${row[7] ?: "1"}, ${row[8] ?: "1"})")
                    append(if (row[5] == "1") " NULL" else " NOT NULL")
                    if (row[10] != null) append(" CONSTRAINT ").append(q(row[9].orEmpty())).append(" DEFAULT ").append(row[10])
                }
            }
        }.toMutableList()

        val indexRows = connection.strings(
            """
            SELECT i.name, CAST(i.is_primary_key AS varchar(1)), CAST(i.is_unique_constraint AS varchar(1)),
                   CAST(i.is_unique AS varchar(1)), i.type_desc, i.filter_definition, c.name,
                   CAST(ic.is_descending_key AS varchar(1)), CAST(ic.is_included_column AS varchar(1))
            FROM sys.indexes i
            JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
            JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
            WHERE i.object_id = OBJECT_ID(?) AND i.type > 0 AND i.is_hypothetical = 0
            ORDER BY i.index_id, ic.is_included_column, ic.key_ordinal, ic.index_column_id
            """.trimIndent(),
            id,
        )
        val indexes = indexRows.groupBy { it[0].orEmpty() }
        val after = mutableListOf<String>()
        indexes.forEach { (indexName, rows) ->
            val first = rows.first()
            val kind = first[4].orEmpty()
            val keys = rows.filter { it[8] != "1" }.joinToString(", ") { q(it[6].orEmpty()) + if (it[7] == "1") " DESC" else " ASC" }
            val included = rows.filter { it[8] == "1" }.joinToString(", ") { q(it[6].orEmpty()) }
            val clustered = if (kind.startsWith("CLUSTERED")) "CLUSTERED" else "NONCLUSTERED"
            when {
                first[1] == "1" -> lines += "    CONSTRAINT ${q(indexName)} PRIMARY KEY $clustered ($keys)"
                first[2] == "1" -> lines += "    CONSTRAINT ${q(indexName)} UNIQUE $clustered ($keys)"
                kind.contains("COLUMNSTORE") -> {
                    val all = rows.joinToString(", ") { q(it[6].orEmpty()) }
                    after += if (kind.startsWith("CLUSTERED")) {
                        "CREATE CLUSTERED COLUMNSTORE INDEX ${q(indexName)} ON $id;\nGO"
                    } else {
                        "CREATE NONCLUSTERED COLUMNSTORE INDEX ${q(indexName)} ON $id ($all);\nGO"
                    }
                }
                kind == "CLUSTERED" || kind == "NONCLUSTERED" -> {
                    val unique = if (first[3] == "1") "UNIQUE " else ""
                    val include = if (included.isEmpty()) "" else " INCLUDE ($included)"
                    val where = first[5]?.let { " WHERE $it" }.orEmpty()
                    after += "CREATE ${unique}$kind INDEX ${q(indexName)} ON $id ($keys)$include$where;\nGO"
                }
                else -> after += "-- ${tr("跳过 {0} 索引 {1}", kind, q(indexName))}"
            }
        }
        connection.strings(
            "SELECT name, definition FROM sys.check_constraints WHERE parent_object_id = OBJECT_ID(?) ORDER BY name",
            id,
        ).forEach { row -> lines += "    CONSTRAINT ${q(row[0].orEmpty())} CHECK ${row[1].orEmpty()}" }

        val parts = mutableListOf("CREATE TABLE $id (\n${lines.joinToString(",\n")}\n);\nGO")
        parts += after
        if (withForeignKeys) parts += foreignKeys(schema, name)
        connection.strings(
            "SELECT m.definition FROM sys.triggers tr JOIN sys.sql_modules m ON m.object_id = tr.object_id " +
                "WHERE tr.parent_id = OBJECT_ID(?) ORDER BY tr.name",
            id,
        ).forEach { row -> row[0]?.let { parts += it.trim() + "\nGO" } }
        return parts.joinToString("\n\n")
    }

    private fun foreignKeys(schema: String, name: String): List<String> {
        val id = objectName(schema, name)
        val rows = connection.strings(
            """
            SELECT fk.name, fk.delete_referential_action_desc, fk.update_referential_action_desc,
                   pc.name, SCHEMA_NAME(rt.schema_id), rt.name, rc.name
            FROM sys.foreign_keys fk
            JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
            JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
            JOIN sys.tables rt ON rt.object_id = fkc.referenced_object_id
            JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
            WHERE fk.parent_object_id = OBJECT_ID(?)
            ORDER BY fk.name, fkc.constraint_column_id
            """.trimIndent(),
            id,
        )
        return rows.groupBy { it[0].orEmpty() }.map { (fkName, cols) ->
            val first = cols.first()
            val local = cols.joinToString(", ") { q(it[3].orEmpty()) }
            val remote = cols.joinToString(", ") { q(it[6].orEmpty()) }
            fun action(verb: String, desc: String?) =
                if (desc == null || desc == "NO_ACTION") "" else " ON $verb ${desc.replace('_', ' ')}"
            "ALTER TABLE $id ADD CONSTRAINT ${q(fkName)} FOREIGN KEY ($local) " +
                "REFERENCES ${objectName(first[4].orEmpty(), first[5].orEmpty())} ($remote)" +
                action("DELETE", first[1]) + action("UPDATE", first[2]) + ";\nGO"
        }
    }

    private fun script(schema: String?): String {
        val out = StringBuilder()
        val database = runCatching { connection.catalog }.getOrNull()
        if (!database.isNullOrBlank()) out.append("USE ${q(database)};\nGO\n")
        val filter = if (schema == null) "" else " AND s.name = ?"
        val binds = if (schema == null) emptyArray() else arrayOf(schema)
        step(tr("列出表"))
        val tables = connection.strings(
            "SELECT s.name, t.name FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id " +
                "WHERE t.is_ms_shipped = 0$filter ORDER BY s.name, t.name",
            *binds,
        )
        if (schema != null && schema !in setOf("dbo", "sys", "guest", "INFORMATION_SCHEMA")) {
            out.append("\nIF SCHEMA_ID(N'${schema.replace("'", "''")}') IS NULL EXEC(N'CREATE SCHEMA ${q(schema).replace("'", "''")}');\nGO\n")
        }
        if (tables.isNotEmpty()) {
            out.append(section(tr("{0}（{1}）", trc("ddl", "表"), tables.size)))
            tables.forEachIndexed { index, row ->
                step("${trc("ddl", "表")} $index/${tables.size}")
                val s = row[0].orEmpty()
                val n = row[1].orEmpty()
                out.append(runCatching { table(s, n, withForeignKeys = false) }
                    .getOrElse { "-- ${tr("无法导出表 {0}：{1}", "$s.$n", it.message)}" }).append("\n\n")
            }
            val foreign = tables.flatMapIndexed { index, row ->
                step("${tr("外键")} $index/${tables.size}")
                runCatching { foreignKeys(row[0].orEmpty(), row[1].orEmpty()) }.getOrElse { emptyList() }
            }
            if (foreign.isNotEmpty()) {
                out.append(section(tr("{0}（{1}）", tr("外键"), foreign.size)))
                foreign.forEach { out.append(it).append("\n\n") }
            }
        }
        step(tr("读取视图、函数与存储过程"))
        val modules = connection.strings(
            "SELECT s.name, o.name, o.type, m.definition FROM sys.sql_modules m " +
                "JOIN sys.objects o ON o.object_id = m.object_id JOIN sys.schemas s ON s.schema_id = o.schema_id " +
                "WHERE o.is_ms_shipped = 0 AND o.type IN ('V', 'P', 'FN', 'IF', 'TF')$filter " +
                "ORDER BY CASE o.type WHEN 'V' THEN 1 WHEN 'FN' THEN 2 WHEN 'IF' THEN 2 WHEN 'TF' THEN 2 ELSE 3 END, s.name, o.name",
            *binds,
        )
        val titles = mapOf("V" to trc("ddl", "视图"), "FN" to tr("函数"), "IF" to tr("函数"), "TF" to tr("函数"), "P" to tr("存储过程"))
        modules.groupBy { titles[it[2]?.trim()] ?: tr("其他") }.forEach { (title, rows) ->
            out.append(section(tr("{0}（{1}）", title, rows.size)))
            rows.forEach { row ->
                val definition = row[3]
                if (definition.isNullOrBlank()) {
                    out.append("-- ${tr("无法读取 {0}.{1} 的定义", row[0], row[1])}\n\n")
                } else {
                    out.append(definition.trim()).append("\nGO\n\n")
                }
            }
        }
        return out.toString().trim()
    }
}

// ---------------------------------------------------------------- PostgreSQL

/**
 * PostgreSQL has no SHOW CREATE TABLE, so tables are rebuilt from pg_catalog; views, functions,
 * indexes, constraints and triggers come from the server's own pg_get_*def functions.
 */
private class PostgresDdl(private val connection: Connection, private val step: (String) -> Unit) {
    fun generate(node: MetaNode): String {
        val schema = node.schema ?: connection.plain("SELECT current_schema()").firstOrNull()?.firstOrNull()
        return when (node.kind) {
            NodeKind.Table -> {
                val name = requireName(node.relation, tr("缺少表名"))
                val oid = oidOf(requireName(schema, tr("缺少 Schema")), name)
                (listOf(table(oid, schema!!, name, withIndexes = true)) + foreignKeys(oid, schema, name))
                    .filter { it.isNotBlank() }.joinToString("\n\n")
            }
            NodeKind.View -> view(requireName(schema, tr("缺少 Schema")), requireName(node.relation, tr("缺少视图名")))
            NodeKind.Schema, NodeKind.Catalog -> schema(requireName(schema ?: node.label, tr("缺少 Schema")))
            else -> error(tr("选择一个表、视图或数据库"))
        }
    }

    private fun q(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    private fun literal(text: String) = "'" + text.replace("'", "''") + "'"

    private fun oidOf(schema: String, name: String): String =
        connection.strings(
            "SELECT c.oid::text FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = ? AND c.relname = ?",
            schema, name,
        ).firstOrNull()?.firstOrNull() ?: error(tr("找不到表 {0}，或者没有权限修改它", name))

    private fun table(oid: String, schema: String, name: String, withIndexes: Boolean): String {
        step(tr("读取表 {0}.{1}", schema, name))
        val full = q(schema) + "." + q(name)
        fun columnsQuery(modern: Boolean) =
            "SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull::text, pg_get_expr(d.adbin, d.adrelid), " +
                (if (modern) "a.attidentity::text, a.attgenerated::text, " else "'', '', ") +
                "col_description(a.attrelid, a.attnum) " +
                "FROM pg_catalog.pg_attribute a LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum " +
                "WHERE a.attrelid = CAST(? AS oid) AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum"
        val columns = runCatching { connection.strings(columnsQuery(true), oid) }
            .getOrElse { connection.strings(columnsQuery(false), oid) }
        val lines = columns.map { row ->
            buildString {
                append("    ").append(q(row[0]!!)).append(' ').append(row[1])
                val default = row[3]
                when {
                    row[5] == "s" && default != null -> append(" GENERATED ALWAYS AS (").append(default).append(") STORED")
                    row[4] == "a" -> append(" GENERATED ALWAYS AS IDENTITY")
                    row[4] == "d" -> append(" GENERATED BY DEFAULT AS IDENTITY")
                    default != null -> append(" DEFAULT ").append(default)
                }
                if (row[2] == "true") append(" NOT NULL")
            }
        }.toMutableList()
        connection.strings(
            "SELECT conname, pg_get_constraintdef(oid, true) FROM pg_catalog.pg_constraint " +
                "WHERE conrelid = CAST(? AS oid) AND contype IN ('p', 'u', 'c', 'x') ORDER BY contype DESC, conname",
            oid,
        ).forEach { row -> lines += "    CONSTRAINT ${q(row[0]!!)} ${row[1]}" }
        val partition = runCatching {
            connection.strings("SELECT pg_get_partkeydef(CAST(? AS oid))", oid).firstOrNull()?.firstOrNull()
        }.getOrNull()
        val out = StringBuilder("CREATE TABLE $full (\n").append(lines.joinToString(",\n")).append("\n)")
        if (!partition.isNullOrBlank()) out.append(" PARTITION BY ").append(partition)
        out.append(";")
        val tableComment = connection.strings("SELECT obj_description(CAST(? AS oid), 'pg_class')", oid).firstOrNull()?.firstOrNull()
        if (!tableComment.isNullOrBlank()) out.append("\n\nCOMMENT ON TABLE $full IS ${literal(tableComment)};")
        columns.filter { !it[6].isNullOrBlank() }.forEach { row ->
            out.append("\nCOMMENT ON COLUMN $full.${q(row[0]!!)} IS ${literal(row[6]!!)};")
        }
        if (withIndexes) {
            val extras = indexes(oid) + triggers(oid)
            if (extras.isNotEmpty()) out.append("\n\n").append(extras.joinToString("\n"))
        }
        return out.toString()
    }

    /** Indexes that don't belong to a constraint (those are already in CREATE TABLE). */
    private fun indexes(oid: String): List<String> = connection.strings(
        "SELECT pg_get_indexdef(i.indexrelid) FROM pg_catalog.pg_index i WHERE i.indrelid = CAST(? AS oid) " +
            "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint c WHERE c.conindid = i.indexrelid) ORDER BY i.indexrelid",
        oid,
    ).mapNotNull { it[0]?.let { def -> "$def;" } }

    private fun triggers(oid: String): List<String> = connection.strings(
        "SELECT pg_get_triggerdef(t.oid, true) FROM pg_catalog.pg_trigger t WHERE t.tgrelid = CAST(? AS oid) " +
            "AND NOT t.tgisinternal ORDER BY t.tgname",
        oid,
    ).mapNotNull { it[0]?.let { def -> "$def;" } }

    private fun foreignKeys(oid: String, schema: String, name: String): List<String> = connection.strings(
        "SELECT conname, pg_get_constraintdef(oid, true) FROM pg_catalog.pg_constraint " +
            "WHERE conrelid = CAST(? AS oid) AND contype = 'f' ORDER BY conname",
        oid,
    ).map { row -> "ALTER TABLE ${q(schema)}.${q(name)} ADD CONSTRAINT ${q(row[0]!!)} ${row[1]};" }

    private fun view(schema: String, name: String): String {
        step(tr("读取视图 {0}.{1}", schema, name))
        val row = connection.strings(
            "SELECT c.relkind::text, pg_get_viewdef(c.oid, true), c.oid::text FROM pg_catalog.pg_class c " +
                "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?",
            schema, name,
        ).firstOrNull() ?: error(tr("找不到视图 {0}", name))
        val body = row[1].orEmpty().trim().removeSuffix(";")
        val full = q(schema) + "." + q(name)
        val head = if (row[0] == "m") "CREATE MATERIALIZED VIEW $full AS\n$body;" else "CREATE OR REPLACE VIEW $full AS\n$body;"
        val comment = connection.strings("SELECT obj_description(CAST(? AS oid), 'pg_class')", row[2]!!).firstOrNull()?.firstOrNull()
        val extras = if (row[0] == "m") indexes(row[2]!!) else triggers(row[2]!!)
        return listOfNotNull(
            head,
            comment?.takeIf { it.isNotBlank() }?.let { "COMMENT ON ${if (row[0] == "m") "MATERIALIZED VIEW" else "VIEW"} $full IS ${literal(it)};" },
            extras.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        ).joinToString("\n\n")
    }

    private fun schema(schema: String): String {
        step(tr("读取数据库 {0}", schema))
        val out = StringBuilder("CREATE SCHEMA IF NOT EXISTS ${q(schema)};\nSET search_path TO ${q(schema)};\n")
        // Sequences behind serial columns and free-standing ones; identity sequences come with their column.
        val sequences = connection.strings(
            "SELECT c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = ? AND c.relkind = 'S' AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d " +
                "WHERE d.objid = c.oid AND d.deptype = 'i') ORDER BY c.relname",
            schema,
        ).mapNotNull { it[0] }
        if (sequences.isNotEmpty()) {
            out.append(section(tr("{0}（{1}）", tr("序列"), sequences.size)))
            sequences.forEach { out.append("CREATE SEQUENCE IF NOT EXISTS ${q(schema)}.${q(it)};\n") }
        }
        val relations = connection.strings(
            "SELECT c.relname, c.relkind::text, c.oid::text FROM pg_catalog.pg_class c " +
                "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'v', 'm') ORDER BY c.oid",
            schema,
        )
        val tables = relations.filter { it[1] == "r" || it[1] == "p" }.sortedBy { it[0] }
        val views = relations.filter { it[1] == "v" || it[1] == "m" }
        val foreign = mutableListOf<String>()
        if (tables.isNotEmpty()) {
            out.append(section(tr("{0}（{1}）", trc("ddl", "表"), tables.size)))
            tables.forEachIndexed { index, row ->
                step("${trc("ddl", "表")} $index/${tables.size}")
                out.append(runCatching { table(row[2]!!, schema, row[0]!!, withIndexes = true) }
                    .getOrElse { "-- ${tr("无法导出表 {0}：{1}", row[0], it.message)}" }).append("\n\n")
                foreign += runCatching { foreignKeys(row[2]!!, schema, row[0]!!) }.getOrDefault(emptyList())
            }
        }
        if (foreign.isNotEmpty()) out.append(foreign.joinToString("\n")).append("\n")
        if (views.isNotEmpty()) {
            out.append(section(tr("{0}（{1}）", trc("ddl", "视图"), views.size)))
            views.forEachIndexed { index, row ->
                step("${trc("ddl", "视图")} $index/${views.size}")
                out.append(runCatching { view(schema, row[0]!!) }.getOrElse { "-- ${tr("无法导出视图 {0}：{1}", row[0], it.message)}" })
                    .append("\n\n")
            }
        }
        // Aggregates and functions owned by extensions are left out.
        val functions = runCatching {
            connection.strings(
                "SELECT p.oid::text, p.proname FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace " +
                    "WHERE n.nspname = ? AND p.prokind IN ('f', 'p') AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d " +
                    "WHERE d.objid = p.oid AND d.deptype = 'e') ORDER BY p.proname",
                schema,
            )
        }.getOrElse {
            // prokind arrived in PostgreSQL 11.
            connection.strings(
                "SELECT p.oid::text, p.proname FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace " +
                    "WHERE n.nspname = ? AND NOT p.proisagg AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d " +
                    "WHERE d.objid = p.oid AND d.deptype = 'e') ORDER BY p.proname",
                schema,
            )
        }
        if (functions.isNotEmpty()) {
            out.append(section(tr("{0}（{1}）", tr("函数与存储过程"), functions.size)))
            functions.forEach { row ->
                step(tr("读取 {0}.{1}", schema, row[1]))
                val def = runCatching {
                    connection.strings("SELECT pg_get_functiondef(CAST(? AS oid))", row[0]!!).firstOrNull()?.firstOrNull()
                }
                out.append(
                    def.getOrNull()?.trim()?.let { "$it;" }
                        ?: "-- ${tr("无法导出 {0}：{1}", row[1], def.exceptionOrNull()?.message)}",
                ).append("\n\n")
            }
        }
        return out.toString()
    }
}

// ---------------------------------------------------------------- SQLite

/** SQLite keeps every object's original CREATE statement in sqlite_master. */
private class SqliteDdl(private val connection: Connection, private val step: (String) -> Unit) {
    fun generate(node: MetaNode): String = when (node.kind) {
        NodeKind.Table, NodeKind.View -> relation(requireName(node.relation, tr("缺少表名")))
        NodeKind.Session, NodeKind.Schema, NodeKind.Catalog -> database()
        else -> error(tr("选择一个表、视图或数据库"))
    }

    private fun relation(name: String): String {
        step(tr("读取表 {0}.{1}", "main", name))
        val own = connection.strings(
            "SELECT sql FROM sqlite_master WHERE name = ? COLLATE NOCASE AND sql IS NOT NULL",
            name,
        ).mapNotNull { it[0] }
        if (own.isEmpty()) error(tr("找不到表 {0}，或者没有权限修改它", name))
        val dependents = connection.strings(
            "SELECT sql FROM sqlite_master WHERE tbl_name = ? COLLATE NOCASE AND type IN ('index', 'trigger') " +
                "AND sql IS NOT NULL ORDER BY type, name",
            name,
        ).mapNotNull { it[0] }
        return (own + dependents).joinToString("\n\n") { it.trim().removeSuffix(";") + ";" }
    }

    private fun database(): String {
        val out = StringBuilder("PRAGMA foreign_keys = OFF;\n")
        val groups = listOf("table" to trc("ddl", "表"), "view" to trc("ddl", "视图"), "index" to tr("索引"), "trigger" to tr("触发器"))
        groups.forEach { (type, title) ->
            val rows = connection.strings(
                "SELECT name, sql FROM sqlite_master WHERE type = ? AND sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY rowid",
                type,
            )
            if (rows.isEmpty()) return@forEach
            out.append(section(tr("{0}（{1}）", title, rows.size)))
            rows.forEach { row ->
                step("$title ${row[0]}")
                out.append(row[1]!!.trim().removeSuffix(";")).append(";\n\n")
            }
        }
        out.append("PRAGMA foreign_keys = ON;\n")
        return out.toString()
    }
}
