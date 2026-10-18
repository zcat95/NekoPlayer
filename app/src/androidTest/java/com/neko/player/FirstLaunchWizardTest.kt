package com.neko.player

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Runs on an API 31+ device. The directory picker itself remains the Android system UI. */
class FirstLaunchWizardTest {
    @get:Rule val rule = createComposeRule()

    @Test fun directoryPermissionGatesContinuationAndPickerCancellationKeepsThePage() {
        val ready = mutableStateOf(false)
        var pickerRequests = 0
        var finishes = 0
        rule.setContent {
            MiuixTheme {
                FirstLaunchWizard(if (ready.value) "内部存储/Movies" else null, ready.value, false,
                    PlayerSettings(), null, { pickerRequests++ }, {}, {}, { finishes++ })
            }
        }
        rule.onNodeWithContentDescription("继续").performClick()
        rule.onNodeWithText("继续").assertIsNotEnabled()
        rule.onNodeWithText("授权并选择目录").performClick()
        rule.runOnIdle { assertEquals(1, pickerRequests); assertEquals(0, finishes) }
        rule.onNodeWithText("给予权限").assertExists()
        // Successful access to an empty folder is sufficient; no video-count gate.
        rule.runOnIdle { ready.value = true }
        rule.onNodeWithText("继续").performClick()
        rule.onNodeWithText("播放偏好").assertExists()
    }

    @Test fun preferencesSurviveBackNavigationAndSavedStateUntilExplicitFinish() {
        val value = mutableStateOf(PlayerSettings())
        var finishes = 0
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MiuixTheme {
                FirstLaunchWizard("内部存储/Movies", true, false, value.value, null, {},
                    { value.value = it }, {}, { finishes++ })
            }
        }
        rule.onNodeWithContentDescription("继续").performClick()
        rule.onNodeWithText("继续").performClick()
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.onAllNodes(isToggleable())[1].performClick()
        rule.runOnIdle { assertFalse(value.value.autoNext); assertFalse(value.value.shuffle) }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("继续").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("播放偏好").assertExists()
        rule.runOnIdle { assertFalse(value.value.autoNext); assertFalse(value.value.shuffle) }
        rule.onNodeWithText("继续").performClick()
        rule.onNodeWithText("设置完成！").assertExists()
        rule.onNodeWithText("隐私政策").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, finishes) }
        rule.onNodeWithText("开始使用").performClick()
        rule.runOnIdle { assertEquals(1, finishes) }
    }

    @Test fun revokedAccessOnCompletionReturnsToFolderSelection() {
        val ready = mutableStateOf(true)
        var finishes = 0
        rule.setContent {
            MiuixTheme {
                FirstLaunchWizard("内部存储/Movies", ready.value, false, PlayerSettings(), null,
                    {}, {}, {}, { finishes++ })
            }
        }
        rule.onNodeWithContentDescription("继续").performClick()
        rule.onNodeWithText("继续").performClick()
        rule.onNodeWithText("继续").performClick()
        rule.runOnIdle { ready.value = false }
        rule.onNodeWithText("重新选择目录").performClick()
        rule.onNodeWithText("给予权限").assertExists()
        rule.onNodeWithText("继续").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, finishes) }
    }

    @Test fun viewModelRequiresDurableReadAccessAndPersistsPreferencesBeforeCompletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("first_launch_test", mode)
        }
        val prefs = app.getSharedPreferences("neko_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val stores = mutableListOf<ViewModelStore>()
        try {
            rule.runOnIdle {
                fun model(): PlayerViewModel {
                    val store = ViewModelStore().also(stores::add)
                    return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[PlayerViewModel::class.java]
                }
                val first = model()
                assertFalse(first.onboardingCompleted)
                assertNull(first.controller)
                first.selectDirectory(Uri.parse("content://missing/tree/videos"), 0)
                first.finishOnboarding()
                assertFalse(first.onboardingCompleted)
                assertFalse(prefs.getBoolean("onboarding_completed", false))
                assertNull(first.controller)
                assertTrue(first.message != null)
                first.updateSettings(first.settings.copy(autoNext = false, shuffle = false))
                val restored = model()
                assertFalse(restored.settings.autoNext)
                assertFalse(restored.settings.shuffle)
                assertFalse(restored.onboardingCompleted)
                assertNull(restored.controller)
            }
        } finally {
            rule.runOnIdle { stores.forEach(ViewModelStore::clear) }
            prefs.edit().clear().commit()
        }
    }
}
