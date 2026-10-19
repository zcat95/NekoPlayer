package com.neko.player

import android.content.Context
import androidx.core.content.edit

/** Shared with the UI, but written only by the service after actual playback. No playback positions. */
internal class WatchHistory(context: Context) {
    val preferences = context.getSharedPreferences("neko_watch_history", Context.MODE_PRIVATE)

    init {
        val saved = preferences.getStringSet("watched_ids", emptySet()).orEmpty().toSet()
        val migrated = saved + saved.map(::mediaIdentity)
        if (migrated != saved) preferences.edit { putStringSet("watched_ids", migrated) }
    }

    fun watchedIds(): Set<String> = preferences.getStringSet("watched_ids", emptySet()).orEmpty().toSet()

    fun isWatched(id: String?): Boolean {
        if (id == null) return false
        val watched = preferences.getStringSet("watched_ids", emptySet()).orEmpty()
        return id in watched || mediaIdentity(id) in watched
    }

    fun markWatched(id: String) {
        val watched = watchedIds()
        if (!isWatched(id)) preferences.edit { putStringSet("watched_ids", watched + id + mediaIdentity(id)) }
    }
}
