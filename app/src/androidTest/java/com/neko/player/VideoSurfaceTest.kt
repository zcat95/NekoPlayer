package com.neko.player

import android.content.Context
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(UnstableApi::class)
class VideoSurfaceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun clipChangesKeepTheSameTextureAndStillDeliverTheNewFirstFrame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "surface-reuse-test.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("green-video.mp4").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        lateinit var player: ExoPlayer
        lateinit var root: View
        val crop = mutableStateOf(true)
        val rendered = mutableStateOf<String?>(null)
        try {
            rule.runOnIdle {
                player = ExoPlayer.Builder(context).build()
                player.setMediaItems(listOf("first", "second").map {
                    MediaItem.Builder().setMediaId(it).setUri(file.toUri()).build()
                })
                player.prepare()
            }
            rule.setContent {
                val view = LocalView.current
                SideEffect { root = view.rootView }
                VideoPlaybackSurface(player, crop.value, false, { rendered.value = it },
                    Modifier.testTag("video-surface"))
            }
            rule.waitUntil(10_000) { rendered.value == "first" }
            fun textures(view: View): List<TextureView> = when (view) {
                is TextureView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { textures(view.getChildAt(it)) }
                else -> emptyList()
            }
            lateinit var initialTexture: TextureView
            rule.runOnIdle { initialTexture = textures(root).single() }
            rule.runOnIdle { crop.value = false; player.seekToDefaultPosition(1) }
            rule.waitUntil(10_000) { rendered.value == "second" }
            rule.runOnIdle {
                val current = textures(root)
                assertEquals(1, current.size)
                assertSame(initialTexture, current.single())
            }
            val pixels = rule.onNodeWithTag("video-surface").captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            assertTrue("The retained surface must show video pixels", center.green > .35f && center.red < .2f)
        } finally {
            rule.runOnIdle { player.release() }
            file.delete()
        }
    }
}
