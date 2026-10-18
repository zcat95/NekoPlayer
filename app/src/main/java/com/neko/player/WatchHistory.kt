package com.neko.player

import android.content.Context
import androidx.core.content.edit

/** Shared with the UI, but written only by the service after actual playback. No playback positions. */
internal class WatchHistory(context: Context) {
    val preferences = context.getSharedPreferences("neko_watch_history", Context.MODE_PRIVATE)

    fun watchedIds(): Set<String> = preferences.getStringSet("watched_ids", emptySet()).orEmpty().toSet()

    fun isWatched(id: String?): Boolean = id != null &&
        preferences.getStringSet("watched_ids", emptySet()).orEmpty().contains(id)

    fun markWatched(id: String) {
        val watched = watchedIds()
        if (id !in watched) preferences.edit { putStringSet("watched_ids", watched + id) }
    }
}
