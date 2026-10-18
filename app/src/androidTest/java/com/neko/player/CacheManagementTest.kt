package com.neko.player

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class CacheManagementTest {
    @Test fun clearingPlaybackCachePreservesTheOriginalVideoAndAllowsFuturePreloads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "cache-management-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getCacheDir() = folder }
        val source = File(folder, "original.mp4").apply { writeBytes(ByteArray(512 * 1024) { 42 }) }
        val cache = PlaybackMediaCache(isolated)
        fun waitForCache() {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (cache.bytes == 0L && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertTrue("Speculative read must populate the cache", cache.bytes > 0L)
        }
        try {
            cache.preload(listOf(source.toUri().toString()))
            waitForCache()
            assertEquals(0L, cache.clear().get(5, TimeUnit.SECONDS))
            assertEquals(0L, cache.bytes)
            assertEquals(512L * 1024, source.length())
            assertTrue(source.readBytes().all { it == 42.toByte() })
            cache.preload(listOf(source.toUri().toString()))
            waitForCache()
        } finally {
            cache.close()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SimpleCache.isCacheFolderLocked(File(folder, "video-buffer")) &&
                SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            folder.deleteRecursively()
        }
    }
}
