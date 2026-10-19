package com.neko.player

import java.util.Locale
import kotlin.random.Random

/** IDs are provider URIs; display names must never be inferred from those opaque IDs. */
data class VideoEntry(val id: String, val name: String, val identity: String = id, val sourceDirectory: String? = null)

/** Overlapping tree grants can expose the same document through different playable URIs. */
fun mergeVideos(libraries: List<List<VideoEntry>>): List<VideoEntry> =
    libraries.flatten().distinctBy { it.identity }

internal fun previewCacheBudget(memoryClassMb: Int, lowRam: Boolean): Int =
    (if (lowRam) 8 else (memoryClassMb / 16).coerceIn(8, 24)) * 1024 * 1024

/** Current frame first, then the next two clips and the previous clip; no wrap or invalid indices. */
fun previewIndices(current: Int, size: Int, direction: Int = 1, target: Int = current,
                   rapid: Boolean = false): List<Int> {
    if (size <= 0) return emptyList()
    val center = current.coerceIn(0, size - 1)
    val landing = target.coerceIn(0, size - 1)
    val step = if (direction < 0) -1 else 1
    val candidates = if (rapid) listOf(landing, center, landing + step)
        else listOf(center, landing, center + step, center - step, center + 2 * step)
    return candidates.filter { it in 0 until size }.distinct()
}

internal data class PreloadWindow(val indices: List<Int>, val scrolling: Boolean)

/** Page crossing intervals approximate swipe speed without per-pixel work on the UI thread. */
internal class SwipePreloadPolicy {
    private var previousPage: Int? = null
    private var previousCrossingMs: Long? = null
    private var direction = 1
    private var rapidUntilMs = 0L

    fun update(current: Int, target: Int, size: Int, scrolling: Boolean, nowMs: Long): PreloadWindow {
        val previous = previousPage
        if (previous != null && previous != current) {
            direction = if (current > previous) 1 else -1
            previousCrossingMs?.let { if (nowMs - it in 0..180) rapidUntilMs = nowMs + 300 }
            previousCrossingMs = nowMs
        }
        if (target != current) direction = if (target > current) 1 else -1
        previousPage = current
        return PreloadWindow(previewIndices(current, size, direction, target,
            scrolling && nowMs < rapidUntilMs), scrolling)
    }
}

fun orderVideos(
    videos: List<VideoEntry>,
    shuffle: Boolean,
    watchedIds: Set<String> = emptySet(),
    preferUnwatched: Boolean = true,
    random: Random = Random.Default,
): List<VideoEntry> {
    if (!shuffle) return videos.sortedWith(
        compareBy<VideoEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
    )
    val shuffled = videos.shuffled(random)
    if (!preferUnwatched) return shuffled
    val (unwatched, watched) = shuffled.partition { it.id !in watchedIds }
    return unwatched + watched
}

/** A cold start must not skip the unwatched portion to restore a previously watched selection. */
internal fun initialVideoIndex(entries: List<VideoEntry>, selectedId: String?, watchedIds: Set<String>,
                               preferUnwatched: Boolean): Int {
    val selected = entries.indexOfFirst { it.id == selectedId }
    if (preferUnwatched && selectedId in watchedIds && entries.any { it.id !in watchedIds }) return 0
    return selected.coerceAtLeast(0)
}

/** Counts actual playing time, including speed, independently of seek position and preview requests. */
internal class WatchProgress {
    private var currentId: String? = null
    private var playedMs = 0.0
    private var lastTimeMs = 0L
    private var wasPlaying = false
    private var lastSpeed = 1f
    private var reported = false

    fun sample(id: String?, durationMs: Long, playing: Boolean, nowMs: Long, speed: Float = 1f): String? {
        if (id != currentId) {
            currentId = id
            playedMs = 0.0
            reported = false
        } else if (wasPlaying) {
            playedMs += (nowMs - lastTimeMs).coerceAtLeast(0) * lastSpeed.toDouble()
        }
        lastTimeMs = nowMs
        wasPlaying = playing
        lastSpeed = speed
        val threshold = if (durationMs > 0) (durationMs / 2).coerceIn(1, 3_000) else 3_000
        return if (id != null && !reported && playedMs >= threshold) {
            reported = true
            id
        } else null
    }
}

/** Filtering an ordered library preserves the order of every surviving favourite. */
fun favoriteVideos(videos: List<VideoEntry>, likedIds: Set<String>): List<VideoEntry> =
    videos.filter { it.id in likedIds }

fun indexAfterRemoval(oldIndex: Int, remainingCount: Int): Int =
    oldIndex.coerceIn(0, (remainingCount - 1).coerceAtLeast(0))

/** A lifecycle pause is resumable; a user pause is not. */
class ForegroundPlaybackPolicy {
    enum class Action { NONE, PLAY, PAUSE }
    private var resumeOnForeground = false

    fun userAction() { resumeOnForeground = false }

    fun update(foreground: Boolean, backgroundAllowed: Boolean, playWhenReady: Boolean): Action {
        if (!foreground && !backgroundAllowed) {
            resumeOnForeground = resumeOnForeground || playWhenReady
            return if (playWhenReady) Action.PAUSE else Action.NONE
        }
        if ((foreground || backgroundAllowed) && resumeOnForeground) {
            resumeOnForeground = false
            return Action.PLAY
        }
        return Action.NONE
    }
}

sealed interface QueueEdit {
    data class Add(val index: Int, val id: String) : QueueEdit
    data class Move(val from: Int, val to: Int) : QueueEdit
    data class RemoveTail(val from: Int, val to: Int) : QueueEdit
}

/** Preserve existing media items, especially the currently decoding item, across list edits. */
fun queueEdits(existing: List<String>, target: List<String>): List<QueueEdit> {
    val queue = existing.toMutableList()
    val edits = mutableListOf<QueueEdit>()
    target.forEachIndexed { index, id ->
        val found = queue.indexOf(id)
        when {
            found < 0 -> {
                queue.add(index, id)
                edits += QueueEdit.Add(index, id)
            }
            found != index -> {
                queue.add(index, queue.removeAt(found))
                edits += QueueEdit.Move(found, index)
            }
        }
    }
    if (queue.size > target.size) edits += QueueEdit.RemoveTail(target.size, queue.size)
    return edits
}
