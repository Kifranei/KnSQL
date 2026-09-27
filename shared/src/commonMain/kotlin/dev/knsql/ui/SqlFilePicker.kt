package dev.knsql.ui

import androidx.compose.runtime.Composable

data class LoadedScript(
    val name: String,
    val text: String,
)

fun interface SqlFileOpener {
    fun open(onLoaded: (LoadedScript) -> Unit, onError: (String) -> Unit)
}

@Composable
expect fun rememberSqlFileOpener(): SqlFileOpener

fun interface SqlFileSaver {
    fun save(fileName: String, text: String, onSaved: (String) -> Unit, onError: (String) -> Unit)
}

@Composable
expect fun rememberFileSaver(mimeType: String): SqlFileSaver

@Composable
fun rememberSqlFileSaver(): SqlFileSaver = rememberFileSaver("application/sql")
