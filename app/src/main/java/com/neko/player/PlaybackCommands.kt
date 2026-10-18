package com.neko.player

/** App-only session commands; never offered to external media controllers. */
internal object PlaybackCommands {
    const val PREFETCH = "com.neko.player.PREFETCH"
    const val CACHE_SIZE = "com.neko.player.CACHE_SIZE"
    const val CLEAR_CACHE = "com.neko.player.CLEAR_CACHE"
    const val IDS = "ids"
    const val SCROLLING = "scrolling"
    const val BYTES = "bytes"
}
