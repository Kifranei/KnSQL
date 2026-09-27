package dev.knsql.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A page whose content scrolls underneath its top bar, with the bar drawing a Miuix progressive blur
 * of that content: strongest at the top edge, fading out towards the content, like the Miuix example
 * app. [topBar] receives the colour its bar should use (transparent while blurring). [content]
 * receives the top padding it must leave so its first item starts below the bar.
 *
 * Falls back to a plain bar above the content when the device lacks runtime shaders.
 */
@Composable
fun BlurredTopBarLayout(
    modifier: Modifier = Modifier,
    topBar: @Composable (barColor: Color) -> Unit,
    content: @Composable (topPadding: Dp) -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    if (!isRuntimeShaderSupported()) {
        Column(modifier) {
            topBar(surface)
            Box(Modifier.weight(1f).fillMaxWidth()) { content(0.dp) }
        }
        return
    }
    // The pages sit on the theme background, not on `surface`: blurring and tinting with the same colour
    // keeps the top of the bar from showing a lighter (or, in dark mode, a different black) strip.
    val background = MiuixTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
    val bar: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .matchParentSize()
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                        blurRadius = 10f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(BlendColorEntry(color = background.copy(alpha = 0.3f))),
                        ),
                    ),
            )
            topBar(Color.Transparent)
        }
    }
    // The bar is measured before the content is composed, in the same pass, so the content's top
    // padding is right from the first frame. Measuring it afterwards (onSizeChanged) left one frame
    // with no padding, which showed as the page flashing under the bar when switching tabs.
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val barPlaceables = subcompose(BlurSlot.Bar, bar).map { it.measure(loose) }
        val barHeight = barPlaceables.maxOfOrNull { it.height } ?: 0
        val topPadding = barHeight.toDp()
        val contentPlaceables = subcompose(BlurSlot.Content) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { content(topPadding) }
        }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceables.forEach { it.place(0, 0) }
            barPlaceables.forEach { it.place(0, 0) }
        }
    }
}

private enum class BlurSlot { Bar, Content }
