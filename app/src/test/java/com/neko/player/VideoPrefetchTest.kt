package com.neko.player

import org.junit.Assert.assertEquals
import org.junit.Test
import com.neko.player.ForegroundPlaybackPolicy.Action

class VideoPrefetchTest {
    @Test fun upcomingAndPreviousClipsAreReadyBeforeTheGesture() {
        assertEquals(listOf(4, 5, 3, 6), previewIndices(4, 10))
    }
    @Test fun preloadWindowDoesNotWrapOrDuplicateBoundaryClips() {
        assertEquals(listOf(0, 1, 2), previewIndices(0, 10))
        assertEquals(listOf(9, 8), previewIndices(9, 10))
        assertEquals(listOf(0), previewIndices(0, 1))
        assertEquals(emptyList<Int>(), previewIndices(0, 0))
    }
    @Test fun selectionFromAnOldPlaylistIsClampedAfterRemoval() {
        assertEquals(listOf(2, 1), previewIndices(5, 3))
        assertEquals(listOf(0, 1), previewIndices(-1, 2))
    }
    @Test fun upwardSwipesPrepareEarlierClipsBeforeLaterClips() {
        assertEquals(listOf(5, 4, 6, 3), previewIndices(5, 10, direction = -1))
        assertEquals(listOf(5, 4, 6, 3), previewIndices(5, 10, direction = -1, target = 4))
    }
    @Test fun rapidSwipesPreferTheLandingPageAndLimitSpeculation() {
        assertEquals(listOf(7, 5, 8), previewIndices(5, 10, target = 7, rapid = true))
        assertEquals(listOf(3, 5, 2), previewIndices(5, 10, direction = -1, target = 3, rapid = true))
        assertEquals(listOf(0, 1), previewIndices(1, 10, direction = -1, target = 0, rapid = true))
    }
    @Test fun fastPageCrossingsShrinkTheWindowAndSettlingRestoresIt() {
        val policy = SwipePreloadPolicy()
        policy.update(2, 3, 20, true, 0)
        policy.update(3, 4, 20, true, 80)
        val fast = policy.update(4, 5, 20, true, 160)
        assertEquals(listOf(5, 4, 6), fast.indices)
        assertEquals(true, fast.scrolling)
        assertEquals(listOf(4, 5, 3, 6), policy.update(4, 4, 20, false, 170).indices)
        assertEquals(false, policy.update(4, 4, 20, false, 170).scrolling)
    }
    @Test fun openingSettingsPausesAndReturningResumesThePlayingClip() {
        val policy = ForegroundPlaybackPolicy()
        // The settings route disallows playback even if background playback is enabled.
        assertEquals(Action.PAUSE, policy.update(false, false, true))
        assertEquals(Action.NONE, policy.update(false, false, false))
        assertEquals(Action.PLAY, policy.update(true, true, false))
        assertEquals(Action.NONE, policy.update(true, true, true))
    }
}
