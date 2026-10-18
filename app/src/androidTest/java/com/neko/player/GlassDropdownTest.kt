package com.neko.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class GlassDropdownTest {
    @get:Rule val rule = createComposeRule()

    @Test fun menuRemainsTranslucentWithoutDimmingThePageAndDismissesOutside() {
        val background = mutableStateOf(Color.Red)
        val selected = mutableIntStateOf(0)
        rule.setContent {
            MiuixTheme {
                Box(Modifier.fillMaxSize().testTag("glass-stage")) {
                    Scaffold {
                        val backdrop = rememberLayerBackdrop()
                        CompositionLocalProvider(LocalSettingsBackdrop provides backdrop) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().testTag("glass-source").layerBackdrop(backdrop).background(background.value))
                                Box(Modifier.padding(top = 120.dp, start = 16.dp, end = 16.dp)) {
                                    GlassDropdownPreference("主题模式", listOf("跟随系统", "浅色", "深色"), selected.intValue,
                                        Modifier.testTag("glass-trigger"), onSelectedIndexChange = { selected.intValue = it })
                                }
                            }
                        }
                    }
                }
            }
        }
        val stage = rule.onNodeWithTag("glass-stage")
        val stageBounds = stage.fetchSemanticsNode().boundsInRoot
        val source = rule.onNodeWithTag("glass-source").fetchSemanticsNode().boundsInRoot
        val sampleX = (source.left - stageBounds.left + 8).toInt()
        val sampleY = (source.top - stageBounds.top + 8).toInt()
        val before = stage.captureToImage().toPixelMap()[sampleX, sampleY]
        rule.onNodeWithTag("glass-trigger").performClick()
        rule.onNodeWithTag("glass-theme-menu").assertIsDisplayed()
        val outside = stage.captureToImage().toPixelMap()[sampleX, sampleY]
        assertTrue("Opening the menu must not dim the page", kotlin.math.abs(before.red - outside.red) < .03f)
        fun menuSample(): Color {
            val pixels = rule.onNodeWithTag("glass-theme-menu").captureToImage().toPixelMap()
            return pixels[pixels.width - 16, pixels.height / 2]
        }
        val overRed = menuSample()
        rule.runOnIdle { background.value = Color.Blue }
        val overBlue = menuSample()
        assertTrue("The glass reveals changes behind it", overRed.red - overBlue.red > .05f && overBlue.blue - overRed.blue > .05f)
        rule.onNodeWithTag("glass-menu-host").performTouchInput { click(Offset(8f, 8f)) }
        rule.onNodeWithTag("glass-theme-menu").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, selected.intValue) }
    }
}
