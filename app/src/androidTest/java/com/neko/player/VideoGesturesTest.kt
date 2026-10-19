package com.neko.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class VideoGesturesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun longHoldGivesOneHapticAndDoesNotPreventThePagerFromTakingOver() {
        val accelerated = mutableStateOf(false)
        var haptics = 0
        var taps = 0
        var settledPage = 0
        rule.setContent {
            val pager = rememberPagerState { 2 }
            settledPage = pager.settledPage
            VerticalPager(pager, Modifier.fillMaxSize()) { page ->
                Box(Modifier.fillMaxSize().testTag("gesture-$page")
                    .videoGestures(page, true, false, { taps++ }, {}, {
                        val started = it && !accelerated.value
                        accelerated.value = it
                        started
                    }, { haptics++ }))
            }
        }
        rule.onNodeWithTag("gesture-0").performTouchInput { down(Offset(center.x, height * .85f)) }
        rule.waitUntil(2_000) { accelerated.value }
        rule.mainClock.advanceTimeBy(700)
        rule.runOnIdle { assertEquals(1, haptics) }
        rule.onNodeWithTag("gesture-0").performTouchInput {
            advanceEventTime(700)
            moveTo(Offset(center.x, height * .1f), delayMillis = 300)
            up()
        }
        rule.waitUntil(3_000) { settledPage == 1 }
        rule.runOnIdle { assertFalse(accelerated.value); assertEquals(1, haptics); assertEquals(0, taps) }
    }

    @Test fun touchCancellationAndLeavingThePlaybackPageRestoreSpeed() {
        val accelerated = mutableStateOf(false)
        val active = mutableStateOf(true)
        rule.setContent {
            Box(Modifier.fillMaxSize().testTag("gesture")
                .videoGestures("clip", active.value, false, {}, {}, {
                    val started = it && !accelerated.value
                    accelerated.value = it
                    started
                }, {}))
        }
        rule.onNodeWithTag("gesture").performTouchInput { down(center) }
        rule.waitUntil(2_000) { accelerated.value }
        rule.onNodeWithTag("gesture").performTouchInput { cancel() }
        rule.waitUntil(2_000) { !accelerated.value }
        rule.onNodeWithTag("gesture").performTouchInput { down(center) }
        rule.waitUntil(2_000) { accelerated.value }
        rule.runOnIdle { active.value = false }
        rule.waitUntil(2_000) { !accelerated.value }
        rule.onNodeWithTag("gesture").performTouchInput { cancel() }
    }

    @Test fun doubleTapDoesNotTriggerPauseAndSingleTapDoesNotTriggerLike() {
        var pauses = 0
        var likes = 0
        rule.setContent {
            Box(Modifier.fillMaxSize().testTag("gesture")
                .videoGestures("clip", true, false, { pauses++ }, { likes++ }, { false }, {}))
        }
        rule.onNodeWithTag("gesture").performTouchInput {
            down(center); advanceEventTime(30); up()
            advanceEventTime(80); down(center); advanceEventTime(30); up()
        }
        rule.waitUntil(2_000) { likes == 1 }
        rule.runOnIdle { assertEquals(0, pauses) }
        rule.onNodeWithTag("gesture").performTouchInput {
            advanceEventTime(500); down(center); advanceEventTime(30); up()
        }
        rule.waitUntil(2_000) { pauses == 1 }
        rule.runOnIdle { assertEquals(1, likes) }
    }

    @Test fun horizontalPageSwipeCancelsAnActiveHoldAsWell() {
        val accelerated = mutableStateOf(false)
        var settled = 0
        rule.setContent {
            val pager = rememberPagerState { 2 }
            settled = pager.settledPage
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                Box(Modifier.fillMaxSize().testTag("horizontal-$page").videoGestures(page, true, false, {}, {}, {
                    val started = it && !accelerated.value
                    accelerated.value = it
                    started
                }, {}))
            }
        }
        rule.onNodeWithTag("horizontal-0").performTouchInput { down(Offset(width * .85f, center.y)) }
        rule.waitUntil(2_000) { accelerated.value }
        rule.onNodeWithTag("horizontal-0").performTouchInput {
            moveTo(Offset(width * .1f, center.y), delayMillis = 300); up()
        }
        rule.waitUntil(3_000) { settled == 1 }
        rule.runOnIdle { assertFalse(accelerated.value) }
    }
}
