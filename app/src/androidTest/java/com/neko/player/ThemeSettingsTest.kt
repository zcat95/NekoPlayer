package com.neko.player

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ThemeSettingsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun themeSelectionOverridesOrFollowsSystemAndSurvivesModelRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("theme_settings_test", mode)
        }
        val prefs = app.getSharedPreferences("neko_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var store = ViewModelStore()
        val model = mutableStateOf<PlayerViewModel?>(null)
        val systemDark = mutableStateOf(false)
        var renderedDark = false
        fun createModel() = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[PlayerViewModel::class.java]
        fun assertDark(expected: Boolean) { rule.runOnIdle { assertEquals(expected, renderedDark) } }
        fun select(label: String) {
            rule.onNodeWithTag("theme-mode").performScrollTo().performClick()
            rule.onNodeWithText(label).performClick()
        }
        try {
            rule.runOnIdle { model.value = createModel() }
            rule.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (systemDark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    val current = checkNotNull(model.value)
                    NekoTheme(current.settings.theme) {
                        val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
                        SideEffect { renderedDark = dark }
                        Scaffold {
                            SettingsContent(current.settings, null, 0, 0, current::updateSettings, {}, {})
                        }
                    }
                }
            }
            assertDark(false)
            rule.runOnIdle { systemDark.value = true }
            assertDark(true)
            select("浅色")
            assertDark(false)
            rule.runOnIdle { systemDark.value = false }
            assertDark(false)
            select("深色")
            assertDark(true)
            rule.runOnIdle {
                assertEquals("dark", prefs.getString("app_theme", null))
                store.clear()
                store = ViewModelStore()
                model.value = createModel()
                assertEquals(AppTheme.DARK, model.value!!.settings.theme)
            }
            assertDark(true)
            select("跟随系统")
            assertDark(false)
            rule.runOnIdle { systemDark.value = true }
            assertDark(true)
            rule.runOnIdle { assertEquals("system", prefs.getString("app_theme", null)) }
        } finally {
            rule.runOnIdle { store.clear() }
            prefs.edit().clear().commit()
        }
    }
}
