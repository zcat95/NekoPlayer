package com.neko.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEditingTest {
    private class Media(val id: String, var position: Long = 0)
    private fun apply(queue: MutableList<Media>, target: List<String>) {
        queueEdits(queue.map { it.id }, target).forEach { edit ->
            when (edit) {
                is QueueEdit.Add -> queue.add(edit.index, Media(edit.id))
                is QueueEdit.Move -> queue.add(edit.to, queue.removeAt(edit.from))
                is QueueEdit.RemoveTail -> queue.subList(edit.from, edit.to).clear()
            }
        }
    }
    @Test fun removingCurrentFavoriteSelectsNextWithoutRecreatingSurvivors() {
        val queue = mutableListOf(Media("a"), Media("b"), Media("c", 1234), Media("d"))
        val next = queue[2]
        val oldCurrentIndex = 1
        apply(queue, listOf("a", "c", "d"))
        assertTrue(queue[indexAfterRemoval(oldCurrentIndex, queue.size)] === next)
        assertEquals(1234L, next.position)
        assertEquals(listOf("a", "c", "d"), queue.map { it.id })
    }
    @Test fun reorderingAndAddingDoesNotReplacePlayingMediaOrItsPosition() {
        val playing = Media("b", 9876)
        val queue = mutableListOf(Media("a"), playing, Media("c"))
        apply(queue, listOf("c", "new", "b", "a"))
        assertEquals(listOf("c", "new", "b", "a"), queue.map { it.id })
        assertTrue(queue[2] === playing)
        assertEquals(9876L, queue[2].position)
    }
    @Test fun noEditsForUnchangedPlaylist() {
        assertTrue(queueEdits(listOf("a", "b"), listOf("a", "b")).isEmpty())
    }
    @Test fun clearingPlaylistAndAddingFirstItemWork() {
        val queue = mutableListOf(Media("a"), Media("b"))
        apply(queue, emptyList())
        assertTrue(queue.isEmpty())
        apply(queue, listOf("new"))
        assertEquals(listOf("new"), queue.map { it.id })
    }
}
