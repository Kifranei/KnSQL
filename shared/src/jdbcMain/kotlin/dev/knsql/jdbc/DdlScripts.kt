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
    Engine.MySql -> MySqlDdl(connection, step).generate(node)
    Engine.SqlServer -> SqlServerDdl(connection, step).generate(node)
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
