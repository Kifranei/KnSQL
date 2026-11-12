package dev.knsql.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.knsql.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberDatabaseFilePicker(): DatabaseFilePicker {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        DatabaseFilePicker { onPicked, onError ->
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    runCatching {
                        val dialog = FileDialog(null as Frame?, tr("选择 SQLite 数据库文件"), FileDialog.LOAD)
                        dialog.isVisible = true
                        val name = dialog.file ?: return@runCatching null
                        File(dialog.directory, name).absolutePath
                    }
                }
                picked.onSuccess { path -> if (path != null) onPicked(path) }
                    .onFailure { error -> onError(error.message ?: tr("无法打开文件")) }
            }
        }
    }
}

actual val databaseFileHint: String
    get() = tr("直接打开这个文件；文件不存在时会新建。只写文件名时放在 ~/.knsql/sqlite。")
