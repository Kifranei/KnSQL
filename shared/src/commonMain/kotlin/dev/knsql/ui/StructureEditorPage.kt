package dev.knsql.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.knsql.data.StructureEditorState
import dev.knsql.data.Workbench
import dev.knsql.data.applyStructureChange
import dev.knsql.data.closeStructureEditor
import dev.knsql.data.proposeAdd
import dev.knsql.data.proposeAlter
import dev.knsql.data.proposeDrop
import dev.knsql.data.proposeTableComment
import dev.knsql.data.reloadStructure
import dev.knsql.i18n.tr
import dev.knsql.model.ColumnInfo
import dev.knsql.model.ColumnSpec
import dev.knsql.model.Engine
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** What the column dialog is editing: a new column, or an existing one. */
private sealed interface ColumnTarget {
    data object New : ColumnTarget
    data class Existing(val column: ColumnInfo) : ColumnTarget
}

@Composable
fun StructureEditorPage(workbench: Workbench, state: StructureEditorState) {
    var target by remember { mutableStateOf<ColumnTarget?>(null) }
    var editingComment by remember { mutableStateOf(false) }
    val structure = state.structure
    val cardColors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)

    AboutBackHandler(enabled = true, onBack = workbench::closeStructureEditor)

    Column(Modifier.fillMaxSize().padding(bottom = LocalBottomBarInset.current)) {
        SmallTopAppBar(
            color = MiuixTheme.colorScheme.background,
            title = tr("{0} 结构", state.table.name),
            subtitle = when {
                state.loading -> tr("正在读取表结构")
                structure == null -> tr("读取失败")
                else -> tr("{0} 列 · 点列修改", structure.columns.size)
            },
            defaultWindowInsetsPadding = true,
            navigationIcon = {
                IconButton(onClick = workbench::closeStructureEditor) { Icon(MiuixIcons.Back, contentDescription = tr("返回")) }
            },
            actions = {
                if (state.loading) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                if (structure != null) {
                    IconButton(onClick = { target = ColumnTarget.New }) { Icon(MiuixIcons.Add, contentDescription = tr("新增列")) }
                    IconButton(onClick = { workbench.reloadStructure(state) }) { Icon(MiuixIcons.Refresh, contentDescription = tr("刷新")) }
                }
            },
        )
        when {
            state.error != null -> ErrorBox(workbench, state.error.orEmpty())
            structure == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { SmallTitle(text = tr("表")) }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp), colors = cardColors) {
                        BasicComponent(
                            title = tr("表注释"),
                            summary = structure.comment ?: tr("未设置"),
                            onClick = { editingComment = true },
                        )
                    }
                }
                item { SmallTitle(text = tr("列")) }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp), colors = cardColors) {
                        structure.columns.forEach { column ->
                            BasicComponent(
                                title = column.name + if (column.primaryKey) " · " + tr("主键") else "",
                                summary = columnSummary(column),
                                onClick = { target = ColumnTarget.Existing(column) },
                                endActions = {
                                    IconButton(onClick = { state.proposeDrop(column.name) }) {
                                        Icon(MiuixIcons.Delete, contentDescription = tr("删除列 {0}", column.name))
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    Text(
                        text = tr("修改会先显示要执行的 DDL。表结构修改由数据库立即提交，无法回滚，生产库上请先确认影响。"),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
        }
    }

    val editing = target
    if (structure != null && editing != null) {
        ColumnDialog(
            engine = structure.engine,
            existing = (editing as? ColumnTarget.Existing)?.column,
            onDismiss = { target = null },
            onSubmit = { spec ->
                when (editing) {
                    ColumnTarget.New -> state.proposeAdd(spec)
                    is ColumnTarget.Existing -> if (!state.proposeAlter(editing.column, spec)) workbench.report(tr("没有修改"))
                }
                target = null
            },
        )
    }
    if (structure != null && editingComment) {
        var text by remember { mutableStateOf(structure.comment.orEmpty()) }
        OverlayDialog(
            show = true,
            title = tr("表注释"),
            onDismissRequest = { editingComment = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = tr("注释"))
                DialogButtons(
                    cancel = tr("取消"),
                    confirm = tr("预览"),
                    onCancel = { editingComment = false },
                    onConfirm = {
                        editingComment = false
                        if (text.trim() == structure.comment.orEmpty()) {
                            workbench.report(tr("没有修改"))
                        } else {
                            state.proposeTableComment(text.trim())
                        }
                    },
                )
            }
        }
    }
    val pending = state.pending
    SqlConfirmDialog(
        show = pending != null,
        title = pending?.title.orEmpty(),
        summary = tr("以下 DDL 会立即生效，数据库无法回滚表结构修改。"),
        statements = pending?.statements.orEmpty(),
        confirm = tr("执行"),
        working = state.working,
        destructive = pending?.destructive == true,
        onDismiss = { state.pending = null },
        onConfirm = { workbench.applyStructureChange(state) },
    )
}

private fun columnSummary(column: ColumnInfo): String = listOfNotNull(
    column.type,
    if (column.nullable) tr("可为空") else tr("不能为空"),
    column.defaultValue?.let { tr("默认 {0}", it) },
    if (column.autoIncrement) tr("自增") else null,
    if (column.generated) tr("计算列") else null,
    column.comment,
).joinToString(" · ")

@Composable
private fun ColumnDialog(
    engine: Engine,
    existing: ColumnInfo?,
    onDismiss: () -> Unit,
    onSubmit: (ColumnSpec) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type.orEmpty()) }
    var nullable by remember { mutableStateOf(existing?.nullable ?: true) }
    var default by remember { mutableStateOf(existing?.defaultValue.orEmpty()) }
    var comment by remember { mutableStateOf(existing?.comment.orEmpty()) }
    val typeHint = when (engine) {
        Engine.Oracle -> "VARCHAR2(50 CHAR) · NUMBER(10,2) · DATE"
        Engine.MySql -> "varchar(50) · int · decimal(10,2) · datetime"
        Engine.SqlServer -> "nvarchar(50) · int · decimal(10,2) · datetime2(0)"
        Engine.Dameng -> "VARCHAR(50) · INT · DECIMAL(10,2) · TIMESTAMP"
    }
    OverlayDialog(
        show = true,
        title = if (existing == null) tr("新增列") else tr("修改列 {0}", existing.name),
        summary = tr("默认值写 SQL 表达式，例如 0、'abc' 或 SYSDATE。"),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = tr("列名"), singleLine = true)
            TextField(
                value = type,
                onValueChange = { type = it },
                modifier = Modifier.fillMaxWidth(),
                label = tr("类型"),
                singleLine = true,
                textStyle = MiuixTheme.textStyles.main.copy(fontFamily = FontFamily.Monospace),
            )
            Text(typeHint, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote2)
            TextField(
                value = default,
                onValueChange = { default = it },
                modifier = Modifier.fillMaxWidth(),
                label = tr("默认值"),
                singleLine = true,
                textStyle = MiuixTheme.textStyles.main.copy(fontFamily = FontFamily.Monospace),
            )
            TextField(value = comment, onValueChange = { comment = it }, modifier = Modifier.fillMaxWidth(), label = tr("注释"))
            CheckRow(tr("允许 NULL"), nullable, { nullable = it })
            DialogButtons(
                cancel = tr("取消"),
                confirm = tr("预览"),
                onCancel = onDismiss,
                enabled = name.isNotBlank() && type.isNotBlank(),
                onConfirm = {
                    onSubmit(
                        ColumnSpec(
                            name = name.trim(),
                            type = type.trim(),
                            nullable = nullable,
                            defaultValue = default.trim().ifEmpty { null },
                            comment = comment.trim().ifEmpty { null },
                        ),
                    )
                },
            )
        }
    }
}
