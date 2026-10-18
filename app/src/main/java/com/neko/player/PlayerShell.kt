package com.neko.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppScreen(model: PlayerViewModel, chooseDirectory: () -> Unit) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    val pages = rememberPagerState(initialPage = model.activeMode, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    AppSystemBars(immersive = !settingsOpen && !about)
    LaunchedEffect(model.pendingHomeLaunch) {
        if (model.pendingHomeLaunch) {
            about = false
            settingsOpen = false
            pages.scrollToPage(0)
            model.onHomeLaunchHandled()
        }
    }
    SideEffect { model.onPlaybackPageVisible(!settingsOpen && !about) }
    LaunchedEffect(pages) {
        snapshotFlow { pages.settledPage }.distinctUntilChanged().collect { index ->
            if (!model.pendingHomeLaunch) model.changeMode(index)
        }
    }
    BackHandler(enabled = pages.currentPage != 0 && !settingsOpen && !about) {
        scope.launch { pages.animateScrollToPage(0) }
    }
    BackHandler(enabled = settingsOpen && !about) { settingsOpen = false }
    BackHandler(enabled = about) { about = false }
    val backdrop = rememberLayerBackdrop()
    val coveredBlur by animateFloatAsState(if (settingsOpen || about) 12f else 0f, tween(300), label = "detailCoveredBlur")
    val selected = remember { mutableIntStateOf(pages.currentPage) }
    SideEffect { selected.intValue = pages.currentPage }
    val view = LocalView.current
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    fun haptic() { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK) }
    Box(Modifier.fillMaxSize()) {
        // Only page content is captured. Every glass control stays outside its sampled layer.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            HorizontalPager(pages, Modifier.fillMaxSize().blur(coveredBlur.dp).testTag("main-pages"),
                userScrollEnabled = !settingsOpen && !about, beyondViewportPageCount = 1) { page ->
                VideoScreen(model, page, chooseDirectory)
            }
            AnimatedVisibility(settingsOpen,
                enter = slideInHorizontally(tween(252, easing = FastOutSlowInEasing)) { it / 7 } + fadeIn(tween(189)),
                exit = slideOutHorizontally(tween(207, easing = FastOutSlowInEasing)) { it / 7 } + fadeOut(tween(162))) {
                Box(Modifier.fillMaxSize().blur(if (about) 12.dp else 0.dp)) {
                    SettingsPage(model, chooseDirectory, onBack = { settingsOpen = false }, onAbout = { about = true })
                }
            }
            AnimatedVisibility(about,
                enter = slideInHorizontally(tween(252, easing = FastOutSlowInEasing)) { it / 7 } + fadeIn(tween(189)),
                exit = slideOutHorizontally(tween(207, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(162))) {
                AboutPage { about = false }
            }
        }
        if (!settingsOpen && !about) PlayerBottomBar(selected, backdrop, dark,
            likeEnabled = model.playlist(pages.settledPage).isNotEmpty() && model.currentId != null,
            isLiked = model.currentId in model.likedIds,
            onLike = {
                model.currentId?.let(model::toggleLike)
                haptic()
            }, onSelect = { index ->
                haptic()
                settingsOpen = false
                scope.launch { pages.animateScrollToPage(index) }
            }, modifier = Modifier.align(Alignment.BottomCenter))
        if (!settingsOpen && !about) PlayerTopControls(backdrop, dark,
            accelerated = model.isPlaying && (model.longPress || model.settings.global2x),
            onSettings = { haptic(); settingsOpen = true },
            modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
internal fun AppSystemBars(immersive: Boolean = false) {
    val activity = LocalActivity.current
    val dark = MiuixTheme.colorScheme.background.luminance() < .5f
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (immersive) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
