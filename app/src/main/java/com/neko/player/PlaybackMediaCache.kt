package com.neko.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Shared by ExoPlayer and a single bounded neighbour-read worker. No second playing decoder. */
@OptIn(UnstableApi::class)
internal class PlaybackMediaCache(context: Context) {
    private val database = StandaloneDatabaseProvider(context)
    private val cache = SimpleCache(File(context.cacheDir, "video-buffer"), LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024), database)
    val factory: CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    @Volatile private var writer: CacheWriter? = null
    private var requested = emptyList<String>()

    fun preload(uris: List<String>) {
        if (uris == requested) return
        requested = uris
        val request = generation.incrementAndGet()
        writer?.cancel()
        worker.execute {
            for (uri in uris) {
                if (generation.get() != request) break
                val current = CacheWriter(factory.createDataSource(),
                    DataSpec.Builder().setUri(uri.toUri()).setLength(2L * 1024 * 1024).build(), ByteArray(64 * 1024), null)
                writer = current
                if (generation.get() != request) { current.cancel(); break }
                runCatching { current.cache() } // Missing/revoked files are reported only if actually played.
                writer = null
            }
        }
    }

    fun close() {
        generation.incrementAndGet()
        writer?.cancel()
        worker.execute { cache.release(); database.close() }
        worker.shutdown()
    }
}
