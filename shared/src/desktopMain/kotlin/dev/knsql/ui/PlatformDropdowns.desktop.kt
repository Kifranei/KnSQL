package dev.knsql.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
actual fun AppDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier,
    summary: String?,
    enabled: Boolean,
) {
    WindowDropdownPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

@Composable
actual fun AppActionMenu(
    show: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
) {
    WindowListPopup(
        show = show,
        alignment = PopupPositionProvider.Align.Start,
        onDismissRequest = onDismissRequest,
    ) {
        ListPopupColumn {
            actions.forEachIndexed { index, (label, action) ->
                DropdownImpl(
                    text = label,
                    optionSize = actions.size,
                    isSelected = false,
                    index = index,
                    onSelectedIndexChange = {
                        onDismissRequest()
                        action()
                    },
                )
            }
        }
    }
}

actual val engineDropdownPicker: Boolean = false
