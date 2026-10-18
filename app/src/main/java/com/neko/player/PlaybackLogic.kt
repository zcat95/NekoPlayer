package com.neko.player

import java.util.Locale

/** IDs are provider URIs; display names must never be inferred from those opaque IDs. */
data class VideoEntry(val id: String, val name: String)

/** Current frame first, then the next two clips and the previous clip; no wrap or invalid indices. */
fun previewIndices(current: Int, size: Int): List<Int> {
    if (size <= 0) return emptyList()
    val center = current.coerceIn(0, size - 1)
    return listOf(center, center + 1, center - 1, center + 2).filter { it in 0 until size }.distinct()
}

fun orderVideos(videos: List<VideoEntry>, shuffle: Boolean): List<VideoEntry> =
    if (shuffle) videos.shuffled() else videos.sortedWith(
        compareBy<VideoEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
    )

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
