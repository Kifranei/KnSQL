package dev.knsql.ui

import androidx.compose.runtime.Composable

/** Picks a local database file (SQLite) and hands back the path the driver should open. */
fun interface DatabaseFilePicker {
    fun pick(onPicked: (String) -> Unit, onError: (String) -> Unit)
}

@Composable
expect fun rememberDatabaseFilePicker(): DatabaseFilePicker

/** Shown under the file field: how the platform treats picked files. */
expect val databaseFileHint: String
