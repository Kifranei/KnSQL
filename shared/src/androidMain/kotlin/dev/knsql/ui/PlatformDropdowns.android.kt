// Android OS4 glass dropdowns, ported from Halcyon's EllaGlassListPopup / EllaSpinnerPreference
// (https://github.com/Kifranei/Halcyon, Apache-2.0) on top of the vendored miuix-glass popups.
package dev.knsql.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.glass.GlassDropdownPopupContent
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.WindowNavigationEventScope

/**
 * OS4 glass list popup (Halcyon's EllaGlassListPopup): hosts [GlassDropdownPopupContent] in a platform
 * [Popup] anchored to its parent, and stays composed until the exit animation has finished.
 */
@Composable
internal fun GlassListPopup(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchorBoundsOverride: Rect? = null,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    maxHeight: Dp = GlassPopupDefaults.MaxHeight,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var capturedAnchor by remember { mutableStateOf(Rect.Zero) }
    val anchorBounds = anchorBoundsOverride ?: capturedAnchor
    val latestOnDismissFinished by rememberUpdatedState(onDismissFinished)
    LaunchedEffect(show) { if (show) open = true }
    if (!open) return
    Popup(
        popupPositionProvider = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchor: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    capturedAnchor = Rect(
                        anchor.left.toFloat(), anchor.top.toFloat(),
                        anchor.right.toFloat(), anchor.bottom.toFloat(),
                    )
                    return IntOffset(-anchor.left, -anchor.top)
                }
            }
        },
        properties = PopupProperties(focusable = true),
        onDismissRequest = onDismissRequest,
    ) {
        WindowNavigationEventScope {
            Box(Modifier.fillMaxSize()) {
                GlassDropdownPopupContent(
                    show = show,
                    onDismissRequest = onDismissRequest,
                    anchorBounds = anchorBounds,
                    backdrop = backdrop,
                    sizing = GlassPopupSizing(maxHeight = maxHeight),
                    modifier = modifier,
                    onDismissFinished = {
                        open = false
                        latestOnDismissFinished?.invoke()
                    },
                    content = content,
                )
            }
        }
    }
}

/**
 * OS4 version of WindowDropdownPreference (Halcyon's EllaSpinnerPreference): the row looks like a
 * standard preference and its options open in a glass popup anchored to the row.
 */
@Composable
internal fun Os4DropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    maxHeight: Dp? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    val actualEnabled = enabled && items.isNotEmpty()
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val hapticFeedback = LocalHapticFeedback.current
    val actionColor = if (actualEnabled) {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    } else {
        MiuixTheme.colorScheme.disabledOnSecondaryVariant
    }
    val setExpanded: (Boolean) -> Unit = { next ->
        if (expanded != next) {
            expanded = next
            currentOnExpandedChange?.invoke(next)
        }
    }

    BasicComponent(
        modifier = modifier.onGloballyPositioned { rowBounds = it.boundsInWindow() },
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val value = items.getOrNull(selectedIndex)
                if (showValue && !value.isNullOrEmpty()) {
                    Text(
                        text = value,
                        modifier = Modifier.padding(end = 8.dp).weight(1f, fill = false),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = actionColor,
                        textAlign = TextAlign.End,
                    )
                }
                DropdownArrowEndAction(actionColor = actionColor)
            }
        },
        insideMargin = insideMargin,
        onClick = {
            setExpanded(!expanded)
            if (expanded) hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
        },
        role = Role.DropdownList,
        holdDownState = expanded,
        enabled = actualEnabled,
    )

    GlassListPopup(
        show = expanded && actualEnabled,
        onDismissRequest = { setExpanded(false) },
        anchorBoundsOverride = rowBounds,
        backdrop = backdrop,
        maxHeight = maxHeight ?: GlassPopupDefaults.MaxHeight,
    ) {
        items.forEachIndexed { index, item ->
            GlassPopupItem(
                text = item,
                selected = index == selectedIndex,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                    setExpanded(false)
                    onSelectedIndexChange?.invoke(index)
                },
            )
        }
    }
}

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
    Os4DropdownPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        backdrop = rememberGlassBackdrop(),
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

@Composable
actual fun AppActionMenu(
    show: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
) {
    val hapticFeedback = LocalHapticFeedback.current
    GlassListPopup(
        show = show,
        onDismissRequest = onDismissRequest,
        backdrop = rememberGlassBackdrop(),
    ) {
        actions.forEach { (label, action) ->
            GlassPopupItem(
                text = label,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDismissRequest()
                    action()
                },
            )
        }
    }
}

/** The page backdrop when runtime shaders can blur it, otherwise null for the solid fill. */
@Composable
private fun rememberGlassBackdrop(): Backdrop? {
    val supported = remember { isRuntimeShaderSupported() }
    return if (supported) LocalGlassBackdrop.current else null
}

actual val engineDropdownPicker: Boolean = true
