package dev.knsql.model

import kotlinx.serialization.Serializable

@Serializable
enum class Engine {
    Oracle,
    MySql,
    SqlServer,
    ;

    val label: String
        get() = when (this) {
            Oracle -> "Oracle"
            MySql -> "MySQL"
            SqlServer -> "SQL Server"
        }

    val defaultPort: Int
        get() = when (this) {
            Oracle -> 1521
            MySql -> 3306
            SqlServer -> 1433
        }
}

@Serializable
data class SavedConnection(
    val id: String,
    val name: String,
    val engine: Engine,
    val host: String,
    val port: Int,
    val database: String = "",
    val oracleSid: Boolean = false,
    val username: String = "",
    val passwordCipher: String = "",
    val sysdba: Boolean = false,
    val encrypt: Boolean = false,
    val readOnly: Boolean = false,
)

@Serializable
data class AppSettings(
    val colorMode: Int = 0,
    val maxRows: Int = 200,
    val queryTimeoutSec: Int = 30,
    val confirmWrites: Boolean = true,
    val keepAlive: Boolean = true,
    val unlimitedRows: Boolean = false,
    val showColumnComments: Boolean = false,
    val glassBottomBar: Boolean = false,
)

@Serializable
data class HistoryEntry(
    val id: String,
    val connectionName: String,
    val sql: String,
    val success: Boolean,
    val message: String,
    val elapsedMs: Long,
    val atEpochMs: Long,
)

enum class NodeKind {
    Session,
    Catalog,
    Schema,
    Table,
    View,
    Column,
}

data class MetaNode(
    val label: String,
    val kind: NodeKind,
    val catalog: String? = null,
    val schema: String? = null,
    val relation: String? = null,
    val relationType: String? = null,
    val expandable: Boolean = true,
    val comment: String? = null,
)

/** A table as written in a query; any part may be null when the query relied on the session default. */
data class TableRef(val catalog: String?, val schema: String?, val name: String)

data class OpenedSession(
    val handle: String,
    val serverInfo: String,
    val children: List<MetaNode>,
)

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val updateCount: Int,
    val elapsedMs: Long,
    val truncated: Boolean,
    /** Where the columns come from, when the result reads a single table. */
    val source: TableRef? = null,
    /** Column comments, aligned with [columns]; null until looked up. */
    val columnComments: List<String?>? = null,
)

data class ConnectionDraft(
    val id: String? = null,
    val name: String = "",
    val engine: Engine = Engine.Oracle,
    val host: String = "",
    val port: String = Engine.Oracle.defaultPort.toString(),
    val database: String = "",
    val oracleSid: Boolean = false,
    val username: String = "",
    val password: String = "",
    val sysdba: Boolean = false,
    val encrypt: Boolean = false,
    val readOnly: Boolean = false,
    val keepStoredPassword: Boolean = false,
)

fun SavedConnection.toDraft(password: String): ConnectionDraft = ConnectionDraft(
    id = id,
    name = name,
    engine = engine,
    host = host,
    port = port.toString(),
    database = database,
    oracleSid = oracleSid,
    username = username,
    password = password,
    sysdba = sysdba,
    encrypt = encrypt,
    readOnly = readOnly,
    keepStoredPassword = passwordCipher.isNotEmpty(),
)

fun quoteIdent(engine: Engine, name: String): String = when (engine) {
    Engine.MySql -> "`${name.replace("`", "``")}`"
    Engine.SqlServer -> "[${name.replace("]", "]]")}]"
    Engine.Oracle -> "\"${name.replace("\"", "\"\"")}\""
}

fun qualifiedName(engine: Engine, catalog: String?, schema: String?, relation: String): String {
    val parts = when (engine) {
        Engine.MySql -> listOfNotNull(catalog?.takeIf { it.isNotBlank() }, relation)
        Engine.SqlServer -> listOfNotNull(
            catalog?.takeIf { it.isNotBlank() },
            schema?.takeIf { it.isNotBlank() },
            relation,
        )
        Engine.Oracle -> listOfNotNull(schema?.takeIf { it.isNotBlank() }, relation)
    }
    return parts.joinToString(".") { quoteIdent(engine, it) }
}

fun previewSql(engine: Engine, catalog: String?, schema: String?, relation: String): String {
    val name = qualifiedName(engine, catalog, schema, relation)
    return when (engine) {
        Engine.MySql -> "SELECT * FROM $name LIMIT 200"
        Engine.SqlServer -> "SELECT TOP 200 * FROM $name"
        Engine.Oracle -> "SELECT * FROM $name FETCH FIRST 200 ROWS ONLY"
    }
}
