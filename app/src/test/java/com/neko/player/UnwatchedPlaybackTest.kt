package com.neko.player

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnwatchedPlaybackTest {
    private val entries = (1..20).map { VideoEntry("$it", "$it.mp4") }

    @Test fun unseenClipsComeFirstWithoutDroppingOrDuplicatingWatchedClips() {
        val watched = setOf("1", "3", "7", "20")
        val ordered = orderVideos(entries, true, watched, random = Random(5))
        assertEquals(entries.toSet(), ordered.toSet())
        assertEquals(entries.size, ordered.size)
        assertEquals(entries.size - watched.size, ordered.takeWhile { it.id !in watched }.size)
        assertEquals(watched, ordered.takeLast(watched.size).map { it.id }.toSet())
    }

    @Test fun disablingPriorityAndHavingNoUnseenClipsBothKeepNormalShuffle() {
        val normal = orderVideos(entries, true, random = Random(42))
        assertEquals(normal, orderVideos(entries, true, setOf("1", "2"), false, Random(42)))
        assertEquals(normal, orderVideos(entries, true, entries.map { it.id }.toSet(), true, Random(42)))
    }

    @Test fun nameOrderIgnoresWatchHistoryAndPriority() {
        assertEquals(orderVideos(entries, false), orderVideos(entries, false, setOf("1", "2")))
    }

    @Test fun coldStartDoesNotSkipUnseenClipsToRestoreWatchedSelection() {
        val queue = listOf(VideoEntry("new", "new.mp4"), VideoEntry("old", "old.mp4"))
        assertEquals(0, initialVideoIndex(queue, "old", setOf("old"), true))
        assertEquals(1, initialVideoIndex(queue, "old", setOf("old"), false))
        assertEquals(1, initialVideoIndex(queue, "old", setOf("old", "new"), true))
    }

    @Test fun previewsAndPausedTimeNeverCountAsWatched() {
        val progress = WatchProgress()
        assertNull(progress.sample("a", 2_000, false, 0))
        assertNull(progress.sample("a", 2_000, false, 10_000))
        assertNull(progress.sample("a", 2_000, true, 20_000))
        assertNull(progress.sample("a", 2_000, true, 20_999))
        assertEquals("a", progress.sample("a", 2_000, true, 21_000))
        assertNull(progress.sample("a", 2_000, true, 25_000))
    }

    @Test fun fastSwipesCannotAccumulateTimeAcrossDifferentClips() {
        val progress = WatchProgress()
        repeat(100) { assertNull(progress.sample("$it", 2_000, true, it * 100L)) }
        assertNull(progress.sample("99", 2_000, true, 10_000))
        assertEquals("99", progress.sample("99", 2_000, true, 10_900))
    }

    @Test fun bufferingAndPauseExcludeTheirElapsedTime() {
        val progress = WatchProgress()
        assertNull(progress.sample("a", 4_000, true, 0))
        assertNull(progress.sample("a", 4_000, false, 800))
        assertNull(progress.sample("a", 4_000, false, 20_000))
        assertNull(progress.sample("a", 4_000, true, 30_000))
        assertNull(progress.sample("a", 4_000, true, 31_199))
        assertEquals("a", progress.sample("a", 4_000, false, 31_200))
    }

    @Test fun speedChangesUseTheSpeedOfEachPlayingInterval() {
        val progress = WatchProgress()
        assertNull(progress.sample("a", 4_000, true, 0, 1f))
        assertNull(progress.sample("a", 4_000, true, 1_000, 2f))
        assertNull(progress.sample("a", 4_000, true, 1_499, 2f))
        assertEquals("a", progress.sample("a", 4_000, true, 1_500, 2f))
    }

    @Test fun longAndUnknownDurationClipsNeedAtMostThreeSecondsOfPlayback() {
        for (duration in listOf(60_000L, -1L, 0L)) {
            val progress = WatchProgress()
            assertNull(progress.sample("a", duration, true, 0))
            assertNull(progress.sample("a", duration, true, 2_999))
            assertEquals("a", progress.sample("a", duration, true, 3_000))
        }
    }
}
