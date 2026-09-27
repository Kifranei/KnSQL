package dev.knsql.data

import dev.knsql.model.ConnectionDraft
import dev.knsql.model.MetaNode
import dev.knsql.model.OpenedSession
import dev.knsql.model.QueryResult
import dev.knsql.model.BoundStatement
import dev.knsql.model.EditableData
import dev.knsql.model.TableRef
import dev.knsql.model.TableStructure

interface DatabaseClient {
    suspend fun test(draft: ConnectionDraft): String
    suspend fun open(draft: ConnectionDraft): OpenedSession
    suspend fun children(handle: String, parent: MetaNode): List<MetaNode>
    suspend fun execute(handle: String, sql: String, maxRows: Int, timeoutSec: Int): List<QueryResult>

    /** DDL for a table or view, or the whole script for a schema / database node. */
    suspend fun ddl(handle: String, node: MetaNode, onProgress: (String) -> Unit): String
    /** Runs a trivial query so idle links through VPNs and firewalls stay open; throws when the link is dead. */
    suspend fun ping(handle: String)

    /** Replaces the connection behind [handle] with a fresh one, keeping the handle and its object tree. */
    suspend fun reconnect(handle: String, draft: ConnectionDraft)

    /** Column comments of [table], keyed by upper-cased column name. */
    suspend fun columnComments(handle: String, table: TableRef): Map<String, String>

    /** Columns, keys and comments of [table], with its owner / database / schema resolved. */
    suspend fun tableStructure(handle: String, table: TableRef): TableStructure

    /** Rows of [structure] plus what identifies each of them (ROWID or primary key). [maxRows] 0 = all. */
    suspend fun loadEditable(handle: String, structure: TableStructure, maxRows: Int): EditableData

    /** Runs data changes in one transaction; returns the rows affected. */
    suspend fun applyChanges(handle: String, statements: List<BoundStatement>): Int

    /** Runs DDL against [structure]'s table, one statement at a time. */
    suspend fun alterTable(handle: String, structure: TableStructure, statements: List<String>)

    fun cancel(handle: String)
    fun close(handle: String)
}

expect fun databaseClient(): DatabaseClient
