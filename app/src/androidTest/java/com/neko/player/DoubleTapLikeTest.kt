package com.neko.player

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DoubleTapLikeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun repeatedDoubleTapsLikeWithoutUnlikingOrChangingCrop() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("double_tap_test_$name", mode)
        }
        val prefs = app.getSharedPreferences("neko_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = ViewModelStore()
        lateinit var model: PlayerViewModel
        val entry = VideoEntry("content://com.neko.player.test.library/tree/a/document/shared", "shared.mp4")
        try {
            rule.runOnIdle { model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[PlayerViewModel::class.java] }
            rule.setContent { Box {
                VideoPlayerPage(entry, true, model, Modifier.fillMaxSize().testTag("video"), loadPreview = false)
                BasicText(if (model.isLiked(entry.id)) "已点赞" else "未点赞", Modifier.testTag("like-state"))
            } }
            repeat(2) {
                rule.onNodeWithTag("video").performTouchInput {
                    down(center); advanceEventTime(30); up()
                    advanceEventTime(80); down(center); advanceEventTime(30); up()
                }
                rule.runOnIdle { assertTrue(model.isLiked(entry.id)); assertEquals(true, model.settings.crop) }
                rule.onNodeWithTag("like-state").assertTextEquals("已点赞")
            }
        } finally {
            rule.runOnIdle { store.clear() }
            prefs.edit().clear().commit()
        }
    }
}
