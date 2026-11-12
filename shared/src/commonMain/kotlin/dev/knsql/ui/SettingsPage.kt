package dev.knsql.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.knsql.data.Workbench
import dev.knsql.i18n.tr
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import dev.knsql.i18n.AppLanguage
import dev.knsql.i18n.I18n
import dev.knsql.i18n.LanguageSettings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsPage(workbench: Workbench, showAbout: Boolean, onShowAbout: (Boolean) -> Unit) {
    var language by remember { mutableStateOf(LanguageSettings.selected()) }
    if (showAbout) {
        AboutPage(onBack = { onShowAbout(false) })
        return
    }
    val settings = workbench.settings
    var rows by remember(settings.maxRows) { mutableStateOf(settings.maxRows.toString()) }
    var timeout by remember(settings.queryTimeoutSec) { mutableStateOf(settings.queryTimeoutSec.toString()) }
    val cardColors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
    BlurredTopBarLayout(
    modifier = Modifier.fillMaxSize(),
    topBar = { barColor ->
    SmallTopAppBar(
        title = tr("设置"),
        subtitle = tr("外观和查询保护"),
        color = barColor,
        defaultWindowInsetsPadding = true,
    )
    },
) { topPadding ->
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = 24.dp + LocalBottomBarInset.current),
    ) {
        item { SmallTitle(text = tr("外观")) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = cardColors,
            ) {
                AppDropdownPreference(
                    items = listOf(tr("跟随系统"), tr("浅色"), tr("深色")),
                    selectedIndex = settings.colorMode.coerceIn(0, 2),
                    title = tr("外观"),
                    summary = tr("界面配色"),
                    onSelectedIndexChange = { workbench.updateSettings(settings.copy(colorMode = it)) },
                )
                SwitchPreference(
                    checked = settings.glassBottomBar,
                    onCheckedChange = { workbench.updateSettings(settings.copy(glassBottomBar = it)) },
                    title = tr("液态玻璃底栏"),
                    summary = tr("竖屏时使用悬浮的液态玻璃导航栏；关闭时为高斯模糊底栏"),
                )
                // Language names are shown in their own language so anyone can find theirs.
                AppDropdownPreference(
                    items = listOf(tr("跟随系统"), "简体中文", "繁體中文", "English"), // i18n-ignore: native names
                    selectedIndex = AppLanguage.entries.indexOf(language),
                    title = tr("语言"),
                    summary = tr("也可以在系统设置的应用语言里修改"),
                    onSelectedIndexChange = { index ->
                        language = AppLanguage.entries[index]
                        I18n.select(language)
                    },
                )
            }
        }
        item { SmallTitle(text = tr("查询")) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = cardColors,
            ) {
                SwitchPreference(
                    checked = settings.confirmWrites,
                    onCheckedChange = { workbench.updateSettings(settings.copy(confirmWrites = it)) },
                    title = tr("写入前确认"),
                    summary = tr("UPDATE、DELETE 和 DDL 会先弹出确认。巡检时保持打开。"),
                )
                SwitchPreference(
                    checked = settings.unlimitedRows,
                    onCheckedChange = { workbench.updateSettings(settings.copy(unlimitedRows = it)) },
                    title = tr("不限制行数"),
                    summary = tr("取回查询的全部结果行。结果很大时会占用大量内存，平板上可能变慢。"),
                )
                if (!settings.unlimitedRows) {
                    TextField(
                        value = rows,
                        onValueChange = { value ->
                            rows = value.filter(Char::isDigit)
                            value.toIntOrNull()?.takeIf { it in 1..5000 }?.let {
                                workbench.updateSettings(settings.copy(maxRows = it))
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        label = tr("单次最多行数"),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                TextField(
                    value = timeout,
                    onValueChange = { value ->
                        timeout = value.filter(Char::isDigit)
                        value.toIntOrNull()?.takeIf { it in 1..600 }?.let {
                            workbench.updateSettings(settings.copy(queryTimeoutSec = it))
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    label = tr("超时（秒）"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
        item { SmallTitle(text = tr("连接")) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = cardColors,
            ) {
                SwitchPreference(
                    checked = settings.keepAlive,
                    onCheckedChange = { workbench.updateSettings(settings.copy(keepAlive = it)) },
                    title = tr("后台保持连接"),
                    summary = tr("空闲时每 90 秒发一次心跳，断线后自动重连。Android 上会显示常驻通知，防止系统在后台杀掉连接。"),
                )
            }
        }
        item { SmallTitle(text = tr("关于")) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = cardColors,
            ) {
                ArrowPreference(
                    title = tr("关于 KnSQL"),
                    summary = tr("版本、界面和数据库驱动"),
                    onClick = { onShowAbout(true) },
                )
            }
        }
    }
    }
}
