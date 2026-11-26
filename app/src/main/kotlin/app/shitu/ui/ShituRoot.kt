package app.shitu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shitu.AppContainer
import app.shitu.core.toast
import app.shitu.navigation.ShituTab
import app.shitu.ui.components.FloatingNavBar
import app.shitu.ui.components.NavBarBottomMargin
import app.shitu.ui.components.NavBarHeight
import app.shitu.ui.components.ScreenBottomSpacing
import app.shitu.ui.components.ScreenHorizontalPadding
import app.shitu.ui.components.ScreenTopPadding
import app.shitu.ui.design.PaperBackground
import app.shitu.ui.design.StatusBarScrim
import app.shitu.ui.downloads.DownloadsPage
import app.shitu.ui.me.AccountsPage
import app.shitu.ui.me.BiliLoginPage
import app.shitu.ui.me.DeveloperPage
import app.shitu.ui.me.EnginesPage
import app.shitu.ui.me.LicensesPage
import app.shitu.ui.me.MeScreen
import app.shitu.ui.me.SponsorPage
import app.shitu.ui.me.StoragePage
import app.shitu.ui.me.WebLoginPage
import app.shitu.ui.search.SearchScreen
import app.shitu.ui.theme.ShituTheme
import app.shitu.ui.tools.ToolsScreen
import app.shitu.ui.workspace.WorkspaceScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }
val LocalNav = staticCompositionLocalOf<AppNav> { error("no nav") }

@Composable
fun ShituRoot(container: AppContainer, nav: AppNav) {
    val settings by container.settings.collectAsStateWithLifecycle()
    ShituTheme(settings = settings) {
        CompositionLocalProvider(LocalContainer provides container, LocalNav provides nav) {
            Shell(container, nav)
        }
    }
}

@Composable
private fun Shell(container: AppContainer, nav: AppNav) {
    val context = LocalContext.current
    val tasks by container.downloadManager.tasks.collectAsStateWithLifecycle()
    val notice by container.downloadManager.notices.collectAsStateWithLifecycle()
    val activeDownloads = tasks.count { it.active }

    // 首次启动申请一次相册权限（拒绝后不再追问，可在「下载与存储」里再开）
    val askMedia = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("first_run", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("asked_media", false) && !app.shitu.ui.me.StoragePermissions.hasMedia(context)) {
            prefs.edit().putBoolean("asked_media", true).apply()
            askMedia.launch(app.shitu.ui.me.StoragePermissions.media)
        }
    }

    LaunchedEffect(notice) {
        notice?.let { context.toast(it); container.downloadManager.consumeNotice() }
    }

    BackHandler(enabled = nav.pages.isNotEmpty() || nav.tab != ShituTab.SEARCH) {
        if (!nav.pop()) nav.tab = ShituTab.SEARCH
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabPadding = PaddingValues(
        start = ScreenHorizontalPadding,
        end = ScreenHorizontalPadding,
        top = top + ScreenTopPadding,
        bottom = NavBarHeight + NavBarBottomMargin + bottom + ScreenBottomSpacing,
    )
    val pagePadding = PaddingValues(
        start = ScreenHorizontalPadding,
        end = ScreenHorizontalPadding,
        top = top,
        bottom = bottom + ScreenBottomSpacing,
    )
    val hazeState = rememberHazeState()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()

    Box(Modifier.fillMaxSize()) {
        PaperBackground(Modifier.hazeSource(hazeState)) {
            AnimatedContent(
                targetState = nav.tab,
                transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
                label = "tab",
            ) { tab ->
                when (tab) {
                    ShituTab.SEARCH -> SearchScreen(tabPadding)
                    ShituTab.TOOLS -> ToolsScreen(tabPadding)
                    ShituTab.WORKSPACE -> WorkspaceScreen(tabPadding)
                    ShituTab.ME -> MeScreen(tabPadding)
                }
            }
        }

        // 二级页面：从右侧推入，盖住标签页
        AnimatedContent(
            targetState = nav.top,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally(spatial) { it / 4 } + fadeIn(effects)) togetherWith fadeOut(effects)
                } else {
                    fadeIn(effects) togetherWith (slideOutHorizontally(spatial) { it / 4 } + fadeOut(effects))
                }
            },
            label = "page",
        ) { page ->
            if (page != null) {
                PaperBackground {
                    when (page) {
                        Page.Downloads -> DownloadsPage(pagePadding)
                        Page.Accounts -> AccountsPage(pagePadding)
                        Page.BiliLogin -> BiliLoginPage(pagePadding)
                        is Page.WebLogin -> WebLoginPage(page.site, pagePadding)
                        Page.Engines -> EnginesPage(pagePadding)
                        Page.Storage -> StoragePage(pagePadding)
                        Page.Licenses -> LicensesPage(pagePadding)
                        Page.Sponsor -> SponsorPage(pagePadding)
                        Page.Developer -> DeveloperPage(pagePadding)
                    }
                }
            }
        }

        StatusBarScrim(Modifier.align(Alignment.TopCenter))

        AnimatedVisibility(
            visible = nav.pages.isEmpty(),
            enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(effects),
            exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(effects),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FloatingNavBar(
                selectedTab = nav.tab,
                onTabSelected = { nav.tab = it },
                hazeState = hazeState,
                workspaceBadgeCount = activeDownloads,
            )
        }

        val selection = nav.selection
        AnimatedVisibility(
            visible = nav.pages.isEmpty() && selection != null,
            enter = androidx.compose.animation.scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(effects),
            exit = androidx.compose.animation.scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(effects),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            // 退出动画期间 selection 可能已为空，保留最后一次的操作
            val last = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(selection) }
            if (selection != null) last.value = selection
            last.value?.let { a ->
                app.shitu.ui.components.SelectionDock(a, hazeState, onOpenQueue = { nav.push(Page.Downloads) })
            }
        }
    }
}
