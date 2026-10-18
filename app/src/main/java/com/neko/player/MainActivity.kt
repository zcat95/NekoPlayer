package com.neko.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: PlayerViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[PlayerViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            NekoTheme(model.settings.theme) {
                // Video intentionally draws edge to edge. Individual controls consume safe insets.
                Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { NekoApp(model) }
            }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) model.unmute()
        return super.onKeyDown(keyCode, event)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            model.onAppLaunched()
        }
    }
}

data class DirectoryGrant(val uri: Uri, val flags: Int)

internal class OpenVideoDirectory : ActivityResultContract<Uri?, DirectoryGrant?>() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        ActivityResultContracts.OpenDocumentTree().createIntent(context, input).apply {
            flags = (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv()) or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
    override fun parseResult(resultCode: Int, intent: Intent?): DirectoryGrant? =
        if (resultCode == Activity.RESULT_OK) intent?.data?.let { DirectoryGrant(it, intent.flags) } else null
}

@OptIn(UnstableApi::class)
@Composable
private fun NekoApp(model: PlayerViewModel) {
    if (!model.onboardingCompleted) AppSystemBars()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, _ ->
            model.onForegroundChanged(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val setupDirectoryPicker = rememberLauncherForActivityResult(OpenVideoDirectory()) { grant ->
        if (grant != null) model.selectDirectory(grant.uri, grant.flags)
    }
    AnimatedContent(model.onboardingCompleted, modifier = Modifier.fillMaxSize(),
        transitionSpec = { setupTransition() }, label = "setupCompletion") { completed ->
        val radius by transition.animateFloat(transitionSpec = { tween(520) }, label = "setupCompletionBlur") {
            if (it == EnterExitState.PostExit) 14f else 0f
        }
        Box(Modifier.fillMaxSize().blur(radius.dp)) {
            if (completed) AppScreen(model) { setupDirectoryPicker.launch(model.directory?.toUri()) }
            else FirstLaunchWizard(model.directoryLabel, model.directoryReadable, model.scanning, model.settings,
                model.message, { setupDirectoryPicker.launch(model.directory?.toUri()) }, model::updateSettings,
                model::dismissMessage, model::finishOnboarding)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun VideoScreen(model: PlayerViewModel, mode: Int, chooseDirectory: () -> Unit) {
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Capture video content only. Buttons, notices and the blur output are siblings.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            if (model.playlist(mode).isNotEmpty()) {
                VideoPlaylist(model, mode)
            }
        }
        if (model.videos.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(40.dp)) {
                HyperTopBarMaterial(backdrop, 1f, surface = Color.Black)
            }
        }
        if (model.scanning) Text("正在扫描视频…", color = Color.White, modifier = Modifier.align(Alignment.Center))
        else if (mode == 1 && model.playlist(1).isEmpty()) {
            Text("暂无收藏视频，在首页点赞后会显示在这里。", color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp))
        } else if (model.videos.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (model.directory == null) "选择本地视频目录开始播放" else "没有找到视频，请检查目录或读取权限。", color = Color.White)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { chooseDirectory() }) { Text("选择视频目录") }
        }
        model.message?.let { message ->
            Column(Modifier.align(Alignment.Center).padding(24.dp)
                .background(MiuixTheme.colorScheme.surface).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message)
                TextButton("知道了", model::dismissMessage)
                TextButton("重新选择目录", { chooseDirectory() })
            }
        }

    }
}

@Composable
@OptIn(UnstableApi::class)
private fun VideoPlaylist(model: PlayerViewModel, mode: Int) {
    val entries = model.playlist(mode)
    val active = mode == model.activeMode
    val pager = rememberPagerState(initialPage = entries.indexOfFirst { it.id == model.selectedId(mode) }.coerceAtLeast(0),
        pageCount = { entries.size })
    LaunchedEffect(active, model.currentId, entries) {
        if (active) {
            val index = entries.indexOfFirst { it.id == model.currentId }
            // A natural media transition must not interrupt an in-flight user swipe.
            if (index >= 0 && index != pager.settledPage && !pager.isScrollInProgress) pager.scrollToPage(index)
        }
    }
    LaunchedEffect(pager, active, entries) {
        if (active) snapshotFlow { pager.settledPage to pager.isScrollInProgress }
            .distinctUntilChanged().drop(1).collect { (index, scrolling) ->
            // Also restore the visible clip after a cancelled swipe if auto-next fired during the drag.
            if (!scrolling) entries.getOrNull(index)?.let { model.selectVideo(it.id) }
        }
    }
    LaunchedEffect(pager, entries, active) {
        val policy = SwipePreloadPolicy()
        if (active) snapshotFlow { Triple(pager.currentPage, pager.targetPage, pager.isScrollInProgress) }
            .distinctUntilChanged().collect { (current, target, scrolling) ->
            model.prefetchVideos(mode, policy.update(current, target, entries.size, scrolling, SystemClock.elapsedRealtime()))
        }
    }
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无收藏视频，在首页点赞后会显示在这里。", color = Color.White)
        }
    } else Box(Modifier.fillMaxSize().clipToBounds()) {
        val player = model.controller
        val currentId = model.currentId
        var renderedId by remember(player, active) { mutableStateOf<String?>(null) }
        val currentIndex = entries.indexOfFirst { it.id == currentId }
        if (active && player != null && currentId != null && currentIndex >= 0) {
            VideoPlaybackSurface(player, model.settings.crop, model.isPlaying,
                onFirstFrame = { renderedId = it },
                modifier = Modifier.graphicsLayer {
                    // Follow the playing page during a drag without recreating or rebinding the surface.
                    translationY = ((currentIndex - pager.currentPage) - pager.currentPageOffsetFraction) * size.height
                })
        }
        VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { entries[it].id }) { index ->
            val entry = entries[index]
            VideoPlayerPage(entry, active && entry.id == currentId, model,
                frameReady = renderedId == entry.id)
        }
    }
}
