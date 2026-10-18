package com.neko.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Requires an API 31+ device; validates real GraphicsLayer compositing. */
class MiuixRenderingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun gradientErasesBlurOutputRatherThanOnlyItsEmptyContent() {
        rule.setContent {
            val source = rememberLayerBackdrop()
            Box(Modifier.size(100.dp).testTag("stage")) {
                Box(Modifier.fillMaxSize().layerBackdrop(source).background(Color.White))
                // This foreground is deliberately excluded from the sampling layer.
                Box(Modifier.fillMaxSize().background(Color.Red))
                Box(Modifier.fillMaxSize().hyperProgressiveBlur(source))
            }
        }
        rule.waitForIdle()
        val pixels = rule.onNodeWithTag("stage").captureToImage().toPixelMap()
        val upper = pixels[pixels.width / 2, pixels.height / 10]
        val lower = pixels[pixels.width / 2, pixels.height - 2]
        assertTrue("The header samples the white source", upper.green > 0.7f)
        assertTrue("The faded edge reveals the red foreground", lower.green < 0.2f && lower.red > 0.8f)
    }

    @Test fun bottomSheetRemainsUntilDismissAnimationFinishes() {
        val shown = mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MiuixTheme {
                Scaffold {
                    OverlayBottomSheet(show = shown.value, title = "测试设置",
                        onDismissRequest = { shown.value = false }) { Text("面板内容") }
                }
            }
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("面板内容").assertExists()
        rule.runOnIdle { shown.value = false }
        rule.mainClock.advanceTimeBy(16)
        rule.onNodeWithText("面板内容").assertExists()
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("面板内容").assertDoesNotExist()
    }
}
