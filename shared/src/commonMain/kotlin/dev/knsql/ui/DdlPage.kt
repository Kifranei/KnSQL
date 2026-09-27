package dev.knsql.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.knsql.data.DdlView
import dev.knsql.data.Workbench
import dev.knsql.i18n.tr
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The clipboard on Android rejects very large payloads, so bigger scripts go to a file instead. */
private const val CLIPBOARD_LIMIT = 500_000

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DdlPage(workbench: Workbench, view: DdlView) {
    val clipboard = LocalClipboardManager.current
    val saver = rememberSqlFileSaver()
    val ready = !view.loading && view.error == null && view.text.isNotEmpty()
    val lines = remember(view.text) { view.text.trimEnd().lines() }

    AboutBackHandler(enabled = true, onBack = workbench::closeDdl)

    fun copy(text: String, done: String) {
        if (text.length > CLIPBOARD_LIMIT) {
            workbench.report(tr("内容太大，剪贴板放不下，请用「保存为文件」"))
        } else {
            clipboard.setText(AnnotatedString(text))
            workbench.report(done)
        }
    }

    Column(Modifier.fillMaxSize().padding(bottom = LocalBottomBarInset.current)) {
        SmallTopAppBar(
            color = MiuixTheme.colorScheme.background,
            title = view.title,
            subtitle = if (view.loading) view.progress else listOf(view.subtitle, view.progress).filter { it.isNotBlank() }.joinToString(" · "),
            defaultWindowInsetsPadding = true,
            navigationIcon = {
                IconButton(onClick = workbench::closeDdl) {
                    Icon(MiuixIcons.Back, contentDescription = tr("返回"))
                }
            },
            actions = {
                if (view.loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                }
                if (ready) {
                    IconButton(onClick = { copy(view.text, tr("已复制 DDL")) }) {
                        Icon(MiuixIcons.Copy, contentDescription = tr("复制全部"))
                    }
                    IconButton(
                        onClick = {
                            saver.save(
                                view.fileName,
                                view.text,
                                { name -> workbench.report(tr("已保存 {0}", name)) },
                                workbench::report,
                            )
                        },
                    ) {
                        Icon(MiuixIcons.Download, contentDescription = tr("保存为文件"))
                    }
                    IconButton(onClick = workbench::ddlToEditor) {
                        Icon(MiuixIcons.Notes, contentDescription = tr("放入编辑器"))
                    }
                }
            },
        )
        when {
            view.loading -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                CircularProgressIndicator()
                Text(
                    text = view.progress,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
                if (view.whole) {
                    Text(
                        text = tr("整库导出会逐个读取对象定义，对象多时需要一些时间。"),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
                TextButton(text = tr("取消"), onClick = workbench::closeDdl)
            }
            view.error != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                item {
                    Card(
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = {}, onLongClick = { copy(view.error, tr("已复制错误信息")) }),
                    ) {
                        Text(
                            text = view.error,
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    Text(
                        text = tr("长按可复制错误信息。Oracle 读取其他用户的对象需要 SELECT_CATALOG_ROLE。"),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
            else -> Card(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
            ) {
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
                        itemsIndexed(lines) { _, line ->
                            Text(
                                text = line.ifEmpty { " " },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                color = if (line.trimStart().startsWith("--")) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                },
                                style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }
}
