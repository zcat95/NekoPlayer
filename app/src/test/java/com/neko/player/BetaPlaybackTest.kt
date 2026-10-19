package com.neko.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BetaPlaybackTest {
    @Test fun mergedFoldersDeduplicateStableDocumentsWithoutChangingPlayableUris() {
        val first = VideoEntry("content://provider/tree/a/document/shared", "same.mp4", "document:provider:shared")
        val alias = VideoEntry("content://provider/tree/b/document/shared", "same.mp4", "document:provider:shared")
        val separate = VideoEntry("content://provider/tree/b/document/other", "same.mp4", "document:provider:other")
        assertEquals(listOf(first, separate), mergeVideos(listOf(listOf(first), listOf(alias, separate))))
        assertEquals(listOf(alias, separate), mergeVideos(listOf(emptyList(), listOf(alias, separate))))
        assertEquals(emptyList<VideoEntry>(), mergeVideos(emptyList()))
    }

    @Test fun previewMemoryBudgetScalesWithinBounds() {
        assertEquals(8 * 1024 * 1024, previewCacheBudget(512, true))
        assertEquals(8 * 1024 * 1024, previewCacheBudget(64, false))
        assertEquals(16 * 1024 * 1024, previewCacheBudget(256, false))
        assertEquals(24 * 1024 * 1024, previewCacheBudget(1024, false))
    }

    @Test fun replacingAnOldFolderKeepsItsPositionAndDoesNotDuplicateExistingFolders() {
        val entries = listOf(VideoDirectory("a"), VideoDirectory("b"), VideoDirectory("c", false))
        assertEquals(listOf("new", "b", "c"), replaceDirectory(entries, "new", "a").map { it.uri })
        assertEquals(listOf("a", "c"), replaceDirectory(entries, "c", "b").map { it.uri })
        assertEquals(true, replaceDirectory(entries, "c", "b").last().enabled)
        assertEquals(4, replaceDirectory(entries, "new", null).size)
    }

    @Test fun firstFramePercentilesUseNearestRankAndHandleMissingData() {
        assertNull(percentile95(emptyList()))
        assertEquals(42L, percentile95(listOf(42)))
        assertEquals(95L, percentile95((100L downTo 1L).toList()))
        assertEquals(1000L, percentile95(listOf(10, 20, 1000)))
    }

    @Test fun cpuPercentageSupportsMultipleCoresAndRejectsInvalidIntervals() {
        assertNull(cpuPercent(10, 0))
        assertNull(cpuPercent(-1, 100))
        assertEquals(25.0, cpuPercent(250, 1000)!!, .001)
        assertEquals(150.0, cpuPercent(1500, 1000)!!, .001)
    }
}
