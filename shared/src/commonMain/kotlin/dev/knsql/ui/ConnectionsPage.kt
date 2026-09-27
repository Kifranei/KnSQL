package dev.knsql.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.knsql.data.Workbench
import dev.knsql.i18n.tr
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ConnectionsPage(
    workbench: Workbench,
    onOverlay: (ActiveOverlay?) -> Unit,
    onOpenWorkbench: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val cardColors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
    val visible = workbench.connections.filter { saved ->
        query.isBlank() || saved.name.contains(query, true) || saved.host.contains(query, true) || saved.database.contains(query, true)
    }
    BlurredTopBarLayout(
    modifier = Modifier.fillMaxSize(),
    topBar = { barColor ->
    SmallTopAppBar(
        title = tr("连接"),
        color = barColor,
        subtitle = "Oracle 19c · MySQL · SQL Server",
        defaultWindowInsetsPadding = true,
        actions = {
            IconButton(onClick = { onOverlay(ActiveOverlay.Editor(null)) }) {
                Icon(MiuixIcons.Add, contentDescription = tr("新建连接"))
            }
        },
    )
    },
) { topPadding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = 24.dp + LocalBottomBarInset.current),
    ) {
        item {
            SearchBar(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                inputField = {
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { searchExpanded = false },
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                        label = tr("搜索连接"),
                        color = MiuixTheme.colorScheme.secondaryContainer,
                    )
                },
                expanded = searchExpanded,
                onExpandedChange = { searchExpanded = it },
            ) {}
        }
        item { SmallTitle(text = if (visible.isEmpty()) tr("没有连接") else tr("已保存")) }
        if (visible.isEmpty()) {
            item {
                Text(
                    text = tr("添加机房里的库。点一条连接会打开会话。"),
                    modifier = Modifier.padding(horizontal = 28.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        } else {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = cardColors,
                ) {
                    visible.forEach { saved ->
                        ArrowPreference(
                            title = saved.name,
                            summary = "${saved.engine.label}  ${saved.host}:${saved.port}" +
                                if (saved.database.isBlank()) "" else "  / ${saved.database}",
                            onClick = {
                                workbench.connect(saved)
                                onOpenWorkbench()
                            },
                            endActions = {
                                IconButton(onClick = { onOverlay(ActiveOverlay.Editor(saved.id)) }) {
                                    Icon(MiuixIcons.Edit, contentDescription = tr("编辑 {0}", saved.name))
                                }
                                IconButton(onClick = { onOverlay(ActiveOverlay.Delete(saved.id)) }) {
                                    Icon(MiuixIcons.Delete, contentDescription = tr("删除 {0}", saved.name))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    }
}
