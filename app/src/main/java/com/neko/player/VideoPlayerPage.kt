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

    // ✅ 创建一次，生命周期与 composable 绑定——不在 URI 变更时重建
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    // ✅ 仅在离开 composition 时 release，不在 URI 变更时 release
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // ✅ URI 变更：仅更新媒体项，不销毁 / 重建播放器
    LaunchedEffect(uri) {
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

    // 视频结束事件
    DisposableEffect(exoPlayer, isVisible, isAutoNext) {
        val listener = object : Player.Listener {
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

        // 右下角操作按钮 — padding 避开大圆角屏幕（小米 17 等）
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 112.dp, end = 32.dp),  // end=32dp 远离边缘圆角
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(onClick = { onToggleLike(!isLiked) }) {
                Icon(
                    imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (isLiked) Color.Red else Color.White,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
