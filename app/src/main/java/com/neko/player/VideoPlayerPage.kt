package com.neko.player

import android.content.Context
import android.view.TextureView
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Only video pixels live here; controls and blur output are sibling layers. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerPage(entry: VideoEntry, active: Boolean, model: PlayerViewModel, modifier: Modifier = Modifier) {
    val player = model.controller
    var firstFrame by remember(entry.id, player, active) { mutableStateOf(false) }
    val preview = model.frames.peek(entry.id)
    LaunchedEffect(entry.id) { model.frames.request(entry.id).await() }
    val globalSpeed by rememberUpdatedState(model.settings.global2x)
    val resizeMode = if (model.settings.crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    Box(modifier = modifier.fillMaxSize().background(Color.Black).pointerInput(entry.id, active) {
        if (!active) return@pointerInput
        var held = false
        detectTapGestures(
            onDoubleTap = { model.updateSettings(model.settings.copy(crop = !model.settings.crop)) },
            onTap = { if (!held) model.togglePlayback() },
            onLongPress = {},
            onPress = {
                held = false
                coroutineScope {
                    val job = launch {
                        delay(200)
                        held = true
                        if (!globalSpeed) model.onLongPressChanged(true)
                    }
                    try { tryAwaitRelease() } finally {
                        job.cancel()
                        model.onLongPressChanged(false)
                    }
                }
            },
        )
    }) {
        if (active && player != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> VideoTextureLayout(context).apply { bind(player, entry.id) { firstFrame = true } } },
                update = { view ->
                    view.resizeMode = resizeMode
                    view.keepScreenOn = model.isPlaying
                },
                onRelease = { it.dispose() },
            )
        }
        // Neighbours are already visible during the drag. Replace the preview atomically at first frame.
        if ((!active || !firstFrame) && preview != null) {
            Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = if (model.settings.crop) ContentScale.Crop else ContentScale.Fit)
        }

    }
}

@OptIn(UnstableApi::class)
private class VideoTextureLayout(context: Context) : FrameLayout(context) {
    private var player: Player? = null
    private var onFirstFrame: () -> Unit = {}
    private var expectedId: String? = null
    private val aspect = AspectRatioFrameLayout(context)
    var resizeMode: Int
        get() = aspect.resizeMode
        set(value) { aspect.resizeMode = value }
    private val texture = TextureView(context)
    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() { if (player?.currentMediaItem?.mediaId == expectedId) onFirstFrame() }
        override fun onVideoSizeChanged(videoSize: VideoSize) { updateRatio(videoSize) }
    }
    init {
        addView(aspect, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        aspect.addView(texture, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    fun bind(value: Player, mediaId: String, onFrame: () -> Unit) {
        player = value
        expectedId = mediaId
        onFirstFrame = onFrame
        value.addListener(listener)
        updateRatio(value.videoSize)
        value.setVideoTextureView(texture)
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
