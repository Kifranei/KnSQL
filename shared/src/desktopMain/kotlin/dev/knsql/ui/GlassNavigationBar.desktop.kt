package dev.knsql.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Desktop fallback: a translucent (optionally blurred) pill with the same size, margins and item layout
 * as the Android liquid-glass bar, plus a sliding tinted indicator. No refraction shaders.
 */
@Composable
actual fun GlassNavigationBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier,
) {
    if (items.isEmpty()) return
    val shape = remember { RoundedCornerShape(32.dp) }
    val containerColor = MiuixTheme.colorScheme.surfaceContainer
    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val blurSupported = remember { isRenderEffectSupported() }
    val safeSelected = selectedIndex.coerceIn(0, items.lastIndex)
    val indicatorPosition by animateFloatAsState(
        targetValue = safeSelected.toFloat(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
    )

    BoxWithConstraints(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(GlassNavigationBarDefaults.Margin)
            .fillMaxWidth()
            .height(GlassNavigationBarDefaults.Height)
            .clip(shape)
            .then(
                if (blurSupported) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = { blur(25.dp.toPx(), 25.dp.toPx()) },
                        onDrawSurface = { drawRect(containerColor.copy(alpha = 0.9f)) },
                    )
                } else {
                    Modifier.background(containerColor.copy(alpha = 0.9f), shape)
                },
            )
            // Swallow clicks that land between tabs so they don't reach the page below.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(4.dp),
    ) {
        val tabWidth = maxWidth / items.size
        Box(
            Modifier
                .offset(x = tabWidth * indicatorPosition)
                .width(tabWidth)
                .fillMaxHeight()
                .background(primary.copy(alpha = 0.15f), shape),
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, item ->
                val selected = index == safeSelected
                val color = if (selected) primary else onSurface
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .selectable(
                            selected = selected,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = color,
                        modifier = Modifier.size(26.dp),
                    )
                    Text(text = item.label, fontSize = 11.sp, color = color, maxLines = 1)
                }
            }
        }
    }
}
