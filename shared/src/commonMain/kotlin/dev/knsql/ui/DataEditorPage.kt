package dev.knsql.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.knsql.data.DataEditorState
import dev.knsql.data.RowTarget
import dev.knsql.data.Workbench
import dev.knsql.data.addRow
import dev.knsql.data.cellValue
import dev.knsql.data.closeDataEditor
import dev.knsql.data.commitDataChanges
import dev.knsql.data.deleteSelected
import dev.knsql.data.discardChanges
import dev.knsql.data.isEdited
import dev.knsql.data.previewDataChanges
import dev.knsql.data.reloadData
import dev.knsql.data.removeInserted
import dev.knsql.data.setCell
import dev.knsql.data.toggleSelected
import dev.knsql.i18n.tr
import dev.knsql.model.ColumnInfo
import dev.knsql.model.EditableData
import dev.knsql.model.ValueKind
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
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

private val selectWidth = 48.dp

@Composable
fun DataEditorPage(workbench: Workbench, state: DataEditorState) {
    var editing by remember { mutableStateOf<Pair<RowTarget, Int>?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val data = state.data
    val changes = state.changes

    fun leave() {
        if (changes.isEmpty) workbench.closeDataEditor() else confirmLeave = true
    }
    AboutBackHandler(enabled = true, onBack = ::leave)

    Column(Modifier.fillMaxSize().padding(bottom = LocalBottomBarInset.current)) {
        SmallTopAppBar(
            color = MiuixTheme.colorScheme.background,
            title = state.table.name,
            subtitle = when {
                state.loading -> tr("正在读取数据")
                data == null -> tr("读取失败")
                changes.isEmpty -> tr("{0} 行 · 点单元格修改，长按行选择", data.rows.size)
                else -> tr("{0} 行 · {1} 项修改待提交", data.rows.size, changes.count)
            },
            defaultWindowInsetsPadding = true,
            navigationIcon = {
                IconButton(onClick = ::leave) { Icon(MiuixIcons.Back, contentDescription = tr("返回")) }
            },
            actions = {
                if (state.loading || state.working) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                if (data != null) {
                    IconButton(onClick = state::addRow) { Icon(MiuixIcons.Add, contentDescription = tr("新增行")) }
                    IconButton(onClick = state::deleteSelected, enabled = state.selected.isNotEmpty()) {
                        Icon(MiuixIcons.Delete, contentDescription = tr("删除所选行"))
                    }
                    IconButton(onClick = { workbench.reloadData(state) }, enabled = changes.isEmpty && !state.loading) {
                        Icon(MiuixIcons.Refresh, contentDescription = tr("刷新"))
                    }
                }
            },
        )
        when {
            state.error != null -> ErrorBox(workbench, state.error.orEmpty())
            data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val notes = buildList {
                    if (!data.rowsIdentifiable) add(tr("这个表没有主键，只能新增行，不能修改或删除已有行。"))
                    if (data.truncated) add(tr("只载入了前 {0} 行，可以在设置里调整或关闭行数限制。", data.rows.size))
                }
                notes.forEach { note ->
                    Text(
                        text = note,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
                EditGrid(
                    state = state,
                    data = data,
                    modifier = Modifier.weight(1f),
                    onEdit = { target, column -> editing = target to column },
                )
                if (!changes.isEmpty) {
                    Row(Modifier.fillMaxWidth().padding(12.dp)) {
                        DialogButtons(
                            cancel = tr("放弃修改"),
                            confirm = tr("预览并提交（{0}）", changes.count),
                            onCancel = state::discardChanges,
                            onConfirm = { workbench.previewDataChanges(state) },
                        )
                    }
                }
            }
        }
    }

    val target = editing
    if (data != null && target != null) {
        CellDialog(
            column = data.structure.columns[target.second],
            inserting = target.first is RowTarget.Inserted,
            initial = state.cellValue(target.first, target.second),
            initiallyOmitted = target.first is RowTarget.Inserted && !state.isEdited(target.first, target.second),
            onDismiss = { editing = null },
            onSave = { value, omit ->
                state.setCell(target.first, target.second, value, omit)
                editing = null
            },
        )
    }
    SqlConfirmDialog(
        show = state.preview != null,
        title = tr("确认提交"),
        summary = tr("以下 {0} 条语句会在一个事务里执行，任何一条失败或影响的行数不对都会全部回滚。", state.preview?.size ?: 0),
        statements = state.preview.orEmpty().map { it.preview },
        confirm = tr("提交"),
        working = state.working,
        destructive = changes.deleted.isNotEmpty(),
        onDismiss = { state.preview = null },
        onConfirm = { workbench.commitDataChanges(state) },
    )
    OverlayDialog(
        show = confirmLeave,
        title = tr("放弃未提交的修改？"),
        summary = tr("还有 {0} 项修改没有提交，离开后会丢失。", changes.count),
        onDismissRequest = { confirmLeave = false },
    ) {
        DialogButtons(
            cancel = tr("继续编辑"),
            confirm = tr("放弃"),
            onCancel = { confirmLeave = false },
            onConfirm = {
                confirmLeave = false
                workbench.closeDataEditor()
            },
            destructive = true,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditGrid(
    state: DataEditorState,
    data: EditableData,
    modifier: Modifier,
    onEdit: (RowTarget, Int) -> Unit,
) {
    val columns = data.structure.columns
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val headerStyle = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold)
    val cellStyle = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)
    val widths = remember(data, headerStyle, cellStyle) {
        fun measure(text: String, style: TextStyle) =
            measurer.measure(text.take(48), style, maxLines = 1, softWrap = false).size.width
        columns.mapIndexed { index, column ->
            var widest = maxOf(measure(column.name + if (column.primaryKey) "  PK" else "", headerStyle), measure(column.type, cellStyle))
            data.rows.asSequence().take(120).forEach { row ->
                widest = maxOf(widest, measure(row.values.getOrNull(index)?.lineSequence()?.firstOrNull() ?: "NULL", cellStyle))
            }
            with(density) { (widest.toDp() + 24.dp).coerceIn(80.dp, 320.dp) }
        }
    }
    val total = widths.fold(selectWidth) { sum, width -> sum + width }
    val editedColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)
    val insertedColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)
    val deletedColor = MiuixTheme.colorScheme.error.copy(alpha = 0.12f)
    val selectedColor = MiuixTheme.colorScheme.secondaryContainer
    val canEditExisting = data.rowsIdentifiable
    val targets = remember(data.rows.size, state.changes.inserts.size) {
        data.rows.indices.map { RowTarget.Existing(it) } + state.changes.inserts.indices.map { RowTarget.Inserted(it) }
    }
    Column(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(Modifier.width(total).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
            Box(Modifier.width(selectWidth))
            columns.forEachIndexed { index, column ->
                Column(Modifier.width(widths[index]).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = column.name + if (column.primaryKey) "  PK" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = headerStyle,
                    )
                    Text(
                        text = column.type + if (column.nullable) "" else " · NOT NULL",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote2,
                    )
                }
            }
        }
        LazyColumn(Modifier.width(total).weight(1f)) {
            items(targets.size) { position ->
                val target = targets[position]
                val existing = target as? RowTarget.Existing
                val deleted = existing != null && existing.index in state.changes.deleted
                val selected = existing != null && existing.index in state.selected
                val rowColor = when {
                    deleted -> deletedColor
                    selected -> selectedColor
                    target is RowTarget.Inserted -> insertedColor
                    else -> Color.Transparent
                }
                Row(Modifier.background(rowColor), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(selectWidth), contentAlignment = Alignment.Center) {
                        when {
                            existing != null && canEditExisting -> Checkbox(
                                state = ToggleableState(selected),
                                onClick = { state.toggleSelected(existing.index) },
                            )
                            target is RowTarget.Inserted -> IconButton(onClick = { state.removeInserted(target.index) }) {
                                Icon(MiuixIcons.Delete, contentDescription = tr("移除新增行"))
                            }
                        }
                    }
                    columns.forEachIndexed { index, column ->
                        val value = state.cellValue(target, index)
                        val editable = column.writable && !deleted && (target is RowTarget.Inserted || canEditExisting)
                        val omitted = target is RowTarget.Inserted && !state.isEdited(target, index)
                        Text(
                            text = when {
                                omitted -> tr("(默认)")
                                value == null -> "NULL"
                                else -> value.lineSequence().firstOrNull().orEmpty()
                            },
                            modifier = Modifier
                                .width(widths[index])
                                .background(if (state.isEdited(target, index) && target is RowTarget.Existing) editedColor else Color.Transparent)
                                .combinedClickable(
                                    enabled = true,
                                    onClick = { if (editable) onEdit(target, index) },
                                    onLongClick = { if (existing != null && canEditExisting) state.toggleSelected(existing.index) },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (value == null || omitted) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
                            style = if (deleted) cellStyle.copy(textDecoration = TextDecoration.LineThrough) else cellStyle,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CellDialog(
    column: ColumnInfo,
    inserting: Boolean,
    initial: String?,
    initiallyOmitted: Boolean,
    onDismiss: () -> Unit,
    onSave: (value: String?, omit: Boolean) -> Unit,
) {
    var text by remember(column, initial) { mutableStateOf(initial.orEmpty()) }
    var isNull by remember(column, initial) { mutableStateOf(initial == null && !initiallyOmitted) }
    var omit by remember(column, initial) { mutableStateOf(initiallyOmitted) }
    val hint = when (column.kind) {
        ValueKind.DateTime -> tr("格式：2024-01-31 08:30:00")
        ValueKind.Date -> tr("格式：2024-01-31")
        ValueKind.Time -> tr("格式：08:30:00")
        ValueKind.Boolean -> tr("填 1 或 0")
        else -> null
    }
    OverlayDialog(
        show = true,
        title = column.name,
        summary = listOfNotNull(
            column.type,
            if (column.nullable) tr("可为空") else tr("不能为空"),
            column.comment,
            hint,
        ).joinToString(" · "),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    isNull = false
                    omit = false
                },
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                label = tr("值"),
                enabled = !isNull && !omit,
                singleLine = column.kind != ValueKind.Text && column.kind != ValueKind.Lob,
                textStyle = MiuixTheme.textStyles.main.copy(fontFamily = FontFamily.Monospace),
            )
            if (column.nullable) {
                CheckRow(tr("设为 NULL"), isNull, { isNull = it; if (it) omit = false })
            }
            if (inserting) {
                CheckRow(tr("不填写，使用列的默认值"), omit, { omit = it; if (it) isNull = false })
            }
            DialogButtons(
                cancel = tr("取消"),
                confirm = tr("确定"),
                onCancel = onDismiss,
                onConfirm = { onSave(if (isNull) null else text, omit) },
            )
        }
    }
}

@Composable
internal fun ErrorBox(workbench: Workbench, message: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .combinedClickableCompat {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(message))
                workbench.report(tr("已复制错误信息"))
            },
        color = MiuixTheme.colorScheme.error,
        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onLongClick: () -> Unit): Modifier =
    combinedClickable(onClick = {}, onLongClick = onLongClick)
