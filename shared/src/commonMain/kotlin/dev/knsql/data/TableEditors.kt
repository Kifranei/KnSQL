package dev.knsql.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.knsql.i18n.tr
import dev.knsql.model.BoundStatement
import dev.knsql.model.ColumnInfo
import dev.knsql.model.ColumnSpec
import dev.knsql.model.EditableData
import dev.knsql.model.Engine
import dev.knsql.model.NodeKind
import dev.knsql.model.TableRef
import dev.knsql.model.TableStructure
import dev.knsql.sql.PendingChanges
import dev.knsql.sql.addColumnDdl
import dev.knsql.sql.alterColumnDdl
import dev.knsql.sql.buildDataChanges
import dev.knsql.sql.dropColumnDdl
import dev.knsql.sql.tableCommentDdl

/** Where an edited cell lives: a loaded row, or a row being inserted. */
sealed interface RowTarget {
    data class Existing(val index: Int) : RowTarget
    data class Inserted(val index: Int) : RowTarget
}

class DataEditorState(val handle: String, val table: TableRef) {
    var data by mutableStateOf<EditableData?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var changes by mutableStateOf(PendingChanges())
    var selected by mutableStateOf(emptySet<Int>())
    var preview by mutableStateOf<List<BoundStatement>?>(null)
    var working by mutableStateOf(false)
}

data class PendingDdl(val title: String, val statements: List<String>, val destructive: Boolean = false)

class StructureEditorState(val handle: String, val table: TableRef) {
    var structure by mutableStateOf<TableStructure?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    /** DDL waiting for confirmation. */
    var pending by mutableStateOf<PendingDdl?>(null)
    var working by mutableStateOf(false)
}

/** The table a tree node points at, in the shape each engine's loader expects. */
fun TreeNode.tableRef(engine: Engine?): TableRef? {
    val relation = meta.relation ?: return null
    if (meta.kind != NodeKind.Table) return null
    return when (engine) {
        Engine.Oracle -> TableRef(null, meta.schema, relation)
        Engine.MySql -> TableRef(meta.catalog ?: meta.schema, null, relation)
        else -> TableRef(meta.catalog, meta.schema, relation)
    }
}

private fun Throwable.firstLines(): String =
    message?.lineSequence()?.filter { it.isNotBlank() }?.take(3)?.joinToString("\n") ?: describeFailure()

private fun Workbench.editableSession(handle: String): Boolean {
    if (sessionReadOnly(handle)) {
        report(tr("这个连接设为只读，不能修改数据或表结构"))
        return false
    }
    return true
}

// ---------------------------------------------------------------- data editor

fun Workbench.openDataEditor(node: TreeNode) {
    val table = node.tableRef(sessionEngine(node.sessionHandle)) ?: return
    openDataEditor(node.sessionHandle, table)
}

fun Workbench.openDataEditor(handle: String, table: TableRef) {
    if (!editableSession(handle)) return
    structureEditor = null
    val state = DataEditorState(handle, table)
    dataEditor = state
    reloadData(state)
}

fun Workbench.reloadData(state: DataEditorState) {
    state.loading = true
    state.error = null
    val limit = if (settings.unlimitedRows) 0 else settings.maxRows
    launchOnConnection(state.handle, onError = {
        state.error = it.describeFailure()
        state.loading = false
    }) {
        val structure = client.tableStructure(state.handle, state.table)
        state.data = client.loadEditable(state.handle, structure, limit)
        state.changes = PendingChanges()
        state.selected = emptySet()
        state.loading = false
    }
}

fun Workbench.closeDataEditor() {
    dataEditor = null
}

/** The value a cell shows, taking pending edits into account. */
fun DataEditorState.cellValue(target: RowTarget, column: Int): String? = when (target) {
    is RowTarget.Existing -> {
        val edits = changes.edits[target.index]
        if (edits != null && column in edits) edits[column] else data?.rows?.getOrNull(target.index)?.values?.getOrNull(column)
    }
    is RowTarget.Inserted -> changes.inserts.getOrNull(target.index)?.get(column)
}

fun DataEditorState.isEdited(target: RowTarget, column: Int): Boolean = when (target) {
    is RowTarget.Existing -> changes.edits[target.index]?.containsKey(column) == true
    is RowTarget.Inserted -> changes.inserts.getOrNull(target.index)?.containsKey(column) == true
}

/** Sets a cell. [value] null = SQL NULL. For inserted rows, [omit] leaves the column out so its default applies. */
fun DataEditorState.setCell(target: RowTarget, column: Int, value: String?, omit: Boolean = false) {
    when (target) {
        is RowTarget.Existing -> {
            val original = data?.rows?.getOrNull(target.index)?.values?.getOrNull(column)
            val row = changes.edits[target.index].orEmpty().toMutableMap()
            if (value == original) row.remove(column) else row[column] = value
            val edits = changes.edits.toMutableMap()
            if (row.isEmpty()) edits.remove(target.index) else edits[target.index] = row
            changes = changes.copy(edits = edits)
        }
        is RowTarget.Inserted -> {
            val inserts = changes.inserts.toMutableList()
            val row = inserts.getOrNull(target.index)?.toMutableMap() ?: return
            if (omit) row.remove(column) else row[column] = value
            inserts[target.index] = row
            changes = changes.copy(inserts = inserts)
        }
    }
}

fun DataEditorState.addRow() {
    changes = changes.copy(inserts = changes.inserts + emptyMap())
}

fun DataEditorState.removeInserted(index: Int) {
    changes = changes.copy(inserts = changes.inserts.filterIndexed { i, _ -> i != index })
}

fun DataEditorState.toggleSelected(index: Int) {
    selected = if (index in selected) selected - index else selected + index
}

/** Marks the selected loaded rows for deletion (or un-marks them if they all already are). */
fun DataEditorState.deleteSelected() {
    if (selected.isEmpty()) return
    val allMarked = selected.all { it in changes.deleted }
    changes = changes.copy(deleted = if (allMarked) changes.deleted - selected else changes.deleted + selected)
    selected = emptySet()
}

fun DataEditorState.discardChanges() {
    changes = PendingChanges()
    selected = emptySet()
}

fun Workbench.previewDataChanges(state: DataEditorState) {
    val data = state.data ?: return
    if (state.changes.isEmpty) return
    state.preview = runCatching { buildDataChanges(data, state.changes) }
        .onFailure { report(it.message ?: tr("无法生成修改语句")) }
        .getOrNull()
}

fun Workbench.commitDataChanges(state: DataEditorState) {
    val statements = state.preview ?: return
    state.working = true
    launchOnConnection(state.handle, onError = {
        state.working = false
        state.preview = null
        state.error = null
        report(tr("提交失败，已回滚：{0}", it.firstLines()))
    }) {
        val affected = client.applyChanges(state.handle, statements)
        state.working = false
        state.preview = null
        report(tr("已提交 {0} 条语句，影响 {1} 行", statements.size, affected))
        val limit = if (settings.unlimitedRows) 0 else settings.maxRows
        val structure = state.data?.structure ?: client.tableStructure(state.handle, state.table)
        state.data = client.loadEditable(state.handle, structure, limit)
        state.changes = PendingChanges()
        state.selected = emptySet()
    }
}

// ---------------------------------------------------------------- structure editor

fun Workbench.openStructureEditor(node: TreeNode) {
    val table = node.tableRef(sessionEngine(node.sessionHandle)) ?: return
    openStructureEditor(node.sessionHandle, table)
}

fun Workbench.openStructureEditor(handle: String, table: TableRef) {
    if (!editableSession(handle)) return
    dataEditor = null
    val state = StructureEditorState(handle, table)
    structureEditor = state
    reloadStructure(state)
}

fun Workbench.reloadStructure(state: StructureEditorState) {
    state.loading = true
    state.error = null
    launchOnConnection(state.handle, onError = {
        state.error = it.describeFailure()
        state.loading = false
    }) {
        state.structure = client.tableStructure(state.handle, state.table)
        state.loading = false
    }
}

fun Workbench.closeStructureEditor() {
    structureEditor = null
}

fun StructureEditorState.proposeAdd(spec: ColumnSpec) {
    val structure = structure ?: return
    pending = PendingDdl(tr("新增列 {0}", spec.name), addColumnDdl(structure, spec))
}

fun StructureEditorState.proposeAlter(old: ColumnInfo, spec: ColumnSpec): Boolean {
    val structure = structure ?: return false
    val ddl = alterColumnDdl(structure, old, spec)
    if (ddl.isEmpty()) return false
    pending = PendingDdl(tr("修改列 {0}", old.name), ddl, destructive = spec.type.trim() != old.type.trim())
    return true
}

fun StructureEditorState.proposeDrop(column: String) {
    val structure = structure ?: return
    pending = PendingDdl(tr("删除列 {0}", column), dropColumnDdl(structure, column), destructive = true)
}

fun StructureEditorState.proposeTableComment(comment: String?) {
    val structure = structure ?: return
    pending = PendingDdl(tr("修改表注释"), tableCommentDdl(structure, comment))
}

fun Workbench.applyStructureChange(state: StructureEditorState) {
    val structure = state.structure ?: return
    val pending = state.pending ?: return
    val title = pending.title
    val ddl = pending.statements
    state.working = true
    launchOnConnection(state.handle, onError = {
        state.working = false
        state.pending = null
        report(tr("{0}失败：{1}", title, it.firstLines()))
        reloadStructure(state)
    }) {
        client.alterTable(state.handle, structure, ddl)
        state.working = false
        state.pending = null
        report(tr("{0}完成", title))
        state.structure = client.tableStructure(state.handle, structure.table)
    }
}
