package dev.knsql

import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.knsql.ui.GlassNavItem
import dev.knsql.ui.GlassNavigationBar
import dev.knsql.ui.LocalBottomBarInset
import dev.knsql.ui.glassBarInset
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.knsql.data.AppRuntime
import dev.knsql.data.Workbench
import dev.knsql.i18n.tr
import dev.knsql.ui.AppDialogs
import dev.knsql.ui.ConnectionsPage
import dev.knsql.ui.HistoryPage
import dev.knsql.ui.SessionKeepAlive
import dev.knsql.ui.SettingsPage
import dev.knsql.ui.WorkbenchPage
import dev.knsql.ui.shouldShowSplitPane
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private val sections = listOf(
    "连接" to MiuixIcons.Link,
    "工作台" to MiuixIcons.Notes,
    "历史" to MiuixIcons.Recent,
    "设置" to MiuixIcons.Settings,
)

@Composable
fun App() {
    val workbench = remember { AppRuntime.workbench }
    LaunchedEffect(workbench) { workbench.load() }
    val controller = remember(workbench.settings.colorMode) {
        when (workbench.settings.colorMode) {
            1 -> ThemeController(ColorSchemeMode.Light)
            2 -> ThemeController(ColorSchemeMode.Dark)
            else -> ThemeController(ColorSchemeMode.System)
        }
    }
    MiuixTheme(controller = controller) {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(workbench) {
            workbench.messages.collect { message -> snackbar.showSnackbar(message) }
        }
        SessionKeepAlive(
            sessionNames = workbench.sessions.map { it.name },
            enabled = workbench.settings.keepAlive,
        )
        var section by rememberSaveable { mutableIntStateOf(0) }
        var overlay by remember { mutableStateOf<dev.knsql.ui.ActiveOverlay?>(null) }
        var showAbout by rememberSaveable { mutableStateOf(false) }
        val wide = shouldShowSplitPane()
        // Navigation stays out of the way while the full-screen About page is open.
        val navVisible = !(section == 3 && showAbout)
        val glass = workbench.settings.glassBottomBar
        val density = LocalDensity.current
        var blurBarHeight by remember { mutableIntStateOf(0) }
        val barInset = when {
            wide || !navVisible -> 0.dp
            glass -> glassBarInset()
            else -> with(density) { blurBarHeight.toDp() }
        }
        val backdrop = rememberLayerBackdrop()
        Scaffold(
            containerColor = MiuixTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = barInset)) },
        ) { innerPadding ->

            if (wide) {
                // Only the bottom and end edges are handled here. The status bar stays unconsumed so each
                // page's top bar can pad itself below it, which keeps its action buttons out of the
                // notification shade's touch area in landscape.
                val direction = LocalLayoutDirection.current
                val edgePadding = PaddingValues(
                    end = innerPadding.calculateEndPadding(direction),
                    bottom = innerPadding.calculateBottomPadding(),
                )
                Row(Modifier.fillMaxSize()) {
                    if (navVisible) SectionRail(section) { section = it }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(edgePadding)
                            .consumeWindowInsets(edgePadding),
                    ) {
                        SectionBody(section, workbench, showAbout, { showAbout = it }, { section = it }, { overlay = it })
                    }
                }
            } else {
                // Pages draw edge to edge and are recorded into the backdrop; the floating glass bar
                // refracts them. Scrolling pages pad their end by LocalBottomBarInset.
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalBottomBarInset provides barInset) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                            SectionBody(section, workbench, showAbout, { showAbout = it }, { section = it }, { overlay = it })
                        }
                    }
                    if (navVisible && glass) {
                        GlassNavigationBar(
                            items = sections.map { (label, icon) -> GlassNavItem(tr(label), icon) },
                            selectedIndex = section,
                            onSelect = { section = it },
                            backdrop = backdrop,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    } else if (navVisible) {
                        SectionBar(
                            selected = section,
                            onSelect = { section = it },
                            backdrop = backdrop,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .onSizeChanged { blurBarHeight = it.height },
                        )
                    }
                }
            }
            AppDialogs(workbench, overlay) { overlay = it }
        }
    }
}

@Composable
private fun SectionBody(
    section: Int,
    workbench: Workbench,
    showAbout: Boolean,
    onShowAbout: (Boolean) -> Unit,
    onSection: (Int) -> Unit,
    onOverlay: (dev.knsql.ui.ActiveOverlay?) -> Unit,
) {
    when (section) {
        0 -> ConnectionsPage(workbench, onOverlay, onOpenWorkbench = { onSection(1) })
        1 -> WorkbenchPage(workbench, onOverlay)
        2 -> HistoryPage(workbench) { sql ->
            workbench.editor = sql
            workbench.editorCursor = 0
            workbench.scriptName = null
            onSection(1)
        }
        else -> SettingsPage(workbench, showAbout, onShowAbout)
    }
}

@Composable
private fun SectionRail(selected: Int, onSelect: (Int) -> Unit) {
    val state = rememberNavigationRailState(NavigationRailValue.Expanded)
    NavigationRail(
        state = state,
        defaultWindowInsetsPadding = true,
        color = MiuixTheme.colorScheme.surface,
        expandContentDescription = tr("展开导航"),
        collapseContentDescription = tr("收起导航"),
    ) {
        sections.forEachIndexed { index, (label, icon) ->
            NavigationRailItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = icon,
                label = tr(label),
            )
        }
    }
}

/** The standard Miuix bar with a Gaussian blur of the page scrolling underneath it. */
@Composable
private fun SectionBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    val surface = MiuixTheme.colorScheme.surface
    val blur = isRuntimeShaderSupported()
    Box(
        modifier.then(
            if (blur) {
                Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    blurRadius = 25f,
                    colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(color = surface.copy(alpha = 0.8f)))),
                )
            } else {
                Modifier
            },
        ),
    ) {
    NavigationBar(
        defaultWindowInsetsPadding = true,
        color = if (blur) Color.Transparent else surface,
    ) {
        sections.forEachIndexed { index, (label, icon) ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = icon,
                label = tr(label),
            )
        }
    }
}
}
