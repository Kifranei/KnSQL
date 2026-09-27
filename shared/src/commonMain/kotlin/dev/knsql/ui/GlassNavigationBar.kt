package dev.knsql.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.LayerBackdrop

data class GlassNavItem(val label: String, val icon: ImageVector)

object GlassNavigationBarDefaults {
    /** Height of the floating pill itself. */
    val Height: Dp = 64.dp

    /** Gap between the pill and the screen edges (and above the gesture bar). */
    val Margin: Dp = 12.dp
}

/**
 * Space the floating bar covers at the bottom of the screen, gesture bar included. Scrolling pages add
 * it as bottom content padding so their last item can scroll out from under the glass.
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

@Composable
fun glassBarInset(): Dp =
    GlassNavigationBarDefaults.Height + GlassNavigationBarDefaults.Margin * 2 +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * Floating liquid-glass navigation bar ported from Halcyon. It refracts whatever is recorded into
 * [backdrop], so the page content behind it must be drawn with `Modifier.layerBackdrop(backdrop)`.
 * It places itself: pass a modifier that aligns it to the bottom of the screen; it adds the gesture
 * bar inset and [GlassNavigationBarDefaults.Margin] on its own.
 */
@Composable
expect fun GlassNavigationBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
)
