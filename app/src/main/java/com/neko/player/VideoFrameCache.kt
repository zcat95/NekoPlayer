package com.neko.player

import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded decoded first frames, requested before neighbours enter the viewport. Main-thread API. */
internal class VideoFrameCache(private val context: Context, private val scope: CoroutineScope) {
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val diagnostics = PerformanceDiagnostics.get(context)
    private val cache = object : LruCache<String, Bitmap>(previewCacheBudget(activity.memoryClass, activity.isLowRamDevice)) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pending = mutableMapOf<String, Deferred<Bitmap?>>()
    private val failed = mutableSetOf<String>()
    private val decoders = Semaphore(1)
    private var revision by mutableIntStateOf(0)

    val bytes: Long get() { revision; return cache.size().toLong() }

    fun peek(id: String): Bitmap? { revision; return cache.get(id) }

    fun request(id: String): Deferred<Bitmap?> {
        cache.get(id)?.let { return CompletableDeferred(it) }
        if (id in failed) return CompletableDeferred(null)
        pending[id]?.takeUnless { it.isCancelled }?.let { return it }
        val task = scope.async(start = CoroutineStart.LAZY) {
            val frame = withContext(Dispatchers.IO) {
                decoders.withPermit {
                    runCatching {
                        MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(context, id.toUri())
                            retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 960)
                        }
                    }.getOrNull()
                }
            }
            if (frame != null) { cache.put(id, frame); diagnostics.previewBytes(cache.size().toLong()); revision++ } else failed += id
            frame
        }
        pending[id] = task
        task.invokeOnCompletion { if (pending[id] === task) pending.remove(id) }
        task.start()
        return task
    }

    fun prefetch(entries: List<VideoEntry>, currentId: String?) {
        val current = entries.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        prefetch(entries, previewIndices(current, entries.size))
    }

    fun prefetch(entries: List<VideoEntry>, indices: List<Int>) {
        val wanted = indices.mapNotNull { entries.getOrNull(it)?.id }.distinct()
        // Rapid swipes must not leave old decodes queued ahead of the current viewport.
        pending.entries.toList().filter { it.key !in wanted }.forEach { it.value.cancel() }
        wanted.forEach(::request)
    }

    fun clear() {
        cancelPending()
        cache.evictAll()
        diagnostics.previewBytes(0)
        failed.clear()
        revision++
    }

    fun cancelPending() { pending.values.toList().forEach { it.cancel() } }

    fun onLibraryChanged() { failed.clear() }
}
