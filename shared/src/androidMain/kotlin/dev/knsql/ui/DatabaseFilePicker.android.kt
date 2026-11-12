package dev.knsql.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.knsql.data.SqliteFiles
import dev.knsql.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android only lets the SQLite native library open real file paths, and files picked through the system
 * picker are content URIs. The picked database is copied into the app's private sqlite directory and the
 * connection opens that copy.
 */
@Composable
actual fun rememberDatabaseFilePicker(): DatabaseFilePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onPicked = remember { mutableStateOf<(String) -> Unit>({}) }
    val onError = remember { mutableStateOf<(String) -> Unit>({}) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?: "database.db"
                    val safeName = display.replace(Regex("[^A-Za-z0-9._\\-\\p{L}]"), "_").ifBlank { "database.db" }
                    val dir = SqliteFiles.baseDir.also { it.mkdirs() }
                    val target = uniqueFile(dir, safeName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error(tr("无法读取文件"))
                    target.absolutePath
                }
            }
            copied.onSuccess { onPicked.value(it) }
                .onFailure { error -> onError.value(error.message ?: tr("无法打开文件")) }
        }
    }
    return remember(launcher) {
        DatabaseFilePicker { picked, failed ->
            onPicked.value = picked
            onError.value = failed
            launcher.launch(arrayOf("application/vnd.sqlite3", "application/x-sqlite3", "application/octet-stream", "*/*"))
        }
    }
}

private fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
    var index = 1
    while (candidate.exists()) {
        candidate = File(dir, "$base-$index$ext")
        index++
    }
    return candidate
}

actual val databaseFileHint: String
    get() = tr("选中的文件会复制到应用私有目录再打开，修改只作用于副本。只写文件名时在应用目录里新建或打开。")
