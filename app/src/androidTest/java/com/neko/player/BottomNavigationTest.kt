package com.neko.player

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class BottomNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun twoPieceFooterIsCentredAndLikeTogglesItsAccessibleState() {
        val liked = mutableStateOf(false)
        val selected = mutableIntStateOf(0)
        rule.setContent {
            MiuixTheme {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.width(320.dp).height(180.dp).testTag("footer-stage")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.White))
                    PlayerBottomBar(selected, backdrop, false, true, liked.value,
                        { liked.value = !liked.value }, {}, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        rule.onNodeWithTag("floating-like").assertIsNotSelected().performClick()
        rule.onNodeWithTag("floating-like").assertIsSelected().performClick()
        rule.onNodeWithTag("floating-like").assertIsNotSelected()
        val like = rule.onNodeWithTag("floating-like").fetchSemanticsNode().boundsInRoot
        val navigation = rule.onNodeWithTag("floating-navigation-body").fetchSemanticsNode().boundsInRoot
        val group = rule.onNodeWithTag("floating-control-group").fetchSemanticsNode().boundsInRoot
        val stage = rule.onNodeWithTag("footer-stage").fetchSemanticsNode().boundsInRoot
        val capsule = rule.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue("The complete two-piece group is centred", kotlin.math.abs(group.center.x - stage.center.x) < 2)
        assertTrue("The capsule and circle have equal height", kotlin.math.abs(capsule.height - like.height) < 2)
        val minimum = with(rule.density) { 48.dp.toPx() }
        assertTrue(like.width >= minimum - 1 && like.height >= minimum - 1)
        assertTrue("The like action is to the right, without overlapping the tabs", navigation.right < like.left)
        assertTrue("Both capsules share a vertical centre", kotlin.math.abs(like.center.y - navigation.center.y) < 2)
    }

    @Test fun settingsAndAboutHideAllPlaybackControlsAndReturningRestoresTheFooter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("footer_navigation_test", mode)
        }
        val prefs = app.getSharedPreferences("neko_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("active_mode", 1).commit()
        val store = ViewModelStore()
        lateinit var model: PlayerViewModel
        try {
            rule.runOnIdle { model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[PlayerViewModel::class.java] }
            rule.runOnIdle {
                assertEquals(0, model.activeMode)
                assertEquals(0, prefs.getInt("active_mode", -1))
            }
            rule.setContent { MiuixTheme { Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { AppScreen(model) {} } } }
            rule.onNodeWithText("选择本地视频目录开始播放").assertIsDisplayed()
            val homeBar = rule.onNodeWithTag("floating-navigation-body").fetchSemanticsNode().boundsInRoot
            fun assertBarMatchesHome() {
                val bar = rule.onNodeWithTag("floating-navigation-body").fetchSemanticsNode().boundsInRoot
                assertTrue("The footer stays at the same height and position", kotlin.math.abs(bar.top - homeBar.top) < 2 && kotlin.math.abs(bar.height - homeBar.height) < 2)
            }
            rule.onNodeWithContentDescription("打开设置").assertDoesNotExist()
            rule.onNodeWithText("全部视频").assertDoesNotExist()
            rule.onNodeWithText("已收藏视频").assertDoesNotExist()
            rule.onNodeWithTag("playback-speed").assertDoesNotExist()
            rule.onNodeWithTag("floating-like").assertIsNotEnabled()
            rule.onNode(hasText("设置") and hasClickAction()).assertDoesNotExist()
            rule.onNode(hasText("收藏") and hasClickAction()).performClick()
            rule.onNodeWithText("暂无收藏视频，在首页点赞后会显示在这里。").assertIsDisplayed()
            rule.runOnIdle { assertEquals(1, model.activeMode) }
            assertBarMatchesHome()
            rule.runOnIdle { model.onAppLaunched() }
            rule.onNodeWithText("选择本地视频目录开始播放").assertIsDisplayed()
            rule.runOnIdle { assertEquals(0, model.activeMode) }
            rule.onNode(hasText("收藏") and hasClickAction()).performClick()
            rule.onNodeWithText("暂无收藏视频，在首页点赞后会显示在这里。").assertIsDisplayed()
            rule.onNodeWithTag("main-pages").performTouchInput { swipeLeft() }
            rule.onNodeWithText("暂无收藏视频，在首页点赞后会显示在这里。").assertIsDisplayed()
            rule.onNodeWithTag("global-settings").performClick()
            rule.onNodeWithText("数据与存储").assertIsDisplayed()
            rule.onNodeWithTag("floating-like").assertDoesNotExist()
            rule.onNodeWithTag("floating-control-group").assertDoesNotExist()
            rule.onNodeWithTag("global-settings").assertDoesNotExist()
            rule.onNodeWithTag("playback-speed").assertDoesNotExist()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText("暂无收藏视频，在首页点赞后会显示在这里。").assertIsDisplayed()
            assertBarMatchesHome()
            rule.onNode(hasText("首页") and hasClickAction()).performClick()
            rule.onNodeWithText("选择本地视频目录开始播放").assertIsDisplayed()
            rule.runOnIdle { assertEquals(0, model.activeMode) }
            rule.onNodeWithTag("global-settings").performClick()
            rule.onNodeWithText("数据与存储").assertIsDisplayed()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText("选择本地视频目录开始播放").assertIsDisplayed()
            assertBarMatchesHome()
            rule.onNodeWithTag("global-settings").performClick()
            rule.onNodeWithTag("settings-list").performScrollToNode(hasText("关于"))
            rule.onNodeWithText("关于").performClick()
            rule.onNodeWithText("zcat95").assertExists()
            rule.onNodeWithTag("floating-control-group").assertDoesNotExist()
            rule.onNodeWithTag("floating-like").assertDoesNotExist()
            rule.onNodeWithTag("global-settings").assertDoesNotExist()
            rule.onNodeWithTag("playback-speed").assertDoesNotExist()
            rule.runOnIdle { model.onAppLaunched() }
            rule.onNodeWithText("选择本地视频目录开始播放").assertIsDisplayed()
        } finally {
            rule.runOnIdle { store.clear() }
            prefs.edit().clear().commit()
        }
    }
}
