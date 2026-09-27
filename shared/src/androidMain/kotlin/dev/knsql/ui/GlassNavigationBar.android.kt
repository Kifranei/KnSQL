// Android actual of the liquid-glass navigation bar ported from Halcyon
// (https://github.com/Kifranei/Halcyon). The port itself and its licenses live in dev.knsql.ui.glass.
package dev.knsql.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.knsql.ui.glass.FloatingBottomBar
import dev.knsql.ui.glass.FloatingBottomBarItem
import dev.knsql.ui.glass.FloatingBottomBarMode
import dev.knsql.ui.glass.LocalFloatingBottomBarActiveIndex
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
actual fun GlassNavigationBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier,
) {
    if (items.isEmpty()) return
    val mode = remember {
        when {
            isRuntimeShaderSupported() && isRenderEffectSupported() -> FloatingBottomBarMode.LiquidGlass
            isRenderEffectSupported() -> FloatingBottomBarMode.Blur
            else -> FloatingBottomBarMode.None
        }
    }
    val safeSelected = selectedIndex.coerceIn(0, items.lastIndex)

    FloatingBottomBar(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(GlassNavigationBarDefaults.Margin),
        selectedIndex = { safeSelected },
        onSelected = onSelect,
        backdrop = backdrop,
        tabsCount = items.size,
        mode = mode,
        height = GlassNavigationBarDefaults.Height,
    ) {
        val activeTabIndex = LocalFloatingBottomBarActiveIndex.current
        items.forEachIndexed { index, item ->
            // Colors follow the sliding indicator rather than the committed selection, as in Halcyon.
            val selected = if (activeTabIndex >= 0) activeTabIndex == index else index == safeSelected
            val color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
            FloatingBottomBarItem(
                selected = index == safeSelected,
                onClick = { onSelect(index) },
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = color,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    text = item.label,
                    fontSize = 11.sp,
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}
