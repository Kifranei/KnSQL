package dev.knsql

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.knsql.data.AppRuntime
import dev.knsql.data.createFileStore
import dev.knsql.i18n.I18n
import java.io.File

fun main() = application {
    AppRuntime.install(createFileStore(File(System.getProperty("user.home"), ".knsql")))
    I18n.refresh()
    Window(
        onCloseRequest = ::exitApplication,
        title = "KnSQL",
        state = rememberWindowState(width = 1280.dp, height = 840.dp),
    ) {
        window.minimumSize = java.awt.Dimension(840, 600)
        App()
    }
}
