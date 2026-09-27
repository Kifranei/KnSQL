package dev.knsql.jdbc

import dev.knsql.i18n.tr
import dev.knsql.model.BindValue
import dev.knsql.model.BoundStatement
import dev.knsql.model.ColumnInfo
import dev.knsql.model.EditableData
import dev.knsql.model.EditableRow
import dev.knsql.model.Engine
import dev.knsql.model.TableRef
import dev.knsql.model.TableStructure
import dev.knsql.model.ValueKind
import dev.knsql.sql.qualifiedTable
import dev.knsql.sql.quoteIdentifier
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types

// Everything here queries the data dictionary directly: Oracle's DatabaseMetaData needs APIs that
// Android lacks, and the dictionary also gives exact DDL types, defaults and comments.

private fun Connection.rows(sql: String, vararg binds: String?): List<List<String?>> {
    prepareStatement(sql).use { statement ->
        binds.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { set ->
            val width = set.metaData.columnCount
            val output = mutableListOf<List<String?>>()
            while (set.next()) output += (1..width).map { set.getString(it) }
            return output
        }
    }
}

private fun Connection.single(sql: String): String? = rows(sql).firstOrNull()?.firstOrNull()

internal fun loadStructure(engine: Engine, connection: Connection, table: TableRef): TableStructure = when (engine) {
    Engine.Oracle -> oracleStructure(connection, table)
    Engine.MySql -> mysqlStructure(connection, table)
    Engine.SqlServer -> sqlServerStructure(connection, table)
}

// ---------------------------------------------------------------- Oracle

private fun oracleKind(type: String): ValueKind {
    val t = type.uppercase()
    return when {
        t.startsWith("NUMBER") || t.startsWith("FLOAT") || t.startsWith("BINARY_") || t == "INTEGER" -> ValueKind.Number
        t == "DATE" || t.startsWith("TIMESTAMP") -> ValueKind.DateTime
        t == "CLOB" || t == "NCLOB" || t == "LONG" -> ValueKind.Lob
        t == "BLOB" || t.startsWith("RAW") || t == "LONG RAW" || t == "BFILE" -> ValueKind.Binary
        else -> ValueKind.Text
    }
}

private fun oracleStructure(connection: Connection, table: TableRef): TableStructure {
    val wantedOwner = table.schema ?: connection.single("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL").orEmpty()
    val found = connection.rows(
        "SELECT OWNER, TABLE_NAME FROM ALL_TABLES WHERE OWNER IN (?, UPPER(?)) AND TABLE_NAME IN (?, UPPER(?)) " +
            "ORDER BY CASE WHEN OWNER = ? AND TABLE_NAME = ? THEN 0 ELSE 1 END",
        wantedOwner, wantedOwner, table.name, table.name, wantedOwner, table.name,
    ).firstOrNull() ?: throw SQLException(tr("找不到表 {0}，或者没有权限修改它", table.name))
    val owner = found[0]!!
    val name = found[1]!!
    val keys = connection.rows(
        "SELECT cc.COLUMN_NAME FROM ALL_CONSTRAINTS c JOIN ALL_CONS_COLUMNS cc " +
            "ON cc.OWNER = c.OWNER AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME " +
            "WHERE c.OWNER = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE = 'P'",
        owner, name,
    ).mapNotNull { it[0] }.toSet()
    val columns = mutableListOf<ColumnInfo>()
    connection.prepareStatement(
        // DATA_DEFAULT is a LONG, which Oracle streams: it has to be read last and in column order.
        "SELECT t.COLUMN_NAME, t.DATA_TYPE, t.DATA_LENGTH, t.CHAR_LENGTH, t.CHAR_USED, t.DATA_PRECISION, " +
            "t.DATA_SCALE, t.NULLABLE, c.COMMENTS, t.VIRTUAL_COLUMN, t.IDENTITY_COLUMN, t.DATA_DEFAULT " +
            "FROM ALL_TAB_COLS t LEFT JOIN ALL_COL_COMMENTS c " +
            "ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME AND c.COLUMN_NAME = t.COLUMN_NAME " +
            "WHERE t.OWNER = ? AND t.TABLE_NAME = ? AND t.HIDDEN_COLUMN = 'NO' ORDER BY t.COLUMN_ID",
    ).use { statement ->
        statement.setString(1, owner)
        statement.setString(2, name)
        statement.executeQuery().use { set ->
            while (set.next()) {
                val column = set.getString(1)
                val dataType = set.getString(2).orEmpty()
                val length = set.getInt(3)
                val chars = set.getInt(4)
                val charUsed = set.getString(5)
                val precision = set.getObject(6)?.toString()
                val scale = set.getObject(7)?.toString()
                val nullable = set.getString(8) == "Y"
                val comment = set.getString(9)?.takeIf { it.isNotBlank() }
                val virtual = set.getString(10) == "YES"
                val identity = set.getString(11) == "YES"
                val default = set.getString(12)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("NULL", true) }
                val type = when (dataType) {
                    "VARCHAR2", "CHAR" -> "$dataType($chars ${if (charUsed == "C") "CHAR" else "BYTE"})"
                    "NVARCHAR2", "NCHAR" -> "$dataType($chars)"
                    "RAW" -> "RAW($length)"
                    "NUMBER" -> when {
                        precision == null && scale == null -> "NUMBER"
                        precision == null -> "NUMBER(*,$scale)"
                        scale == null || scale == "0" -> "NUMBER($precision)"
                        else -> "NUMBER($precision,$scale)"
                    }
                    "FLOAT" -> if (precision != null) "FLOAT($precision)" else "FLOAT"
                    else -> dataType
                }
                columns += ColumnInfo(
                    name = column,
                    type = type,
                    kind = oracleKind(dataType),
                    nullable = nullable,
                    defaultValue = if (identity) null else default,
                    comment = comment,
                    primaryKey = column in keys,
                    autoIncrement = identity,
                    generated = virtual,
                )
            }
        }
    }
    val comment = connection.rows(
        "SELECT COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?",
        owner, name,
    ).firstOrNull()?.firstOrNull()?.takeIf { it.isNotBlank() }
    return TableStructure(Engine.Oracle, TableRef(null, owner, name), columns, comment)
}

// ---------------------------------------------------------------- MySQL

private fun mysqlKind(dataType: String): ValueKind = when (dataType.lowercase()) {
    "tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric", "float", "double", "real" -> ValueKind.Number
    "datetime", "timestamp" -> ValueKind.DateTime
    "date" -> ValueKind.Date
    "time" -> ValueKind.Time
    "bit", "bool", "boolean" -> ValueKind.Boolean
    "tinytext", "text", "mediumtext", "longtext", "json" -> ValueKind.Lob
    "binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob", "geometry", "point" -> ValueKind.Binary
    else -> ValueKind.Text
}

/** MySQL reports string defaults unquoted, MariaDB quoted; normalise to a SQL expression. */
private fun mysqlDefault(raw: String?, kind: ValueKind, extra: String, nullable: Boolean): String? {
    val value = raw ?: return null
    if (value.equals("NULL", true) && nullable) return null
    if (value.startsWith("'")) return value
    val expression = "DEFAULT_GENERATED" in extra.uppercase() || value.contains('(') ||
        value.uppercase().startsWith("CURRENT_TIMESTAMP") || value.uppercase() == "NOW()"
    return when {
        expression -> value
        kind == ValueKind.Number || kind == ValueKind.Boolean -> value
        else -> "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"
    }
}

private fun mysqlStructure(connection: Connection, table: TableRef): TableStructure {
    val database = table.catalog ?: table.schema ?: connection.single("SELECT DATABASE()")
        ?: throw SQLException(tr("没有选中数据库，无法确定表 {0} 在哪个库", table.name))
    val info = connection.rows(
        "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
        database, table.name,
    ).firstOrNull() ?: throw SQLException(tr("找不到表 {0}，或者没有权限修改它", table.name))
    val name = info[0]!!
    val columns = connection.rows(
        "SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, COLUMN_KEY, EXTRA " +
            "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
        database, name,
    ).map { row ->
        val extra = row[7].orEmpty()
        val kind = mysqlKind(row[2].orEmpty())
        val nullable = row[3] == "YES"
        val kept = extra.split(Regex("(?i)(auto_increment|default_generated)")).joinToString(" ") { it.trim() }.trim()
        ColumnInfo(
            name = row[0]!!,
            type = row[1].orEmpty(),
            kind = kind,
            nullable = nullable,
            defaultValue = mysqlDefault(row[4], kind, extra, nullable),
            comment = row[5]?.takeIf { it.isNotBlank() },
            primaryKey = row[6] == "PRI",
            autoIncrement = "auto_increment" in extra.lowercase(),
            extra = kept.takeIf { it.isNotEmpty() && !it.contains("GENERATED", true) },
            generated = extra.contains("VIRTUAL GENERATED", true) || extra.contains("STORED GENERATED", true),
        )
    }
    return TableStructure(Engine.MySql, TableRef(database, null, name), columns, info[1]?.takeIf { it.isNotBlank() })
}

// ---------------------------------------------------------------- SQL Server

private fun sqlServerKind(type: String): ValueKind = when (type.lowercase()) {
    "tinyint", "smallint", "int", "bigint", "decimal", "numeric", "float", "real", "money", "smallmoney" -> ValueKind.Number
    "datetime", "datetime2", "smalldatetime", "datetimeoffset" -> ValueKind.DateTime
    "date" -> ValueKind.Date
    "time" -> ValueKind.Time
    "bit" -> ValueKind.Boolean
    "text", "ntext", "xml" -> ValueKind.Lob
    "binary", "varbinary", "image", "timestamp", "rowversion", "geography", "geometry", "hierarchyid" -> ValueKind.Binary
    else -> ValueKind.Text
}

private fun sqlServerType(type: String, maxLength: Int, precision: Int, scale: Int): String = when (type.lowercase()) {
    "varchar", "char", "varbinary", "binary" -> type + if (maxLength == -1) "(max)" else "($maxLength)"
    "nvarchar", "nchar" -> type + if (maxLength == -1) "(max)" else "(${maxLength / 2})"
    "decimal", "numeric" -> "$type($precision,$scale)"
    "datetime2", "time", "datetimeoffset" -> "$type($scale)"
    else -> type
}

private fun sqlServerStructure(connection: Connection, table: TableRef): TableStructure {
    val database = table.catalog ?: connection.single("SELECT DB_NAME()")
    val db = database?.let { quoteIdentifier(Engine.SqlServer, it) + "." }.orEmpty()
    val schema = table.schema ?: connection.single("SELECT SCHEMA_NAME()") ?: "dbo"
    val objectName = db + quoteIdentifier(Engine.SqlServer, schema) + "." + quoteIdentifier(Engine.SqlServer, table.name)
    val found = connection.rows(
        "SELECT o.name FROM ${db}sys.objects o WHERE o.object_id = OBJECT_ID(?) AND o.type = 'U'",
        objectName,
    ).firstOrNull() ?: throw SQLException(tr("找不到表 {0}，或者没有权限修改它", table.name))
    val name = found[0]!!
    val columns = connection.rows(
        """
        SELECT c.name, t.name, CAST(c.max_length AS varchar(12)), CAST(c.precision AS varchar(12)),
               CAST(c.scale AS varchar(12)), CAST(c.is_nullable AS varchar(1)), CAST(c.is_identity AS varchar(1)),
               CAST(c.is_computed AS varchar(1)), dc.definition, CAST(ep.value AS nvarchar(4000)),
               CASE WHEN EXISTS (SELECT 1 FROM ${db}sys.indexes i JOIN ${db}sys.index_columns ic
                   ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                   WHERE i.object_id = c.object_id AND i.is_primary_key = 1 AND ic.column_id = c.column_id) THEN '1' ELSE '0' END
        FROM ${db}sys.columns c
        JOIN ${db}sys.types t ON t.user_type_id = c.user_type_id
        LEFT JOIN ${db}sys.default_constraints dc ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id
        LEFT JOIN ${db}sys.extended_properties ep ON ep.class = 1 AND ep.major_id = c.object_id
            AND ep.minor_id = c.column_id AND ep.name = 'MS_Description'
        WHERE c.object_id = OBJECT_ID(?)
        ORDER BY c.column_id
        """.trimIndent(),
        objectName,
    ).map { row ->
        val type = row[1].orEmpty()
        ColumnInfo(
            name = row[0]!!,
            type = sqlServerType(type, row[2]?.toIntOrNull() ?: 0, row[3]?.toIntOrNull() ?: 0, row[4]?.toIntOrNull() ?: 0),
            kind = sqlServerKind(type),
            nullable = row[5] == "1",
            defaultValue = row[8]?.trim()?.removeSurrounding("(", ")")?.let { unwrapped ->
                // SQL Server stores ((0)) and ('x'); keep one level for readability.
                if (unwrapped.startsWith("(") && unwrapped.endsWith(")")) unwrapped.removeSurrounding("(", ")") else unwrapped
            },
            comment = row[9]?.takeIf { it.isNotBlank() },
            primaryKey = row[10] == "1",
            autoIncrement = row[6] == "1",
            generated = row[7] == "1" || type.equals("timestamp", true),
        )
    }
    val comment = connection.rows(
        "SELECT CAST(value AS nvarchar(4000)) FROM ${db}sys.extended_properties " +
            "WHERE class = 1 AND major_id = OBJECT_ID(?) AND minor_id = 0 AND name = 'MS_Description'",
        objectName,
    ).firstOrNull()?.firstOrNull()?.takeIf { it.isNotBlank() }
    return TableStructure(Engine.SqlServer, TableRef(database, schema, name), columns, comment)
}

// ---------------------------------------------------------------- rows for editing

internal fun loadEditableRows(connection: Connection, structure: TableStructure, maxRows: Int): EditableData {
    val engine = structure.engine
    val table = qualifiedTable(engine, structure.table)
    val columns = structure.columns
    val list = columns.joinToString(", ") { "t." + quoteIdentifier(engine, it.name) }
    val keyNames = if (engine == Engine.Oracle) listOf("ROWID") else structure.keyColumns
    val limit = maxRows > 0
    val sql = when (engine) {
        Engine.Oracle -> "SELECT ROWIDTOCHAR(t.ROWID), $list FROM $table t" + if (limit) " FETCH FIRST ${maxRows + 1} ROWS ONLY" else ""
        Engine.MySql -> "SELECT $list FROM $table t" + if (limit) " LIMIT ${maxRows + 1}" else ""
        Engine.SqlServer -> "SELECT " + (if (limit) "TOP (${maxRows + 1}) " else "") + "$list FROM $table t"
    }
    val offset = if (engine == Engine.Oracle) 2 else 1
    val keyIndexes = keyNames.map { key -> columns.indexOfFirst { it.name == key } }
    val rows = mutableListOf<EditableRow>()
    var truncated = false
    connection.createStatement().use { statement ->
        statement.fetchSize = 200
        statement.executeQuery(sql).use { set ->
            while (set.next()) {
                if (limit && rows.size >= maxRows) {
                    truncated = true
                    break
                }
                val values = columns.indices.map { index -> readCell(set, index + offset, columns[index].kind) }
                val key = if (engine == Engine.Oracle) listOf(set.getString(1)) else keyIndexes.map { values.getOrNull(it) }
                rows += EditableRow(key, values)
            }
        }
    }
    return EditableData(structure, keyNames, rows, truncated)
}

private fun readCell(set: ResultSet, index: Int, kind: ValueKind): String? = when (kind) {
    ValueKind.Binary -> set.getBytes(index)?.let { "<binary ${it.size} bytes>" }
    ValueKind.DateTime -> set.getTimestamp(index)?.toString()?.removeSuffix(".0")
    ValueKind.Number -> set.getBigDecimal(index)?.toPlainString()
    else -> set.getString(index)
}

// ---------------------------------------------------------------- applying changes

/** Runs [statements] in one transaction. Any failure, or a key lookup not hitting exactly one row, rolls back. */
internal fun applyInTransaction(connection: Connection, statements: List<BoundStatement>): Int {
    if (connection.isReadOnly) throw SQLException(tr("这个连接是只读的，不能修改数据"))
    val previous = connection.autoCommit
    connection.autoCommit = false
    try {
        var affected = 0
        statements.forEachIndexed { index, statement ->
            connection.prepareStatement(statement.sql).use { prepared ->
                statement.binds.forEachIndexed { position, value -> bind(prepared, position + 1, value) }
                val count = prepared.executeUpdate()
                if (statement.expectSingleRow && count != 1) {
                    throw SQLException(tr("第 {0} 条语句影响了 {1} 行（应为 1 行），全部修改已回滚", index + 1, count))
                }
                affected += count
            }
        }
        connection.commit()
        return affected
    } catch (error: Throwable) {
        runCatching { connection.rollback() }
        throw error
    } finally {
        runCatching { connection.autoCommit = previous }
    }
}

private fun bind(statement: PreparedStatement, index: Int, value: BindValue) {
    val text = value.text
    if (text == null) {
        val type = when (value.kind) {
            ValueKind.Number -> Types.NUMERIC
            ValueKind.DateTime -> Types.TIMESTAMP
            ValueKind.Date -> Types.DATE
            ValueKind.Time -> Types.TIME
            ValueKind.Boolean -> Types.BIT
            else -> Types.VARCHAR
        }
        statement.setNull(index, type)
        return
    }
    val trimmed = text.trim()
    when (value.kind) {
        ValueKind.Number -> statement.setBigDecimal(index, runCatching { BigDecimal(trimmed) }.getOrElse {
            throw SQLException(tr("“{0}”不是有效的数字", text))
        })
        ValueKind.DateTime -> statement.setTimestamp(index, parseTimestamp(trimmed) ?: throw SQLException(tr("“{0}”不是有效的时间，格式为 2024-01-31 08:30:00", text)))
        ValueKind.Date -> statement.setDate(index, runCatching { java.sql.Date.valueOf(trimmed.take(10)) }.getOrElse {
            throw SQLException(tr("“{0}”不是有效的日期，格式为 2024-01-31", text))
        })
        ValueKind.Time -> statement.setTime(index, runCatching { java.sql.Time.valueOf(trimmed) }.getOrElse {
            throw SQLException(tr("“{0}”不是有效的时间，格式为 08:30:00", text))
        })
        ValueKind.Boolean -> statement.setBoolean(index, trimmed == "1" || trimmed.equals("true", true))
        else -> statement.setString(index, text)
    }
}

private fun parseTimestamp(text: String): Timestamp? {
    val normalized = text.replace('T', ' ').let { if (it.length == 10) "$it 00:00:00" else it }
        .let { if (Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$").matches(it)) "$it:00" else it }
    return runCatching { Timestamp.valueOf(normalized) }.getOrNull()
}

/** DDL runs statement by statement: each is committed by the database on its own. */
internal fun runDdl(engine: Engine, connection: Connection, structure: TableStructure, statements: List<String>) {
    if (connection.isReadOnly) throw SQLException(tr("这个连接是只读的，不能修改表结构"))
    val previous = if (engine == Engine.SqlServer) runCatching { connection.catalog }.getOrNull() else null
    val database = structure.table.catalog
    if (engine == Engine.SqlServer && database != null && !database.equals(previous, true)) connection.catalog = database
    try {
        statements.forEachIndexed { index, sql ->
            try {
                connection.createStatement().use { it.execute(sql) }
            } catch (error: SQLException) {
                val done = if (index == 0) "" else tr("前 {0} 条已经生效。", index)
                throw SQLException(tr("第 {0}/{1} 条失败：{2}", index + 1, statements.size, error.message) + done, error)
            }
        }
    } finally {
        if (engine == Engine.SqlServer && previous != null && database != null && !database.equals(previous, true)) {
            runCatching { connection.catalog = previous }
        }
    }
}
