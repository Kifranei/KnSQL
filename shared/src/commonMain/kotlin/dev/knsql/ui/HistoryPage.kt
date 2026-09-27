package dev.knsql.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.knsql.data.Workbench
import dev.knsql.i18n.tr
import dev.knsql.model.HistoryEntry
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class HistoryDelete { Selected, All }

@Composable
fun HistoryPage(workbench: Workbench, onUse: (String) -> Unit) {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf<HistoryDelete?>(null) }
    val history = workbench.history
    val present = history.mapTo(HashSet()) { it.id }
    val picked = selected.intersect(present)
    val haptic = LocalHapticFeedback.current

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    AboutBackHandler(enabled = selecting, onBack = ::exitSelection)

    val barInset = LocalBottomBarInset.current
    Column(Modifier.fillMaxSize()) {
        BlurredTopBarLayout(
        modifier = Modifier.weight(1f),
        topBar = { barColor ->
        if (selecting) {
            SmallTopAppBar(
                title = if (picked.isEmpty()) tr("选择记录") else tr("已选择 {0} 项", picked.size),
                color = barColor,
                defaultWindowInsetsPadding = true,
                navigationIcon = {
                    IconButton(onClick = ::exitSelection) {
                        Icon(MiuixIcons.Close, contentDescription = tr("退出多选"))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { selected = if (picked.size == present.size) emptySet() else present },
                    ) {
                        Icon(
                            MiuixIcons.SelectAll,
                            contentDescription = if (picked.size == present.size) tr("取消全选") else tr("全选"),
                        )
                    }
                },
            )
        } else {
            SmallTopAppBar(
                title = tr("历史"),
                color = barColor,
                subtitle = tr("最近 100 条"),
                defaultWindowInsetsPadding = true,
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { confirm = HistoryDelete.All }) {
                            Icon(MiuixIcons.Delete, contentDescription = tr("清空历史"))
                        }
                    }
                },
            )
        }
        },
        ) { topPadding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPadding, bottom = 24.dp + if (selecting) 0.dp else barInset),
        ) {
            if (history.isEmpty()) {
                item {
                    Text(
                        text = tr("执行过的语句会出现在这里。点一条可以放回编辑器，长按可以多选删除。"),
                        modifier = Modifier.padding(horizontal = 28.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            } else {
                item { SmallTitle(text = if (selecting) tr("点按勾选，可多选") else tr("记录 · 长按多选")) }
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
                    ) {
                        history.forEach { entry ->
                            val checked = entry.id in picked
                            val toggle = {
                                selected = if (checked) picked - entry.id else picked + entry.id
                            }
                            HistoryRow(
                                entry = entry,
                                selecting = selecting,
                                checked = checked,
                                onClick = { if (selecting) toggle() else onUse(entry.sql) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (selecting) {
                                        toggle()
                                    } else {
                                        selecting = true
                                        selected = setOf(entry.id)
                                    }
                                },
                                onCheck = toggle,
                            )
                        }
                    }
                }
            }
        }
        }
        if (selecting) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .padding(bottom = barInset),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = tr("取消"),
                    onClick = ::exitSelection,
                    modifier = Modifier.weight(1f),
                    minWidth = 0.dp,
                )
                Button(
                    onClick = { confirm = HistoryDelete.Selected },
                    enabled = picked.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    minWidth = 0.dp,
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError,
                    ),
                ) {
                    Text(if (picked.isEmpty()) tr("删除") else tr("删除 {0} 项", picked.size))
                }
            }
        }
    }

    OverlayDialog(
        show = confirm != null,
        title = if (confirm == HistoryDelete.All) tr("清空历史") else tr("删除记录"),
        summary = if (confirm == HistoryDelete.All) {
            tr("删除全部 {0} 条执行记录。只清理本机历史，不会改动数据库。", history.size)
        } else {
            tr("删除所选的 {0} 条执行记录。只清理本机历史，不会改动数据库。", picked.size)
        },
        onDismissRequest = { confirm = null },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = tr("取消"),
                onClick = { confirm = null },
                modifier = Modifier.weight(1f),
                minWidth = 0.dp,
            )
            Button(
                onClick = {
                    if (confirm == HistoryDelete.All) workbench.clearHistory() else workbench.deleteHistory(picked)
                    confirm = null
                    exitSelection()
                },
                modifier = Modifier.weight(1f),
                minWidth = 0.dp,
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.error,
                    contentColor = MiuixTheme.colorScheme.onError,
                ),
            ) {
                Text(if (confirm == HistoryDelete.All) tr("清空") else tr("删除"))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheck: () -> Unit,
) {
    BasicComponent(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        title = entry.sql.lineSequence().first().take(80),
        summary = historySummary(entry),
        startAction = if (selecting) {
            {
                Checkbox(
                    state = ToggleableState(checked),
                    onClick = onCheck,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        } else {
            null
        },
        endActions = if (selecting) {
            null
        } else {
            {
                Icon(
                    MiuixIcons.Basic.ArrowRight,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
    )
}

private fun historySummary(entry: HistoryEntry): String {
    val detail = if (entry.success) entry.message else tr("失败：{0}", entry.message)
    val elapsed = if (detail.trimEnd().endsWith("ms")) "" else " · ${entry.elapsedMs} ms"
    return "${entry.connectionName} · $detail$elapsed"
}
