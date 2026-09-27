// Android actual of the floating navigation bar: the HyperOS 4 (OS4) glass capsule from Miuix's
// miuix-glass, ported from Halcyon (https://github.com/Kifranei/Halcyon). miuix-glass is not on
// Maven Central yet, so the needed sources are vendored under top.yukonga.miuix.kmp.glass.
package dev.knsql.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassNavigationBar as MiuixGlassNavigationBar

@Composable
actual fun GlassNavigationBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier,
) {
    if (items.isEmpty()) return
    // Without runtime shaders the capsule falls back to a solid surfaceContainer fill.
    val shaderSupported = remember { isRuntimeShaderSupported() }
    MiuixGlassNavigationBar(
        items = items.map { GlassNavigationItem(icon = it.icon, label = it.label, contentDescription = it.label) },
        selectedIndex = selectedIndex.coerceIn(0, items.lastIndex),
        onSelect = { index -> if (index != selectedIndex) onSelect(index) },
        backdrop = if (shaderSupported) backdrop else null,
        // The bar pads the gesture bar and its floating bottom margin by itself.
        modifier = modifier.padding(horizontal = 16.dp),
    )
}
