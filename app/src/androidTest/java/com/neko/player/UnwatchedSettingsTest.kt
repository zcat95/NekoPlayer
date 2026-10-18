package com.neko.player

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class UnwatchedSettingsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun preferenceSurvivesRecreationAndOnlyAppliesToShuffle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("unwatched_settings_test_$name", mode)
        }
        val prefs = app.getSharedPreferences("neko_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val stores = mutableListOf<ViewModelStore>()
        fun createModel(): PlayerViewModel {
            val store = ViewModelStore().also(stores::add)
            return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[PlayerViewModel::class.java]
        }
        val model = mutableStateOf<PlayerViewModel?>(null)
        try {
            rule.runOnIdle { model.value = createModel(); assertTrue(model.value!!.settings.preferUnwatched) }
            rule.setContent {
                MiuixTheme { Scaffold {
                    val current = checkNotNull(model.value)
                    SettingsContent(current.settings, null, 0, 0, current::updateSettings, {}, {})
                } }
            }
            rule.onNodeWithTag("prefer-unwatched").performScrollTo().performClick()
            rule.runOnIdle {
                assertFalse(model.value!!.settings.preferUnwatched)
                assertFalse(prefs.getBoolean("preferUnwatched", true))
                stores.last().clear()
                model.value = createModel()
                assertFalse(model.value!!.settings.preferUnwatched)
            }
            rule.onNodeWithText("随机播放").performScrollTo().performClick()
            rule.onNodeWithTag("prefer-unwatched").performScrollTo().assertIsNotEnabled()
            rule.runOnIdle { assertFalse(model.value!!.settings.shuffle) }
            rule.onNodeWithText("随机播放").performScrollTo().performClick()
            rule.onNodeWithTag("prefer-unwatched").performScrollTo().performClick()
            rule.runOnIdle { assertTrue(model.value!!.settings.preferUnwatched) }
        } finally {
            rule.runOnIdle { stores.forEach(ViewModelStore::clear) }
            prefs.edit().clear().commit()
            app.getSharedPreferences("neko_watch_history", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
