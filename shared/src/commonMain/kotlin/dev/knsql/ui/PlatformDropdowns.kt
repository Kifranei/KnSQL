package dev.knsql.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * The page layer that Android's OS4 glass dropdowns blur. App provides it for the pages under the
 * bottom bar; it stays null inside dialogs and wherever the page isn't recorded, and the popups then
 * use a solid fill instead of sampling content they would cover.
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** A preference row that picks one of [items]: the OS4 glass dropdown on Android, Miuix's window dropdown on desktop. */
@Composable
expect fun AppDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
)

/**
 * A menu of [actions] anchored to the composable it is placed in (put it inside the anchor's Box):
 * the OS4 glass list popup on Android, Miuix's window list popup on desktop.
 */
@Composable
expect fun AppActionMenu(
    show: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
)

/** Whether the connection editor picks the database type from a dropdown (Android) rather than a button grid (desktop). */
expect val engineDropdownPicker: Boolean
