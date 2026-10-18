package com.neko.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import top.yukonga.miuix.kmp.theme.MiuixTheme

class PlayerTopControlsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun speedAppearsOnlyWhileAcceleratingAndMirrorsTheSettingsButton() {
        val accelerated = mutableStateOf(false)
        var settingsRequests = 0
        rule.setContent {
            MiuixTheme {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.width(320.dp).height(180.dp).testTag("top-controls-stage")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.White))
                    PlayerTopControls(backdrop, false, accelerated.value, { settingsRequests++ })
                }
            }
        }
        rule.onNodeWithContentDescription("2 倍速播放").assertDoesNotExist()
        rule.onNodeWithTag("global-settings").assertIsDisplayed()
        rule.runOnIdle { accelerated.value = true }
        val speed = rule.onNodeWithContentDescription("2 倍速播放").assertIsDisplayed()
        speed.assert(hasClickAction().not())
        val speedBounds = speed.fetchSemanticsNode().boundsInRoot
        val gear = rule.onNodeWithTag("global-settings").fetchSemanticsNode().boundsInRoot
        val stage = rule.onNodeWithTag("top-controls-stage").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(speedBounds.top - gear.top) < 2)
        assertTrue(kotlin.math.abs(speedBounds.width - gear.width) < 2)
        assertTrue(kotlin.math.abs(speedBounds.height - gear.height) < 2)
        assertTrue("The two circles mirror each other", kotlin.math.abs((speedBounds.center.x + gear.center.x) / 2 - stage.center.x) < 2)
        rule.runOnIdle { accelerated.value = false }
        rule.onNodeWithContentDescription("2 倍速播放").assertDoesNotExist()
        rule.onNodeWithTag("global-settings").performClick()
        rule.runOnIdle { assertEquals(1, settingsRequests) }
    }
}
