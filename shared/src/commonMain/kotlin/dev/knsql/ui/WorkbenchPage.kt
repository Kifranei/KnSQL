package dev.knsql.ui

import top.yukonga.miuix.kmp.icon.extended.Edit
import dev.knsql.data.openStructureEditor
import dev.knsql.data.openDataEditor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import dev.knsql.model.QueryResult
import top.yukonga.miuix.kmp.basic.Checkbox
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Download
import dev.knsql.sql.toCsv
import dev.knsql.sql.toTsv
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.knsql.data.TreeNode
import dev.knsql.data.Workbench
import dev.knsql.i18n.tr
import dev.knsql.model.NodeKind
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WorkbenchPage(workbench: Workbench, onOverlay: (ActiveOverlay?) -> Unit) {
    workbench.dataEditor?.let { editor ->
        DataEditorPage(workbench, editor)
        return
    }
    workbench.structureEditor?.let { editor ->
        StructureEditorPage(workbench, editor)
        return
    }
    workbench.ddl?.let { view ->
        DdlPage(workbench, view)
        return
    }
    val live = workbench.sessions.find { it.handle == workbench.activeHandle }
    Column(Modifier.fillMaxSize().padding(bottom = LocalBottomBarInset.current)) {
        SmallTopAppBar(
            color = MiuixTheme.colorScheme.background,
            title = live?.name ?: tr("工作台"),
            subtitle = workbench.status.lineSequence().filter { it.isNotBlank() }.joinToString(" · "),
            defaultWindowInsetsPadding = true,
            actions = {
                if (workbench.busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                if (live != null) {
                    IconButton(onClick = { workbench.closeSession(live.handle) }) {
                        Icon(MiuixIcons.Close, contentDescription = tr("断开 {0}", live.name))
                    }
                }
            },
        )
        if (workbench.sessions.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                workbench.sessions.forEach { session ->
                    SessionChip(
                        name = session.name,
                        active = session.handle == workbench.activeHandle,
                        onFocus = { workbench.focus(session.handle) },
                        onClose = { workbench.closeSession(session.handle) },
                    )
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // The three-pane layout needs height as well as width: a phone in landscape is wide
            // enough but too short for the editor and the grid stacked, so it gets the tabs instead.
            val compact = maxHeight < 420.dp
            if (maxWidth >= 720.dp && maxHeight >= 520.dp) {
                Row(Modifier.fillMaxSize()) {
                    ObjectTree(workbench, Modifier.width(300.dp).fillMaxHeight().background(MiuixTheme.colorScheme.surface))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        EditorPane(workbench, Modifier.weight(0.42f))
                        HorizontalDivider()
                        ResultPane(workbench, onOverlay, Modifier.weight(0.58f))
                    }
                }
            } else {
                var tab by remember { mutableIntStateOf(1) }
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer)
                            .padding(4.dp),
                    ) {
                        listOf(tr("对象"), "SQL", tr("结果")).forEachIndexed { index, label ->
                            val selected = tab == index
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { tab = index }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                    when (tab) {
                        0 -> ObjectTree(workbench, Modifier.weight(1f).fillMaxWidth())
                        1 -> EditorPane(workbench, Modifier.weight(1f).fillMaxWidth(), compact = compact)
                        else -> ResultPane(workbench, onOverlay, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ObjectTree(workbench: Workbench, modifier: Modifier) {
    val nodes = workbench.visibleNodes()
    LazyColumn(modifier) {
        if (nodes.isEmpty()) {
            item {
                Text(
                    text = tr("从「连接」打开一个库，这里会列出目录、表和视图。点表名会预览数据，长按表或库可以查看 DDL。"),
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
        items(nodes, key = { it.id }) { node ->
            TreeRow(
                node = node,
                onToggle = { workbench.toggle(node.id) },
                onOpen = { workbench.openRelation(node) },
                onDdl = { workbench.showDdl(node) },
                onEditData = { workbench.openDataEditor(node) },
                onEditStructure = { workbench.openStructureEditor(node) },
                // A SQLite file has no schema level, so its whole-database DDL hangs off the session row.
                sessionDdl = node.meta.kind == NodeKind.Session &&
                    workbench.connections.find { it.id == node.savedId }?.engine?.fileBased == true,
            )
        }
    }
}

@Composable
private fun SessionChip(name: String, active: Boolean, onFocus: () -> Unit, onClose: () -> Unit) {
    val content = if (active) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onFocus)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            maxLines = 1,
            color = content,
            style = MiuixTheme.textStyles.body2,
        )
        IconButton(onClick = onClose, minWidth = 36.dp, minHeight = 36.dp) {
            Icon(
                MiuixIcons.Close,
                contentDescription = tr("断开 {0}", name),
                tint = content,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    node: TreeNode,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDdl: () -> Unit,
    onEditData: () -> Unit,
    onEditStructure: () -> Unit,
    sessionDdl: Boolean = false,
) {
    val kind = node.meta.kind
    val openable = kind == NodeKind.Table || kind == NodeKind.View
    val container = kind == NodeKind.Schema || kind == NodeKind.Catalog || sessionDdl
    val actions = when {
        kind == NodeKind.Table -> listOf(
            tr("预览数据") to onOpen,
            tr("编辑数据") to onEditData,
            tr("修改表结构") to onEditStructure,
            tr("查看 DDL") to onDdl,
        )
        openable -> listOf(tr("预览数据") to onOpen, tr("查看 DDL") to onDdl)
        container -> listOf((if (node.expanded) tr("折叠") else tr("展开")) to onToggle, tr("导出全部 DDL") to onDdl)
        else -> emptyList()
    }
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (openable) onOpen() else onToggle() },
                    onLongClick = if (actions.isEmpty()) {
                        null
                    } else {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu = true
                        }
                    },
                )
                .padding(start = (12 + node.depth * 16).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.meta.expandable) {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (node.expanded) MiuixIcons.ExpandMore else MiuixIcons.ChevronForward,
                        contentDescription = if (node.expanded) tr("折叠") else tr("展开"),
                    )
                }
            }
            Column {
                Text(node.meta.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body1)
                val comment = node.meta.comment
                if (openable || comment != null) {
                    val kindLabel = when (kind) {
                        NodeKind.View -> tr("视图")
                        NodeKind.Table -> tr("表")
                        else -> null
                    }
                    Text(
                        text = listOfNotNull(kindLabel, comment).joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote2,
                    )
                }
            }
        }
        if (actions.isNotEmpty()) {
            AppActionMenu(show = menu, onDismissRequest = { menu = false }, actions = actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorPane(workbench: Workbench, modifier: Modifier, compact: Boolean = false) {
    val opener = rememberSqlFileOpener()
    val sqlSaver = rememberSqlFileSaver()
    Column(
        modifier
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.isAltPressed && !event.isCtrlPressed && event.key == Key.X -> {
                        workbench.runEditor()
                        true
                    }
                    event.isCtrlPressed && !event.isAltPressed && event.key == Key.Enter -> {
                        workbench.runCurrentStatement()
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Keep the whole TextFieldValue so selections (select all, drag handles) survive recomposition.
        // Rebuild it only when the text was replaced from outside, e.g. opening a file or history entry.
        var field by remember {
            mutableStateOf(TextFieldValue(workbench.editor, TextRange(workbench.editorCursor.coerceIn(0, workbench.editor.length))))
        }
        if (field.text != workbench.editor) {
            field = TextFieldValue(workbench.editor, TextRange(workbench.editorCursor.coerceIn(0, workbench.editor.length)))
        }
        TextField(
            value = field,
            onValueChange = {
                field = it
                workbench.editor = it.text
                workbench.editorCursor = it.selection.start
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = workbench.scriptName ?: "SQL",
            minLines = if (compact) 2 else 6,
            textStyle = MiuixTheme.textStyles.main.copy(fontFamily = FontFamily.Monospace),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { workbench.runEditor() },
                enabled = !workbench.busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("执行脚本")) }
            TextButton(
                text = tr("当前语句"),
                onClick = workbench::runCurrentStatement,
                enabled = !workbench.busy,
            )
            TextButton(
                text = tr("打开文件"),
                onClick = {
                    opener.open(
                        onLoaded = { workbench.openScript(it.name, it.text) },
                        onError = workbench::report,
                    )
                },
                enabled = !workbench.busy,
            )
            TextButton(
                text = tr("保存"),
                onClick = {
                    sqlSaver.save(
                        workbench.scriptName?.let { if (it.endsWith(".sql", true)) it else "$it.sql" } ?: "query.sql",
                        workbench.editor,
                        { name -> workbench.report(tr("已保存 {0}", name)) },
                        workbench::report,
                    )
                },
                enabled = workbench.editor.isNotBlank(),
            )
            TextButton(text = tr("取消"), onClick = workbench::cancel, enabled = workbench.busy)
        }
        if (!compact) {
            Text(
                text = tr("Alt+X 执行整个脚本，Ctrl+Enter 执行光标所在语句。支持 ; 、单独一行的 / 或 GO，以及 DELIMITER。"),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote2,
            )
        }
    }
}

@Composable
private fun ResultPane(workbench: Workbench, onOverlay: (ActiveOverlay?) -> Unit, modifier: Modifier) {
    val results = workbench.results
    val logScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val csvSaver = rememberFileSaver("text/csv")
    Column(modifier) {
        if (workbench.scriptLog.isNotEmpty()) {
            Column(
                Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(logScroll)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                workbench.scriptLog.forEach { line ->
                    Text(line, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        if (results.isEmpty()) {
            Text(
                text = if (workbench.scriptLog.isEmpty()) tr("执行结果会显示在这里。") else tr("这些语句没有返回结果集。"),
                modifier = Modifier.padding(16.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
            return
        }
        if (results.size > 1) {
            TabRow(
                tabs = results.mapIndexed { index, _ -> tr("结果 {0}", index + 1) },
                selectedTabIndex = workbench.resultIndex.coerceIn(0, results.lastIndex),
                onTabSelected = { workbench.resultIndex = it },
            )
        }
        val resultIndex = workbench.resultIndex.coerceIn(0, results.lastIndex)
        val result = results[resultIndex]
        val showComments = workbench.settings.showColumnComments
        LaunchedEffect(showComments, resultIndex, result.source) {
            if (showComments) workbench.ensureColumnComments(resultIndex)
        }
        val summary = when {
            result.columns.isEmpty() -> tr("影响 {0} 行 · {1} ms", result.updateCount, result.elapsedMs)
            result.truncated -> tr("{0} 行已截断 · {1} ms", result.rows.size, result.elapsedMs)
            else -> tr("{0} 行 · {1} ms", result.rows.size, result.elapsedMs)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
            if (result.columns.isNotEmpty()) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { workbench.updateSettings(workbench.settings.copy(showColumnComments = !showComments)) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = ToggleableState(showComments),
                        onClick = { workbench.updateSettings(workbench.settings.copy(showColumnComments = !showComments)) },
                    )
                    Text(
                        text = tr("列注释"),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
                val source = result.source
                val handle = workbench.activeHandle
                if (source != null && handle != null) {
                    IconButton(onClick = { workbench.openDataEditor(handle, source) }) {
                        Icon(MiuixIcons.Edit, contentDescription = tr("编辑这张表的数据"))
                    }
                }
                IconButton(
                    onClick = {
                        val text = result.toTsv()
                        if (text.length > 500_000) {
                            workbench.report(tr("结果太大，剪贴板放不下，请用「导出 CSV」"))
                        } else {
                            clipboard.setText(AnnotatedString(text))
                            workbench.report(tr("已复制 {0} 行，可直接粘贴到表格", result.rows.size))
                        }
                    },
                ) {
                    Icon(MiuixIcons.Copy, contentDescription = tr("复制结果"))
                }
                IconButton(
                    onClick = {
                        csvSaver.save(
                            workbench.resultFileName(),
                            result.toCsv(),
                            { name ->
                                workbench.report(
                                    if (result.truncated) {
                                        tr("已导出 {0}（{1} 行，结果被最多行数截断）", name, result.rows.size)
                                    } else {
                                        tr("已导出 {0}（{1} 行）", name, result.rows.size)
                                    },
                                )
                            },
                            workbench::report,
                        )
                    },
                ) {
                    Icon(MiuixIcons.Download, contentDescription = tr("导出 CSV"))
                }
            }
        }
        if (result.columns.isEmpty()) return
        if (showComments && result.source == null) {
            Text(
                text = tr("这个结果来自多张表、视图联接或子查询，读不到列注释。"),
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote2,
            )
        }
        ResultGrid(result, showComments, onOverlay, Modifier.weight(1f))
    }
}

/**
 * Header and rows share one width per column, measured from the header, its comment and a sample of
 * the rows, so cells line up. Rows are lazy, so unlimited results stay scrollable.
 */
@Composable
private fun ResultGrid(
    result: QueryResult,
    showComments: Boolean,
    onOverlay: (ActiveOverlay?) -> Unit,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val headerStyle = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold)
    val commentStyle = MiuixTheme.textStyles.footnote2
    val cellStyle = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)
    val comments = if (showComments) result.columnComments else null
    val widths = remember(result.columns, result.rows, comments, headerStyle, cellStyle) {
        fun measure(text: String, style: TextStyle) =
            measurer.measure(text.take(48), style, maxLines = 1, softWrap = false).size.width
        result.columns.mapIndexed { column, name ->
            var widest = measure(name, headerStyle)
            comments?.getOrNull(column)?.let { widest = maxOf(widest, measure(it, commentStyle)) }
            result.rows.asSequence().take(120).forEach { row ->
                val cell = row.getOrNull(column) ?: "NULL"
                widest = maxOf(widest, measure(cell.lineSequence().firstOrNull().orEmpty(), cellStyle))
            }
            with(density) { (widest.toDp() + 24.dp).coerceIn(72.dp, 320.dp) }
        }
    }
    val total = widths.fold(0.dp) { sum, width -> sum + width }
    val horizontal = rememberScrollState()
    Column(modifier.fillMaxWidth().horizontalScroll(horizontal)) {
        Row(
            Modifier
                .width(total)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        ) {
            result.columns.forEachIndexed { index, name ->
                Column(Modifier.width(widths[index]).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = headerStyle)
                    if (showComments) {
                        Text(
                            text = comments?.getOrNull(index) ?: " ",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = commentStyle,
                        )
                    }
                }
            }
        }
        LazyColumn(Modifier.width(total).weight(1f)) {
            items(result.rows.size) { rowIndex ->
                val row = result.rows[rowIndex]
                Row {
                    result.columns.indices.forEach { index ->
                        val cell = row.getOrNull(index)
                        val shown = cell ?: "NULL"
                        Text(
                            text = shown.lineSequence().firstOrNull().orEmpty(),
                            modifier = Modifier
                                .width(widths[index])
                                .clickable {
                                    onOverlay(ActiveOverlay.Cell(result.columns.getOrElse(index) { tr("值") }, shown))
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (cell == null) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
                            style = cellStyle,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
