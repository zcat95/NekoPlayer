package com.neko.player

import android.content.Context
import android.view.TextureView
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon

/** Only video pixels live here; controls and blur output are sibling layers. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerPage(entry: VideoEntry, active: Boolean, model: PlayerViewModel,
                    modifier: Modifier = Modifier, frameReady: Boolean = false, loadPreview: Boolean = true) {
    val preview = model.frames.peek(entry.id)
    LaunchedEffect(entry.id, loadPreview) { if (loadPreview) model.frames.request(entry.id).await() }
    val view = LocalView.current
    var likePulse by remember(entry.id) { mutableIntStateOf(0) }
    var showLike by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(likePulse) {
        if (likePulse > 0) { showLike = true; delay(550); showLike = false }
    }
    Box(modifier = modifier.fillMaxSize()
        .background(if (active && frameReady) Color.Transparent else Color.Black)
        .videoGestures(entry.id, active, model.settings.global2x,
            onDoubleTap = {
                if (model.likeVideo(entry.id)) view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                likePulse++
            },
            onTap = model::togglePlayback, onAcceleration = model::onLongPressChanged)) {
        // Neighbours are already visible during the drag. Replace the preview atomically at first frame.
        if ((!active || !frameReady) && preview != null) {
            Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = if (model.settings.crop) ContentScale.Crop else ContentScale.Fit)
        }
        AnimatedVisibility(showLike && active, modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(100)) + scaleIn(tween(160), initialScale = .7f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 1.08f)) {
            Icon(painterResource(R.drawable.ic_favorite), contentDescription = "已点赞",
                tint = Color.White.copy(alpha = .95f), modifier = Modifier.size(88.dp).testTag("double-tap-like-feedback"))
        }
    }
}

/** One native rendering view per active feed. Changing clips must not recreate its texture/surface. */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoPlaybackSurface(player: Player, crop: Boolean, playing: Boolean,
                                  onFirstFrame: (String) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context -> VideoTextureLayout(context) },
        update = { view ->
            view.resizeMode = if (crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            view.keepScreenOn = playing
            view.bind(player, onFirstFrame)
        },
        onRelease = { it.dispose() },
    )
}

@OptIn(UnstableApi::class)
private class VideoTextureLayout(context: Context) : FrameLayout(context) {
    private var player: Player? = null
    private var onFirstFrame: (String) -> Unit = {}
    private val aspect = AspectRatioFrameLayout(context)
    var resizeMode: Int
        get() = aspect.resizeMode
        set(value) { aspect.resizeMode = value }
    private val texture = TextureView(context)
    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() { player?.currentMediaItem?.mediaId?.let(onFirstFrame) }
        override fun onVideoSizeChanged(videoSize: VideoSize) { updateRatio(videoSize) }
    }
    init {
        addView(aspect, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        aspect.addView(texture, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    fun bind(value: Player, onFrame: (String) -> Unit) {
        val changed = player !== value
        if (changed) dispose()
        onFirstFrame = onFrame
        if (changed) {
            player = value
            value.addListener(listener)
            updateRatio(value.videoSize)
            value.setVideoTextureView(texture)
        }
    }
    private fun updateRatio(size: VideoSize) {
        if (size.width > 0 && size.height > 0) aspect.setAspectRatio(size.width.toFloat() * size.pixelWidthHeightRatio / size.height)
    }
    fun dispose() {
        keepScreenOn = false
        player?.removeListener(listener)
        player?.clearVideoTextureView(texture)
        player = null
        onFirstFrame = {}
    }
}
