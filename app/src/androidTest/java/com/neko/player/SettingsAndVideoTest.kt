package com.neko.player

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

class SettingsAndVideoTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fixedVisualOptionsAreRemovedFromSettings() {
        rule.setContent {
            MiuixTheme { Scaffold {
                SettingsContent(PlayerSettings(), "内部存储/Movies", 3, 1, {}, {}, {})
            } }
        }
        rule.onNodeWithText("关于").performScrollTo().assertExists()
        listOf("模糊强度", "视觉效果", "遮罩高度", "标签水平边距", "标签垂直边距").forEach {
            rule.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test fun nextClipIsVisibleBeforeBecomingActiveAndWhileItsPlayerBinds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val video = File(context.cacheDir, "green-preview-test.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("green-video.mp4").use { input ->
            video.outputStream().use { output -> input.copyTo(output) }
        }
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("video_preview_test", mode)
        }
        val prefs = app.getSharedPreferences("neko_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().putFloat("maskHeightDp", 120f).putFloat("headerHPad", 80f).putFloat("headerVPad", 160f).commit()
        val store = ViewModelStore()
        lateinit var model: PlayerViewModel
        val active = mutableStateOf(false)
        val entry = VideoEntry(video.toUri().toString(), "green.mp4")
        try {
            rule.runOnIdle {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[PlayerViewModel::class.java]
                assertTrue(!prefs.contains("maskHeightDp"))
                assertTrue(!prefs.contains("headerHPad"))
                assertTrue(!prefs.contains("headerVPad"))
                model.frames.prefetch(listOf(entry), entry.id)
            }
            rule.waitUntil(10_000) { model.frames.peek(entry.id) != null }
            rule.setContent {
                VideoPlayerPage(entry, active.value, model, Modifier.fillMaxSize().testTag("next-clip"))
            }
            fun assertGreenFrame() {
                val pixels = rule.onNodeWithTag("next-clip").captureToImage().toPixelMap()
                val pixel = pixels[pixels.width / 2, pixels.height / 2]
                assertTrue("Preloaded video frame must cover the page", pixel.green > .35f && pixel.red < .2f && pixel.blue < .2f)
            }
            assertGreenFrame()
            rule.runOnIdle { assertNull(model.controller); active.value = true }
            assertGreenFrame()
        } finally {
            rule.runOnIdle { store.clear() }
            prefs.edit().clear().commit()
            video.delete()
        }
    }
}
