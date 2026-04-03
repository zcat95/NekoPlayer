package com.neko.player

import android.net.Uri
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.textureBlur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerPage(
    uri: Uri,
    isVisible: Boolean,
    isLiked: Boolean,
    onToggleLike: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    isAutoNext: Boolean,
    isGlobal2xSpeed: Boolean,
    isBackgroundPlayAllowed: Boolean,
    isMuted: Boolean,
    resizeMode: Int,
    onToggleResizeMode: () -> Unit,
    onVideoEnded: () -> Unit,
    onLongPressStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isLongPressActive by remember { mutableStateOf(false) }
    var speedChangedInPress by remember { mutableStateOf(false) }
    var isFirstFrameRendered by remember { mutableStateOf(false) }

    // ✅ 创建一次，生命周期与 composable 绑定——不在 URI 变更时重建
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    // ✅ 仅在离开 composition 时 release，不在 URI 变更时 release
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // ✅ URI 变更：仅更新媒体项，不销毁 / 重建播放器
    LaunchedEffect(uri) {
        isFirstFrameRendered = false
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
    }

    // 可见性控制播放
    LaunchedEffect(isVisible) {
        exoPlayer.playWhenReady = isVisible
    }

    // 自动续播 / 单曲循环
    LaunchedEffect(isAutoNext) {
        exoPlayer.repeatMode = if (isAutoNext) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }

    // 全局静音
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    // 后台暂停
    DisposableEffect(lifecycleOwner, isBackgroundPlayAllowed) {
        val observer = LifecycleEventObserver { _, event ->
            if (!isBackgroundPlayAllowed) {
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                    exoPlayer.playWhenReady = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 倍速
    val activePlaybackSpeed = if (isGlobal2xSpeed || isLongPressActive) 2f else 1f
    LaunchedEffect(activePlaybackSpeed) {
        exoPlayer.setPlaybackSpeed(activePlaybackSpeed)
    }

    // 视频事件（结束、首帧）
    DisposableEffect(exoPlayer, isVisible, isAutoNext) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isFirstFrameRendered = true
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && isVisible && isAutoNext) {
                    onVideoEnded()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onToggleResizeMode() },
                        onTap = {
                            if (!speedChangedInPress) {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            }
                        },
                        onPress = {
                            speedChangedInPress = false
                            val job = CoroutineScope(Dispatchers.Main).launch {
                                delay(200)
                                if (!isGlobal2xSpeed) {
                                    isLongPressActive = true
                                    onLongPressStateChanged(true)
                                }
                                speedChangedInPress = true
                            }
                            try {
                                tryAwaitRelease()
                            } finally {
                                job.cancel()
                                if (isLongPressActive) {
                                    isLongPressActive = false
                                    onLongPressStateChanged(false)
                                }
                            }
                        },
                        onLongPress = { /* 已由 onPress 处理，此处留空防止默认行为 */ }
                    )
                },
            factory = { ctx ->
                // ✅ 使用纯 TextureView —— Compose GraphicsLayer 可捕捉其像素用于模糊
                // ✅ AspectRatioFrameLayout 处理宽高比 / resizeMode，替代 PlayerView 的内部逻辑
                val container = AspectRatioFrameLayout(ctx).apply {
                    this.resizeMode = resizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val textureView = TextureView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                container.addView(textureView)

                // 将 TextureView 绑定到 ExoPlayer
                exoPlayer.setVideoTextureView(textureView)

                // 监听视频尺寸变化，同步 AspectRatio 到容器
                exoPlayer.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            container.setAspectRatio(
                                videoSize.width.toFloat() / videoSize.height
                            )
                        }
                    }
                })
                container
            },
            update = { container ->
                // 响应 resizeMode 设置变化
                container.resizeMode = resizeMode
            }
        )

        // 第一帧占位符，纯黑背景
        androidx.compose.animation.AnimatedVisibility(
            visible = !isFirstFrameRendered,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        // 操作按鈕层——独立 composable 隔离重组，点赞状态变化不波及 AndroidView
        ActionOverlay(
            isLiked = isLiked,
            onToggleLike = onToggleLike,
            onOpenSettings = onOpenSettings
        )
    }
}

/**
 * 操作按鈕浮层 — 独立 composable 节点。
 * 当 isLiked 变化时，只有此函数重组；并不会导致父级 Box 或 AndroidView 的 update lambda 被调用。
 */
@Composable
private fun ActionOverlay(
    isLiked: Boolean,
    onToggleLike: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 112.dp, end = 32.dp)
                .pixelShift(2f)
                .background(
                    color = Color.Black.copy(alpha = 0.2f),
                    shape = CircleShape
                )
                .clip(CircleShape)
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(onClick = { onToggleLike(!isLiked) }) {
                Icon(
                    imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (isLiked) Color.Red else Color.White,
                    modifier = Modifier.padding(4.dp).fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.padding(4.dp).fillMaxSize()
                )
            }
        }
    }
}

/**
 * 像素偏移防烧屏机制（Pixel Shifting）
 * 通过极慢的微距偏移，防止高亮静态元素长时间点亮同一批像素。
 */
fun Modifier.pixelShift(maxOffsetPx: Float = 2f): Modifier = composed {
    val infiniteTransition = rememberInfiniteTransition(label = "pixel_shift")
    
    val offsetX by infiniteTransition.animateFloat(
        initialValue = -maxOffsetPx,
        targetValue = maxOffsetPx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11000), 
            repeatMode = RepeatMode.Reverse
        ),
        label = "x_shift"
    )
    
    val offsetY by infiniteTransition.animateFloat(
        initialValue = -maxOffsetPx,
        targetValue = maxOffsetPx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 13000), 
            repeatMode = RepeatMode.Reverse
        ),
        label = "y_shift"
    )

    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(offsetX.toInt(), offsetY.toInt())
            }
        }
    )
}
