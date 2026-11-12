package dev.knsql.sql

import dev.knsql.model.BindValue
import dev.knsql.model.BoundStatement
import dev.knsql.model.ColumnInfo
import dev.knsql.model.ColumnSpec
import dev.knsql.model.EditableData
import dev.knsql.model.Engine
import dev.knsql.model.TableRef
import dev.knsql.model.TableStructure
import dev.knsql.model.ValueKind

// ---------------------------------------------------------------- quoting

fun quoteIdentifier(engine: Engine, name: String): String = when (engine) {
    Engine.Oracle, Engine.Dameng -> "\"" + name.replace("\"", "\"\"") + "\""
    Engine.MySql -> "`" + name.replace("`", "``") + "`"
    Engine.SqlServer -> "[" + name.replace("]", "]]") + "]"
}

/** Fully qualified table name. The structure must already be resolved (owner / database / schema set). */
fun qualifiedTable(engine: Engine, table: TableRef): String {
    val parts = when (engine) {
        Engine.Oracle, Engine.Dameng -> listOfNotNull(table.schema, table.name)
        Engine.MySql -> listOfNotNull(table.catalog ?: table.schema, table.name)
        Engine.SqlServer -> listOfNotNull(table.catalog, table.schema ?: "dbo", table.name)
    }
    return parts.joinToString(".") { quoteIdentifier(engine, it) }
}

fun stringLiteral(engine: Engine, text: String): String = when (engine) {
    Engine.Oracle, Engine.Dameng -> "'" + text.replace("'", "''") + "'"
    Engine.MySql -> "'" + text.replace("\\", "\\\\").replace("'", "''") + "'"
    Engine.SqlServer -> "N'" + text.replace("'", "''") + "'"
}

/** How a bound value reads in the preview. Execution always binds, so this is for people only. */
fun previewLiteral(engine: Engine, value: BindValue): String {
    val text = value.text ?: return "NULL"
    return when (value.kind) {
        ValueKind.Number -> if (text.trim().toDoubleOrNull() != null) text.trim() else stringLiteral(engine, text)
        ValueKind.Boolean -> text.trim()
        ValueKind.DateTime -> if (engine.oracleLike) "TIMESTAMP " + stringLiteral(engine, text.trim()) else stringLiteral(engine, text.trim())
        ValueKind.Date -> if (engine.oracleLike) "DATE " + stringLiteral(engine, text.trim().take(10)) else stringLiteral(engine, text.trim())
        else -> stringLiteral(engine, text)
    }
}

// ---------------------------------------------------------------- data changes

/**
 * Pending edits of an [EditableData] grid.
 * [edits] maps row index → column index → new value (null = SQL NULL).
 * [inserts] holds new rows; a column missing from the map is left out so its default applies.
 */
data class PendingChanges(
    val edits: Map<Int, Map<Int, String?>> = emptyMap(),
    val deleted: Set<Int> = emptySet(),
    val inserts: List<Map<Int, String?>> = emptyList(),
) {
    val count: Int get() = edits.filterKeys { it !in deleted }.size + deleted.size + inserts.size
    val isEmpty: Boolean get() = count == 0
}

/** Statements for [changes], deletes first, then updates, then inserts. */
fun buildDataChanges(data: EditableData, changes: PendingChanges): List<BoundStatement> {
    val structure = data.structure
    val engine = structure.engine
    val table = qualifiedTable(engine, structure.table)
    val columns = structure.columns
    val statements = mutableListOf<BoundStatement>()

    fun keyClause(rowIndex: Int): Pair<String, List<BindValue>> {
        val row = data.rows[rowIndex]
        if (engine == Engine.Oracle) {
            return "ROWID = CHARTOROWID(?)" to listOf(BindValue(row.key.firstOrNull(), ValueKind.Text))
        }
        val parts = data.keyNames.mapIndexed { index, name ->
            val column = columns.first { it.name == name }
            quoteIdentifier(engine, name) to BindValue(row.key.getOrNull(index), column.kind)
        }
        return parts.joinToString(" AND ") { "${it.first} = ?" } to parts.map { it.second }
    }

    fun render(sql: String, binds: List<BindValue>): String {
        var index = 0
        return buildString {
            sql.forEach { c ->
                if (c == '?' && index < binds.size) append(previewLiteral(engine, binds[index++])) else append(c)
            }
        }
    }

    changes.deleted.sorted().forEach { rowIndex ->
        require(data.rowsIdentifiable) { "rows can't be identified" }
        val (where, binds) = keyClause(rowIndex)
        val sql = "DELETE FROM $table WHERE $where"
        statements += BoundStatement(sql, binds, render(sql, binds), expectSingleRow = true)
    }
    changes.edits.toSortedMap().forEach { (rowIndex, cells) ->
        if (rowIndex in changes.deleted || cells.isEmpty()) return@forEach
        require(data.rowsIdentifiable) { "rows can't be identified" }
        val ordered = cells.toSortedMap()
        val set = ordered.keys.joinToString(", ") { "${quoteIdentifier(engine, columns[it].name)} = ?" }
        val values = ordered.map { (column, value) -> BindValue(value, columns[column].kind) }
        val (where, keyBinds) = keyClause(rowIndex)
        val sql = "UPDATE $table SET $set WHERE $where"
        val binds = values + keyBinds
        statements += BoundStatement(sql, binds, render(sql, binds), expectSingleRow = true)
    }
    changes.inserts.forEach { row ->
        val ordered = row.toSortedMap()
        val sql = if (ordered.isEmpty()) {
            if (engine == Engine.MySql) "INSERT INTO $table () VALUES ()" else "INSERT INTO $table DEFAULT VALUES"
        } else {
            "INSERT INTO $table (" + ordered.keys.joinToString(", ") { quoteIdentifier(engine, columns[it].name) } +
                ") VALUES (" + ordered.keys.joinToString(", ") { "?" } + ")"
        }
        val binds = ordered.map { (column, value) -> BindValue(value, columns[column].kind) }
        statements += BoundStatement(sql, binds, render(sql, binds), expectSingleRow = false)
    }
    return statements
}

// ---------------------------------------------------------------- structure changes

private fun defaultClause(expression: String?): String =
    expression?.trim()?.takeIf { it.isNotEmpty() }?.let { " DEFAULT $it" }.orEmpty()

private fun sqlServerTableKey(table: TableRef): Pair<String, String> = (table.schema ?: "dbo") to table.name

private fun sqlServerObject(table: TableRef): String {
    val (schema, name) = sqlServerTableKey(table)
    return quoteIdentifier(Engine.SqlServer, schema) + "." + quoteIdentifier(Engine.SqlServer, name)
}

/** Drops whatever default constraint SQL Server has on [column], if any. */
private fun sqlServerDropDefault(table: TableRef, column: String): String {
    val objectName = stringLiteral(Engine.SqlServer, sqlServerObject(table))
    return "DECLARE @df sysname = (SELECT dc.name FROM sys.default_constraints dc " +
        "JOIN sys.columns c ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id " +
        "WHERE dc.parent_object_id = OBJECT_ID($objectName) AND c.name = ${stringLiteral(Engine.SqlServer, column)});\n" +
        "IF @df IS NOT NULL EXEC(N'ALTER TABLE ${sqlServerObject(table).replace("'", "''")} DROP CONSTRAINT [' + @df + N']');"
}

/** Adds, replaces or removes the MS_Description extended property of the table or one of its columns. */
private fun sqlServerComment(table: TableRef, column: String?, comment: String?): String {
    val (schema, name) = sqlServerTableKey(table)
    val e = Engine.SqlServer
    val objectId = "OBJECT_ID(${stringLiteral(e, sqlServerObject(table))})"
    val minor = if (column == null) "0" else "COLUMNPROPERTY($objectId, ${stringLiteral(e, column)}, 'ColumnId')"
    val target = "N'SCHEMA', ${stringLiteral(e, schema)}, N'TABLE', ${stringLiteral(e, name)}" +
        (column?.let { ", N'COLUMN', ${stringLiteral(e, it)}" } ?: "")
    val exists = "EXISTS (SELECT 1 FROM sys.extended_properties WHERE class = 1 AND major_id = $objectId " +
        "AND minor_id = $minor AND name = N'MS_Description')"
    val text = comment?.takeIf { it.isNotEmpty() }
        ?: return "IF $exists EXEC sp_dropextendedproperty N'MS_Description', $target;"
    val value = stringLiteral(e, text)
    return "IF $exists EXEC sp_updateextendedproperty N'MS_Description', $value, $target;\n" +
        "ELSE EXEC sp_addextendedproperty N'MS_Description', $value, $target;"
}

private fun mysqlColumnDefinition(spec: ColumnSpec, old: ColumnInfo?): String = buildString {
    val e = Engine.MySql
    append(quoteIdentifier(e, spec.name)).append(' ').append(spec.type.trim())
    append(if (spec.nullable) " NULL" else " NOT NULL")
    append(defaultClause(spec.defaultValue))
    if (old?.autoIncrement == true) append(" AUTO_INCREMENT")
    old?.extra?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
    spec.comment?.takeIf { it.isNotEmpty() }?.let { append(" COMMENT ").append(stringLiteral(e, it)) }
}

fun addColumnDdl(structure: TableStructure, spec: ColumnSpec): List<String> {
    val engine = structure.engine
    val table = qualifiedTable(engine, structure.table)
    val name = quoteIdentifier(engine, spec.name)
    return when (engine) {
        Engine.Oracle, Engine.Dameng -> listOfNotNull(
            "ALTER TABLE $table ADD ($name ${spec.type.trim()}${defaultClause(spec.defaultValue)}" +
                (if (spec.nullable) "" else " NOT NULL") + ")",
            spec.comment?.takeIf { it.isNotEmpty() }?.let { "COMMENT ON COLUMN $table.$name IS ${stringLiteral(engine, it)}" },
        )
        Engine.MySql -> listOf("ALTER TABLE $table ADD COLUMN ${mysqlColumnDefinition(spec, null)}")
        Engine.SqlServer -> {
            val default = spec.defaultValue?.trim()?.takeIf { it.isNotEmpty() }?.let {
                " CONSTRAINT " + quoteIdentifier(engine, "DF_${structure.table.name}_${spec.name}") + " DEFAULT $it"
            }.orEmpty()
            listOfNotNull(
                "ALTER TABLE ${sqlServerObject(structure.table)} ADD $name ${spec.type.trim()}" +
                    (if (spec.nullable) " NULL" else " NOT NULL") + default,
                spec.comment?.takeIf { it.isNotEmpty() }?.let { sqlServerComment(structure.table, spec.name, it) },
            )
        }
    }
}

fun alterColumnDdl(structure: TableStructure, old: ColumnInfo, spec: ColumnSpec): List<String> {
    val engine = structure.engine
    val table = qualifiedTable(engine, structure.table)
    val renamed = spec.name != old.name
    val typeChanged = !spec.type.trim().equals(old.type.trim(), ignoreCase = true)
    val nullChanged = spec.nullable != old.nullable
    val newDefault = spec.defaultValue?.trim()?.takeIf { it.isNotEmpty() }
    val defaultChanged = newDefault != old.defaultValue?.trim()?.takeIf { it.isNotEmpty() }
    val newComment = spec.comment?.takeIf { it.isNotEmpty() }
    val commentChanged = newComment != old.comment?.takeIf { it.isNotEmpty() }
    val name = quoteIdentifier(engine, spec.name)
    val out = mutableListOf<String>()
    when (engine) {
        Engine.Oracle, Engine.Dameng -> {
            if (renamed) out += "ALTER TABLE $table RENAME COLUMN ${quoteIdentifier(engine, old.name)} TO $name"
            if (typeChanged || nullChanged || defaultChanged) {
                val parts = buildString {
                    append(name)
                    if (typeChanged) append(' ').append(spec.type.trim())
                    if (defaultChanged) append(" DEFAULT ").append(newDefault ?: "NULL")
                    if (nullChanged) append(if (spec.nullable) " NULL" else " NOT NULL")
                }
                out += "ALTER TABLE $table MODIFY ($parts)"
            }
            if (commentChanged) out += "COMMENT ON COLUMN $table.$name IS ${stringLiteral(engine, newComment.orEmpty())}"
        }
        Engine.MySql -> {
            if (renamed || typeChanged || nullChanged || defaultChanged || commentChanged) {
                out += "ALTER TABLE $table CHANGE COLUMN ${quoteIdentifier(engine, old.name)} ${mysqlColumnDefinition(spec, old)}"
            }
        }
        Engine.SqlServer -> {
            val objectName = sqlServerObject(structure.table)
            if (renamed) {
                val path = stringLiteral(engine, "${structure.table.schema ?: "dbo"}.${structure.table.name}.${old.name}")
                out += "EXEC sp_rename $path, ${stringLiteral(engine, spec.name)}, N'COLUMN'"
            }
            if (typeChanged || nullChanged) {
                out += "ALTER TABLE $objectName ALTER COLUMN $name ${spec.type.trim()}" + if (spec.nullable) " NULL" else " NOT NULL"
            }
            if (defaultChanged) {
                out += sqlServerDropDefault(structure.table, spec.name)
                if (newDefault != null) {
                    out += "ALTER TABLE $objectName ADD CONSTRAINT " +
                        quoteIdentifier(engine, "DF_${structure.table.name}_${spec.name}") + " DEFAULT $newDefault FOR $name"
                }
            }
            if (commentChanged) out += sqlServerComment(structure.table, spec.name, newComment)
        }
    }
    return out
}

fun dropColumnDdl(structure: TableStructure, column: String): List<String> {
    val engine = structure.engine
    val table = qualifiedTable(engine, structure.table)
    val name = quoteIdentifier(engine, column)
    return when (engine) {
        Engine.Oracle, Engine.Dameng, Engine.MySql -> listOf("ALTER TABLE $table DROP COLUMN $name")
        Engine.SqlServer -> listOf(
            sqlServerDropDefault(structure.table, column),
            "ALTER TABLE ${sqlServerObject(structure.table)} DROP COLUMN $name",
        )
    }
}

fun tableCommentDdl(structure: TableStructure, comment: String?): List<String> {
    val engine = structure.engine
    val table = qualifiedTable(engine, structure.table)
    val text = comment?.takeIf { it.isNotEmpty() }
    return when (engine) {
        Engine.Oracle, Engine.Dameng -> listOf("COMMENT ON TABLE $table IS ${stringLiteral(engine, text.orEmpty())}")
        Engine.MySql -> listOf("ALTER TABLE $table COMMENT = ${stringLiteral(engine, text.orEmpty())}")
        Engine.SqlServer -> listOf(sqlServerComment(structure.table, null, text))
    }
}
