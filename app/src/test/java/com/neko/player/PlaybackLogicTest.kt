package com.neko.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.neko.player.ForegroundPlaybackPolicy.Action

class PlaybackLogicTest {
    @Test fun lifecyclePauseResumesExactlyOnce() {
        val policy = ForegroundPlaybackPolicy()
        assertEquals(Action.PAUSE, policy.update(false, false, true))
        assertEquals(Action.NONE, policy.update(false, false, false))
        assertEquals(Action.PLAY, policy.update(true, false, false))
        assertEquals(Action.NONE, policy.update(true, false, true))
    }

    @Test fun manuallyPausedVideoDoesNotResumeOnReturn() {
        val policy = ForegroundPlaybackPolicy()
        assertEquals(Action.NONE, policy.update(false, false, false))
        assertEquals(Action.NONE, policy.update(true, false, false))
    }

    @Test fun backgroundPlaybackIsNotInterrupted() {
        val policy = ForegroundPlaybackPolicy()
        assertEquals(Action.NONE, policy.update(false, true, true))
        assertEquals(Action.NONE, policy.update(true, true, true))
    }

    @Test fun explicitUserActionCancelsPendingResume() {
        val policy = ForegroundPlaybackPolicy()
        policy.update(false, false, true)
        policy.userAction()
        assertEquals(Action.NONE, policy.update(true, false, false))
    }

    @Test fun enablingBackgroundPlaybackReleasesLifecyclePause() {
        val policy = ForegroundPlaybackPolicy()
        policy.update(false, false, true)
        assertEquals(Action.PLAY, policy.update(false, true, false))
    }

    @Test fun nameSortDoesNotUseOpaqueDocumentIds() {
        val videos = listOf(VideoEntry("content://provider/999", "Alpha.mp4"),
            VideoEntry("content://provider/1", "zebra.mp4"), VideoEntry("content://provider/2", "beta.mp4"))
        assertEquals(listOf("Alpha.mp4", "beta.mp4", "zebra.mp4"), orderVideos(videos, false).map { it.name })
    }

    @Test fun removingFavoritePreservesAllSurvivingOrder() {
        val shuffled = listOf(VideoEntry("3", "C"), VideoEntry("1", "A"), VideoEntry("2", "B"), VideoEntry("4", "D"))
        assertEquals(listOf("3", "2", "4"), favoriteVideos(shuffled, setOf("2", "3", "4")).map { it.id })
        assertEquals(shuffled, favoriteVideos(shuffled, setOf("1", "2", "3", "4")))
    }

    @Test fun removingCurrentSelectsSuccessorOrPreviousForLastItem() {
        assertEquals(2, indexAfterRemoval(2, 4))
        assertEquals(2, indexAfterRemoval(3, 3))
        assertEquals(0, indexAfterRemoval(0, 0))
    }

    @Test fun shuffleKeepsEveryVideoExactlyOnce() {
        val entries = (1..100).map { VideoEntry("$it", "$it.mp4") }
        val shuffled = orderVideos(entries, true)
        assertEquals(entries.toSet(), shuffled.toSet())
        assertEquals(entries.size, shuffled.size)
        assertFalse(favoriteVideos(entries, emptySet()).isNotEmpty())
    }
}
