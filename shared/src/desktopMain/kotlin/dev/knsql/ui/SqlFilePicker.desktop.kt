package dev.knsql.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.knsql.i18n.tr
import dev.knsql.sql.decodeSqlBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberSqlFileOpener(): SqlFileOpener {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        SqlFileOpener { onLoaded, onError ->
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        val dialog = FileDialog(null as Frame?, tr("打开 SQL 脚本"), FileDialog.LOAD)
                        dialog.isVisible = true
                        val name = dialog.file ?: return@runCatching null
                        val file = File(dialog.directory, name)
                        LoadedScript(name, decodeSqlBytes(file.readBytes()))
                    }
                }
                loaded.onSuccess { script ->
                    if (script != null) onLoaded(script)
                }.onFailure { error ->
                    onError(error.message ?: tr("无法打开文件"))
                }
            }
        }
    }
}

@Composable
actual fun rememberFileSaver(mimeType: String): SqlFileSaver {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        SqlFileSaver { fileName, text, onSaved, onError ->
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val dialog = FileDialog(null as Frame?, tr("保存文件"), FileDialog.SAVE)
                        dialog.file = fileName
                        dialog.isVisible = true
                        val name = dialog.file ?: return@runCatching null
                        File(dialog.directory, name).writeText(text, Charsets.UTF_8)
                        name
                    }
                }
                saved.onSuccess { name -> if (name != null) onSaved(name) }
                    .onFailure { error -> onError(error.message ?: tr("保存失败")) }
            }
        }
    }
}
