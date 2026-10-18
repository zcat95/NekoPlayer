package com.neko.player

import android.content.Context
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
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pending = mutableMapOf<String, Deferred<Bitmap?>>()
    private val failed = mutableSetOf<String>()
    private val decoders = Semaphore(1)
    private var revision by mutableIntStateOf(0)

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
                            retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 1280)
                        }
                    }.getOrNull()
                }
            }
            if (frame != null) { cache.put(id, frame); revision++ } else failed += id
            frame
        }
        pending[id] = task
        task.invokeOnCompletion { if (pending[id] === task) pending.remove(id) }
        task.start()
        return task
    }

    fun prefetch(entries: List<VideoEntry>, currentId: String?) {
        val current = entries.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val wanted = previewIndices(current, entries.size).map { entries[it].id }
        // Rapid swipes must not leave old decodes queued ahead of the current viewport.
        pending.entries.toList().filter { it.key !in wanted }.forEach { it.value.cancel() }
        wanted.forEach(::request)
    }

    fun onLibraryChanged() { failed.clear() }
}
