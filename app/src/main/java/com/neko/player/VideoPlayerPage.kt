package com.neko.player

import android.content.Context
import android.net.Uri
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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

    // State for tap behaviors
    var isLongPressActive by remember { mutableStateOf(false) }
    var speedChangedInPress by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            // Evaluated during initial boot, but we will dynamically adjust it below
        }
    }

    DisposableEffect(uri) {
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        onDispose {
            exoPlayer.release()
        }
    }

    // Play/Pause only when visible
    DisposableEffect(isVisible) {
        exoPlayer.playWhenReady = isVisible
        onDispose {
            if (!isVisible) {
                exoPlayer.playWhenReady = false
            }
        }
    }
    
    // Auto Next or Repeat One
    LaunchedEffect(isAutoNext) {
        exoPlayer.repeatMode = if (isAutoNext) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }
    
    // Mute Switch Logic
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    // Lifecycle Observer for background auto-pause (only pauses if background play disallowed)
    DisposableEffect(lifecycleOwner, isBackgroundPlayAllowed) {
        val observer = LifecycleEventObserver { _, event ->
            if (!isBackgroundPlayAllowed) {
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                    exoPlayer.playWhenReady = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // React to playback speed changes
    val activePlaybackSpeed = if (isGlobal2xSpeed || isLongPressActive) 2f else 1f
    LaunchedEffect(activePlaybackSpeed) {
        exoPlayer.setPlaybackSpeed(activePlaybackSpeed)
    }

    // Listen for end of video
    DisposableEffect(exoPlayer, isVisible, isAutoNext) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && isVisible && isAutoNext) {
                    onVideoEnded()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onToggleResizeMode()
                        },
                        onTap = {
                            if (!speedChangedInPress) {
                                if (exoPlayer.isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                }
                            }
                        },
                        onPress = {
                            speedChangedInPress = false
                            val job = CoroutineScope(Dispatchers.Main).launch {
                                delay(200) // Threshold before entering long press mode
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
                        onLongPress = {
                            // Suppresses default onTap action after long press timeout is natively reached
                        }
                    )
                },
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.resizeMode = resizeMode
                    player = exoPlayer
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
                playerView.resizeMode = resizeMode
            }
        )
        
        // Action Buttons Column
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 96.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Like Button Minimalist Approach
            IconButton(onClick = { onToggleLike(!isLiked) }) {
                Icon(
                    imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (isLiked) Color.Red else Color.White,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Settings Button
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
