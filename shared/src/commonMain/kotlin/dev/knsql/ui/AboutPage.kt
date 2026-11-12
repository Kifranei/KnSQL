package dev.knsql.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.knsql.ui.effect.BgEffectBackground
import dev.knsql.i18n.tr
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AboutPage(onBack: () -> Unit) {
    AboutBackHandler(enabled = true, onBack = onBack)
    val scrollState = rememberLazyListState()
    var logoHeightPx by remember { mutableIntStateOf(0) }
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val scrollProgress by remember {
        derivedStateOf {
            if (logoHeightPx <= 0) 0f
            else {
                val index = scrollState.firstVisibleItemIndex
                val offset = scrollState.firstVisibleItemScrollOffset
                if (index > 0) 1f else (offset.toFloat() / logoHeightPx).coerceIn(0f, 1f)
            }
        }
    }
    val barColor = if (isDark) Color.Black else MiuixTheme.colorScheme.surface
    val density = LocalDensity.current
    var barHeightPx by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        AboutBody(
            scrollState = scrollState,
            scrollProgress = scrollProgress,
            topInset = with(density) { barHeightPx.toDp() },
            onLogoHeightChanged = { logoHeightPx = it },
        )
        SmallTopAppBar(
            title = tr("关于"),
            modifier = Modifier.onSizeChanged { barHeightPx = it.height },
            color = barColor.copy(alpha = scrollProgress),
            titleColor = MiuixTheme.colorScheme.onSurface.copy(alpha = scrollProgress),
            defaultWindowInsetsPadding = true,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, contentDescription = tr("返回"))
                }
            },
        )
    }
}

@Composable
private fun AboutBody(
    scrollState: androidx.compose.foundation.lazy.LazyListState,
    scrollProgress: Float,
    topInset: Dp,
    onLogoHeightChanged: (Int) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val blurEnable = remember { isRenderEffectSupported() && isRuntimeShaderSupported() }
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current
    var logoHeightDp by remember { mutableStateOf(300.dp) }
    val logoLiftPx = with(density) { 96.dp.toPx() }
    val heroTopPadding = 148.dp
    val heroBottomPadding = 112.dp
    val titleBlend = remember(isDark) { aboutTitleBlendColors(isDark) }
    val cardBlend = remember(isDark) { aboutCardBlendColors(isDark) }

    // In landscape the camera cutout and the 3-button bar sit on the sides. The flowing background
    // stays full-bleed; only the hero and the cards keep clear of them, like KernelSU's About page.
    val sideInsets = WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)
    BgEffectBackground(
        dynamicBackground = true,
        modifier = Modifier.fillMaxSize(),
        bgModifier = Modifier.layerBackdrop(backdrop),
        effectBackground = true,
        isDarkTheme = isDark,
        isOs3 = true,
        alpha = { 1f - scrollProgress },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = (1f - scrollProgress * 1.35f).coerceIn(0f, 1f)
                    translationY = -logoLiftPx * scrollProgress
                }
                .windowInsetsPadding(sideInsets)
                .padding(top = topInset + heroTopPadding)
                .onSizeChanged { size -> with(density) { logoHeightDp = size.height.toDp() } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                modifier = Modifier.then(
                    if (blurEnable) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(16.dp),
                            blurRadius = 150f,
                            noiseCoefficient = BlurDefaults.NoiseCoefficient,
                            colors = BlurColors(blendColors = titleBlend),
                            contentBlendMode = BlendMode.DstIn,
                            enabled = true,
                        )
                    } else {
                        Modifier
                    },
                ),
                text = "KnSQL",
                color = MiuixTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )
            Text(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                text = "1.0.0",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(sideInsets),
            contentPadding = PaddingValues(
                top = topInset,
                bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        ) {
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(logoHeightDp + heroTopPadding + topInset + heroBottomPadding)
                        .onSizeChanged { onLogoHeightChanged(it.height) },
                )
            }
            item {
                SmallTitle(text = tr("项目"))
                AboutCard(backdrop, blurEnable, cardBlend) {
                    BasicComponent(
                        title = tr("项目主页"),
                        summary = PROJECT_URL.removePrefix("https://"),
                        onClick = { uriHandler.openUri(PROJECT_URL) },
                    )
                    BasicComponent(
                        title = tr("开源许可"),
                        summary = "Apache-2.0",
                        onClick = { uriHandler.openUri("https://www.apache.org/licenses/LICENSE-2.0") },
                    )
                }
            }
            item {
                SmallTitle(text = tr("界面与驱动"))
                AboutCard(backdrop, blurEnable, cardBlend) {
                    BasicComponent(
                        title = "Miuix",
                        summary = tr("HyperOS 界面组件。"),
                        onClick = { uriHandler.openUri("https://github.com/compose-miuix-ui/miuix") },
                    )
                    BasicComponent(
                        title = "Halcyon",
                        summary = tr("关于页流光背景参考。"),
                        onClick = { uriHandler.openUri("https://github.com/Kifranei/Halcyon") },
                    )
                    BasicComponent(
                        title = "Oracle JDBC",
                        summary = tr("连接 Oracle 11g R2 至 23ai（ojdbc 23 与 ojdbc 19）。"),
                        onClick = { uriHandler.openUri("https://www.oracle.com/database/technologies/appdev/jdbc.html") },
                    )
                    BasicComponent(
                        title = "MariaDB Connector/J",
                        summary = tr("连接 MySQL 5.5 至 9.x 和 MariaDB 10.x / 11.x。"),
                        onClick = { uriHandler.openUri("https://mariadb.com/kb/en/about-mariadb-connector-j/") },
                    )
                    BasicComponent(
                        title = tr("达梦 JDBC"),
                        summary = tr("连接达梦 DM7 / DM8。"),
                        onClick = { uriHandler.openUri("https://eco.dameng.com/document/dm/zh-cn/app-dev/java-jdbc.html") },
                    )
                    BasicComponent(
                        title = tr("Microsoft JDBC 与 jTDS"),
                        summary = tr("连接 SQL Server 2012 及以上，更早的版本用 jTDS。"),
                        onClick = { uriHandler.openUri("https://github.com/microsoft/mssql-jdbc") },
                    )
                    BasicComponent(
                        title = "PostgreSQL JDBC",
                        summary = tr("连接 PostgreSQL 9.6 至 18。"),
                        onClick = { uriHandler.openUri("https://jdbc.postgresql.org/") },
                    )
                    BasicComponent(
                        title = "SQLite JDBC",
                        summary = tr("打开本地 SQLite 3 数据库文件，自带 SQLite 引擎。"),
                        onClick = { uriHandler.openUri("https://github.com/xerial/sqlite-jdbc") },
                    )
                }
            }
            item {
                Box(Modifier.fillMaxWidth().height(520.dp))
            }
        }
    }
}

@Composable
private fun AboutCard(
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop,
    blurEnable: Boolean,
    cardBlend: List<BlendColorEntry>,
    content: @Composable () -> Unit,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .then(
                if (blurEnable) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(16.dp),
                        blurRadius = if (isDark) 72f else 64f,
                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                        colors = BlurColors(blendColors = cardBlend),
                        enabled = true,
                    )
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.defaultColors(
            color = if (blurEnable) Color.Transparent else MiuixTheme.colorScheme.secondaryContainer,
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
        content = { content() },
    )
}

private const val PROJECT_URL = "https://github.com/Kifranei/KnSQL"
