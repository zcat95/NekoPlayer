package com.neko.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.json.JSONArray
import org.json.JSONObject

/** Owns the sole playing decoder and media notification, independently of Activity/Compose. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaCache: PlaybackMediaCache
    private lateinit var watchHistory: WatchHistory
    private val watchProgress = WatchProgress()
    private var preferredPreloadIds: List<String>? = null
    private var preloadSuspended = false
    private val watchCheckpoint = object : Runnable {
        override fun run() {
            watchProgress.sample(player.currentMediaItem?.mediaId, player.duration, player.isPlaying,
                SystemClock.elapsedRealtime(), player.playbackParameters.speed)?.let(watchHistory::markWatched)
            if (player.isPlaying && !watchHistory.isWatched(player.currentMediaItem?.mediaId))
                handler.postDelayed(this, 250)
        }
    }
    private val preloadNeighbours = Runnable {
        // Give the current clip's first frame priority over speculative disk reads.
        if (player.playbackState != Player.STATE_READY || preloadSuspended) return@Runnable
        val current = player.currentMediaItemIndex
        val indices = preferredPreloadIds?.mapNotNull { id ->
            (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id }
        } ?: previewIndices(current, player.mediaItemCount)
        val uris = indices.filter { it != current }
            .mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri?.toString() }
        mediaCache.preload(uris)
    }
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val failedIds = mutableSetOf<String>()
    private val prefs by lazy { getSharedPreferences("neko_prefs", MODE_PRIVATE) }
    private val queueCheckpoint = Runnable { saveQueue() }
    private val checkpoint = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        watchHistory = WatchHistory(this)
        mediaCache = PlaybackMediaCache(this)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(mediaCache.factory))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 250, 500).build())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.repeatMode = if (prefs.getBoolean("isAutoNext", true)) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        player.volume = if (prefs.getBoolean("isColdStartMuted", false)) 0f else 1f
        player.setPlaybackSpeed(if (prefs.getBoolean("isGlobal2xSpeed", false)) 2f else 1f)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                handler.removeCallbacks(watchCheckpoint)
                watchCheckpoint.run()
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // Coalesce a shuffle's individual move commands into one preferences write.
                handler.removeCallbacks(queueCheckpoint)
                handler.postDelayed(queueCheckpoint, 250)
                handler.removeCallbacks(preloadNeighbours)
                handler.postDelayed(preloadNeighbours, 150)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                savePosition()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) preferredPreloadIds = null
                mediaCache.preload(emptyList())
                handler.removeCallbacks(preloadNeighbours)
                handler.postDelayed(preloadNeighbours, 150)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(checkpoint)
                if (isPlaying) handler.post(checkpoint) else savePosition()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    handler.removeCallbacks(preloadNeighbours)
                    handler.postDelayed(preloadNeighbours, 150)
                    player.currentMediaItem?.mediaId?.let(failedIds::remove)
                    if (session?.sessionExtras?.isEmpty == false) session?.setSessionExtras(Bundle.EMPTY)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                session?.setSessionExtras(Bundle().apply {
                    putString("playback_error", "无法播放当前视频，已尝试跳过。请检查文件是否损坏或目录权限是否有效。")
                    putLong("error_time", android.os.SystemClock.elapsedRealtime())
                })
                player.currentMediaItem?.mediaId?.let(failedIds::add)
                // Never cycle indefinitely when every file is corrupt or a grant was revoked.
                val next = (player.currentMediaItemIndex + 1 until player.mediaItemCount)
                    .firstOrNull { player.getMediaItemAt(it).mediaId !in failedIds }
                if (next != null) {
                    player.seekToDefaultPosition(next)
                    player.prepare()
                } else {
                    player.pause()
                }
            }
        })
        val activityIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    if (controller.packageName == packageName) {
                        listOf(PlaybackCommands.PREFETCH, PlaybackCommands.CACHE_SIZE, PlaybackCommands.CLEAR_CACHE)
                            .forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
                    }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands.build()).build()
                }

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (controller.packageName != packageName)
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    return when (customCommand.customAction) {
                        PlaybackCommands.PREFETCH -> {
                            preferredPreloadIds = args.getStringArrayList(PlaybackCommands.IDS)?.take(4)
                            preloadSuspended = args.getBoolean(PlaybackCommands.SCROLLING)
                            handler.removeCallbacks(preloadNeighbours)
                            mediaCache.preload(emptyList())
                            if (!preloadSuspended) handler.postDelayed(preloadNeighbours, 150)
                            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        PlaybackCommands.CACHE_SIZE -> Futures.immediateFuture(cacheResult(mediaCache.bytes))
                        PlaybackCommands.CLEAR_CACHE -> {
                            handler.removeCallbacks(preloadNeighbours)
                            Futures.transform(mediaCache.clear(), { bytes -> cacheResult(bytes ?: 0L) }, MoreExecutors.directExecutor())
                        }
                        else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                }

                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    if (!prefs.getBoolean("isBackgroundPlayAllowed", false)) {
                        return Futures.immediateFailedFuture(IllegalStateException("Background playback is disabled"))
                    }
                    val items = restoreQueue()
                    val index = items.indexOfFirst { it.mediaId == prefs.getString("playback_uri", null) }.coerceAtLeast(0)
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                        items, index, prefs.getLong("playback_position", 0L).coerceAtLeast(0L)))
                }
            }).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun cacheResult(bytes: Long) = SessionResult(SessionResult.RESULT_SUCCESS,
        Bundle().apply { putLong(PlaybackCommands.BYTES, bytes) })

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!prefs.getBoolean("isBackgroundPlayAllowed", false) || !player.playWhenReady) {
            player.pause()
            stopSelf()
        }
    }

    private fun savePosition() {
        val item = player.currentMediaItem ?: return
        val key = if (prefs.getInt("active_mode", 0) == 0) "saved_video_uri" else "saved_liked_video_uri"
        prefs.edit {
            putString("playback_uri", item.mediaId)
            putLong("playback_position", player.currentPosition.coerceAtLeast(0L))
            putString(key, item.mediaId)
        }
    }

    private fun saveQueue() {
        val queue = JSONArray()
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            queue.put(JSONObject().put("id", item.mediaId).put("name", item.mediaMetadata.title?.toString().orEmpty()))
        }
        prefs.edit { putString("playback_queue", queue.toString()) }
    }

    private fun restoreQueue(): List<MediaItem> = try {
        val queue = JSONArray(prefs.getString("playback_queue", "[]"))
        List(queue.length()) { index ->
            val entry = queue.getJSONObject(index)
            MediaItem.Builder().setMediaId(entry.getString("id")).setUri(entry.getString("id"))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.getString("name")).build()).build()
        }
    } catch (_: org.json.JSONException) { emptyList() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        savePosition()
        saveQueue()
        session?.release()
        session = null
        player.release()
        mediaCache.close()
        super.onDestroy()
    }
}
