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
    @Test fun openingSettingsPausesAndReturningResumesThePlayingClip() {
        val policy = ForegroundPlaybackPolicy()
        // The settings route disallows playback even if background playback is enabled.
        assertEquals(Action.PAUSE, policy.update(false, false, true))
        assertEquals(Action.NONE, policy.update(false, false, false))
        assertEquals(Action.PLAY, policy.update(true, true, false))
        assertEquals(Action.NONE, policy.update(true, true, true))
    }
}
