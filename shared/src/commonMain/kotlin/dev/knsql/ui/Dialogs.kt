package dev.knsql.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.knsql.data.Workbench
import dev.knsql.i18n.tr
import dev.knsql.model.ConnectionDraft
import dev.knsql.model.Engine
import dev.knsql.model.OracleDriver
import dev.knsql.model.toDraft
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

sealed interface ActiveOverlay {
    data class Editor(val connectionId: String?) : ActiveOverlay
    data class Delete(val connectionId: String) : ActiveOverlay
    data class Cell(val title: String, val value: String) : ActiveOverlay
}

@Composable
fun AppDialogs(
    workbench: Workbench,
    overlay: ActiveOverlay?,
    onOverlay: (ActiveOverlay?) -> Unit,
) {
    ConnectionEditorDialog(
        show = overlay is ActiveOverlay.Editor,
        workbench = workbench,
        connectionId = (overlay as? ActiveOverlay.Editor)?.connectionId,
        onDismiss = { onOverlay(null) },
    )
    val deleteTarget = (overlay as? ActiveOverlay.Delete)?.connectionId
    OverlayDialog(
        show = deleteTarget != null,
        title = tr("删除连接"),
        summary = tr("只删除本机保存的配置，不会改动数据库。"),
        onDismissRequest = { onOverlay(null) },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = tr("取消"),
                onClick = { onOverlay(null) },
                modifier = Modifier.weight(1f),
                minWidth = 0.dp,
            )
            Button(
                onClick = {
                    if (deleteTarget != null) workbench.deleteConnection(deleteTarget)
                    onOverlay(null)
                },
                modifier = Modifier.weight(1f),
                minWidth = 0.dp,
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.error,
                    contentColor = MiuixTheme.colorScheme.onError,
                ),
            ) {
                Text(tr("删除"))
            }
        }
    }
    ConfirmWriteDialog(workbench)
    val cell = overlay as? ActiveOverlay.Cell
    OverlayDialog(
        show = cell != null,
        title = cell?.title ?: tr("单元格"),
        onDismissRequest = { onOverlay(null) },
    ) {
        val clipboard = LocalClipboardManager.current
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = cell?.value ?: "",
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = tr("复制"),
                    onClick = {
                        clipboard.setText(AnnotatedString(cell?.value.orEmpty()))
                        onOverlay(null)
                    },
                    modifier = Modifier.weight(1f),
                    minWidth = 0.dp,
                )
                Button(
                    onClick = { onOverlay(null) },
                    modifier = Modifier.weight(1f),
                    minWidth = 0.dp,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("关闭")) }
            }
        }
    }
}

@Composable
private fun ConfirmWriteDialog(workbench: Workbench) {
    OverlayDialog(
        show = workbench.pendingSql != null,
        title = tr("确认写入"),
        summary = tr("语句里包含 INSERT、UPDATE、DELETE 或 DDL。平板上误触后会直接作用到服务器。"),
        onDismissRequest = workbench::dismissPending,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = workbench.pendingSql.orEmpty(),
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 8,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = tr("取消"),
                    onClick = workbench::dismissPending,
                    modifier = Modifier.weight(1f),
                    minWidth = 0.dp,
                )
                Button(
                    onClick = workbench::confirmPending,
                    modifier = Modifier.weight(1f),
                    minWidth = 0.dp,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("执行")) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionEditorDialog(
    show: Boolean,
    workbench: Workbench,
    connectionId: String?,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(ConnectionDraft()) }
    var previousCipher by remember { mutableStateOf("") }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf(false) }
    val databaseFilePicker = rememberDatabaseFilePicker()
    LaunchedEffect(show, connectionId) {
        if (!show) return@LaunchedEffect
        val saved = workbench.connections.find { it.id == connectionId }
        previousCipher = saved?.passwordCipher.orEmpty()
        val password = if (saved == null || saved.passwordCipher.isEmpty()) {
            ""
        } else {
            runCatching { dev.knsql.data.AppRuntime.store.decrypt(saved.passwordCipher) }.getOrDefault("")
        }
        draft = saved?.toDraft(password) ?: ConnectionDraft()
        testMessage = null
    }
    OverlayDialog(
        show = show,
        title = if (connectionId == null) tr("新建连接") else tr("编辑连接"),
        summary = tr("密码只保存在这台设备上。"),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            fun pickEngine(engine: Engine) {
                val port = draft.port.toIntOrNull()
                val replacePort = port == null || port == draft.engine.defaultPort
                draft = draft.copy(
                    engine = engine,
                    port = if (replacePort) engine.defaultPort.toString() else draft.port,
                )
            }
            if (engineDropdownPicker) {
                AppDropdownPreference(
                    items = Engine.entries.map { if (it == Engine.Dameng) tr("达梦") else it.label },
                    selectedIndex = Engine.entries.indexOf(draft.engine),
                    title = tr("数据库类型"),
                    onSelectedIndexChange = { pickEngine(Engine.entries[it]) },
                )
            } else Engine.entries.chunked(4).forEach { rowEngines ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowEngines.forEach { engine ->
                    val selected = draft.engine == engine
                    val label = when (engine) {
                        Engine.SqlServer -> "MSSQL"
                        Engine.Dameng -> tr("达梦")
                        Engine.PostgreSql -> "PgSQL"
                        else -> engine.label
                    }
                    Button(
                        onClick = { pickEngine(engine) },
                        modifier = Modifier.weight(1f),
                        minWidth = 0.dp,
                        colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    ) { Text(label, maxLines = 1) }
                }
                repeat(4 - rowEngines.size) { Spacer(Modifier.weight(1f)) }
            }
            }
            TextField(value = draft.name, onValueChange = { draft = draft.copy(name = it) }, label = tr("名称"), singleLine = true)
            if (draft.engine.fileBased) {
                TextField(
                    value = draft.database,
                    onValueChange = { draft = draft.copy(database = it) },
                    label = tr("数据库文件"),
                    singleLine = true,
                )
                TextButton(
                    text = tr("选择文件"),
                    onClick = {
                        databaseFilePicker.pick(
                            onPicked = { path ->
                                draft = draft.copy(
                                    database = path,
                                    name = draft.name.ifBlank { path.substringAfterLast('/').substringAfterLast('\\') },
                                )
                            },
                            onError = { workbench.report(it) },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = databaseFileHint,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                )
            } else {
            TextField(value = draft.host, onValueChange = { draft = draft.copy(host = it) }, label = tr("主机"), singleLine = true)
            if (draft.engine == Engine.SqlServer) {
                TextField(
                    value = draft.instance,
                    onValueChange = { draft = draft.copy(instance = it) },
                    label = tr("实例名（可留空）"),
                    singleLine = true,
                )
            }
            TextField(
                value = draft.port,
                onValueChange = { draft = draft.copy(port = it.filter(Char::isDigit)) },
                label = tr("端口"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextField(
                value = draft.database,
                onValueChange = { draft = draft.copy(database = it) },
                label = when (draft.engine) {
                    Engine.Oracle -> tr("服务名或 SID")
                    Engine.Dameng -> tr("模式（可留空）")
                    Engine.MySql, Engine.MariaDb, Engine.SqlServer -> tr("数据库（可留空）")
                    Engine.PostgreSql -> tr("数据库（留空为 postgres）")
                    else -> tr("数据库")
                },
                singleLine = true,
            )
            TextField(value = draft.username, onValueChange = { draft = draft.copy(username = it) }, label = tr("用户名"), singleLine = true)
            TextField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it, keepStoredPassword = false) },
                label = tr("密码"),
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = { reveal = !reveal },
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Icon(
                            imageVector = if (reveal) MiuixIcons.Hide else MiuixIcons.Show,
                            contentDescription = if (reveal) tr("隐藏密码") else tr("显示密码"),
                        )
                    }
                },
            )
            }
            if (draft.engine == Engine.Oracle) {
                SmallTitle(text = "Oracle")
                AppDropdownPreference(
                    items = listOf(
                        tr("自动"),
                        "ojdbc 23 · 19c / 21c / 23ai", // i18n-ignore: driver and version names
                        "ojdbc 19 · 11g R2 / 12c / 18c", // i18n-ignore: driver and version names
                    ),
                    selectedIndex = OracleDriver.entries.indexOf(draft.oracleDriver),
                    title = tr("驱动"),
                    summary = tr("自动：先用 ojdbc 23，服务器低于 19c 或 ojdbc 23 连不上时改用 ojdbc 19。"),
                    onSelectedIndexChange = { index -> draft = draft.copy(oracleDriver = OracleDriver.entries[index]) },
                )
                SwitchPreference(
                    checked = draft.oracleSid,
                    onCheckedChange = { draft = draft.copy(oracleSid = it) },
                    title = tr("使用 SID"),
                    summary = tr("关闭时按服务名连接；11g 及更早的库常用 SID。连不上时会自动换另一种方式。"),
                )
                SwitchPreference(
                    checked = draft.sysdba,
                    onCheckedChange = { draft = draft.copy(sysdba = it) },
                    title = "SYSDBA",
                    summary = tr("以 SYSDBA 身份登录。"),
                )
            }
            if (draft.engine == Engine.SqlServer || draft.engine.mysqlLike || draft.engine == Engine.PostgreSql) {
                SwitchPreference(
                    checked = draft.encrypt,
                    onCheckedChange = { draft = draft.copy(encrypt = it) },
                    title = tr("加密连接"),
                    summary = if (draft.engine == Engine.SqlServer) {
                        tr("服务器设置了强制加密时需要打开。")
                    } else {
                        tr("内网自签证书会信任服务器证书。")
                    },
                )
            }
            if (draft.engine == Engine.SqlServer) {
                SwitchPreference(
                    checked = draft.trustServerCertificate,
                    onCheckedChange = { draft = draft.copy(trustServerCertificate = it) },
                    title = tr("信任服务器证书"),
                    summary = tr("不校验证书链，内网自签证书需要打开。"),
                )
            }
            SwitchPreference(
                checked = draft.readOnly,
                onCheckedChange = { draft = draft.copy(readOnly = it) },
                title = tr("只读会话"),
                summary = tr("驱动层拒绝写入。巡检时建议打开。"),
            )
            testMessage?.let { message ->
                val clipboard = LocalClipboardManager.current
                Text(
                    text = message,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = {
                            clipboard.setText(AnnotatedString(message))
                            workbench.report(tr("已复制错误信息"))
                        },
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                )
                Text(
                    text = tr("长按错误信息可复制"),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = tr("测试"),
                    onClick = {
                        testMessage = tr("正在测试…")
                        workbench.test(draft) { testMessage = it }
                    },
                    modifier = Modifier.weight(1f),
                    minWidth = 0.dp,
                )
                TextButton(
                    text = tr("取消"),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    minWidth = 0.dp,
                )
                Button(
                    onClick = {
                        if (workbench.saveConnection(draft, previousCipher)) onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    minWidth = 0.dp,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存")) }
            }
        }
    }
}
