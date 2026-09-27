package dev.knsql.model

/** Broad value kinds, enough to bind edited text with the right JDBC setter. */
enum class ValueKind { Text, Number, DateTime, Date, Time, Boolean, Lob, Binary }

/** A column as the structure editor and the data editor see it. */
data class ColumnInfo(
    val name: String,
    /** Full SQL type as it would appear in DDL, e.g. `VARCHAR2(50 CHAR)`, `decimal(10,2)`, `[nvarchar](40)`. */
    val type: String,
    val kind: ValueKind,
    val nullable: Boolean,
    /** Default as a SQL expression, or null when there is none. */
    val defaultValue: String?,
    val comment: String?,
    val primaryKey: Boolean = false,
    val autoIncrement: Boolean = false,
    /** MySQL-only attributes that must survive a column redefinition, e.g. `on update CURRENT_TIMESTAMP`. */
    val extra: String? = null,
    /** Computed / generated columns can't be written. */
    val generated: Boolean = false,
) {
    val writable: Boolean get() = !generated && kind != ValueKind.Binary
}

/** A table resolved to the parts each engine needs (owner, database, schema). */
data class TableStructure(
    val engine: Engine,
    val table: TableRef,
    val columns: List<ColumnInfo>,
    val comment: String?,
) {
    val keyColumns: List<String> get() = columns.filter { it.primaryKey }.map { it.name }
}

/** One row loaded for editing. [key] identifies it: the Oracle ROWID, or the primary key values. */
data class EditableRow(val key: List<String?>, val values: List<String?>)

data class EditableData(
    val structure: TableStructure,
    /** Names of the key parts, `ROWID` for Oracle. Empty when rows can't be identified. */
    val keyNames: List<String>,
    val rows: List<EditableRow>,
    val truncated: Boolean,
) {
    val rowsIdentifiable: Boolean get() = keyNames.isNotEmpty()
}

/** A value to bind: null means SQL NULL. */
data class BindValue(val text: String?, val kind: ValueKind)

/** A parameterised statement plus a readable rendering for the confirmation preview. */
data class BoundStatement(
    val sql: String,
    val binds: List<BindValue>,
    val preview: String,
    /** For UPDATE / DELETE: the statement must touch exactly one row, or the batch rolls back. */
    val expectSingleRow: Boolean,
)

/** Column definition typed into the structure editor. */
data class ColumnSpec(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val defaultValue: String?,
    val comment: String?,
)
