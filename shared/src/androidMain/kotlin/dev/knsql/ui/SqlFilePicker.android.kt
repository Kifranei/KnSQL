package dev.knsql.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.knsql.i18n.tr
import dev.knsql.sql.decodeSqlBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberSqlFileOpener(): SqlFileOpener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onLoaded = remember { mutableStateOf<(LoadedScript) -> Unit>({}) }
    val onError = remember { mutableStateOf<(String) -> Unit>({}) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                        ?: "script.sql"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error(tr("无法读取文件"))
                    LoadedScript(name, decodeSqlBytes(bytes))
                }
            }
            loaded.onSuccess { onLoaded.value(it) }.onFailure { error ->
                onError.value(error.message ?: tr("无法打开文件"))
            }
        }
    }
    return remember(launcher) {
        SqlFileOpener { loaded, failed ->
            onLoaded.value = loaded
            onError.value = failed
            launcher.launch(arrayOf("application/sql", "text/plain", "application/octet-stream", "*/*"))
        }
    }
}

@Composable
actual fun rememberFileSaver(mimeType: String): SqlFileSaver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { mutableStateOf<String?>(null) }
    val onSaved = remember { mutableStateOf<(String) -> Unit>({}) }
    val onError = remember { mutableStateOf<(String) -> Unit>({}) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        val text = pending.value
        pending.value = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error(tr("无法写入文件"))
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?: tr("文件")
                }
            }
            saved.onSuccess { onSaved.value(it) }.onFailure { error -> onError.value(error.message ?: tr("保存失败")) }
        }
    }
    return remember(launcher) {
        SqlFileSaver { fileName, text, saved, failed ->
            pending.value = text
            onSaved.value = saved
            onError.value = failed
            launcher.launch(fileName)
        }
    }
}
