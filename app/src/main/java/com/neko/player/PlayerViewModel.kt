package com.neko.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
data class PlayerSettings(
    val shuffle: Boolean = true,
    val autoNext: Boolean = true,
    val crop: Boolean = true,
    val global2x: Boolean = false,
    val backgroundPlay: Boolean = false,
    val coldMute: Boolean = false,
    val theme: AppTheme = AppTheme.SYSTEM,
)

@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("neko_prefs", Application.MODE_PRIVATE)
    private val setupPrefs = application.getSharedPreferences("neko_first_launch", Application.MODE_PRIVATE)
    var settings by mutableStateOf(PlayerSettings(
        shuffle = prefs.getBoolean("isShuffleEnabled", true),
        autoNext = prefs.getBoolean("isAutoNext", true),
        crop = prefs.getInt("resizeMode", AspectRatioFrameLayout.RESIZE_MODE_ZOOM) == AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        global2x = prefs.getBoolean("isGlobal2xSpeed", false),
        backgroundPlay = prefs.getBoolean("isBackgroundPlayAllowed", false),
        coldMute = prefs.getBoolean("isColdStartMuted", false),
        theme = AppTheme.fromPreference(prefs.getString("app_theme", null)),
    ))
        private set
    var videos by mutableStateOf<List<VideoEntry>>(emptyList())
        private set
    var likedIds by mutableStateOf(prefs.getStringSet("liked_video_uris", emptySet()).orEmpty().toSet())
        private set
    var directory by mutableStateOf(prefs.getString("saved_directory_uri", null))
        private set
    var onboardingCompleted by mutableStateOf(setupPrefs.getBoolean("onboarding_completed", false))
        private set
    var directoryReadable by mutableStateOf(false)
        private set
    val directoryLabel: String?
        get() = directory?.let { selected ->
            runCatching {
                val id = DocumentsContract.getTreeDocumentId(selected.toUri())
                if (id.startsWith("primary:")) "内部存储/${id.removePrefix("primary:")}" else id.replace(':', '/')
            }.getOrDefault("已选择的视频目录")
        }
    var scanning by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var activeMode by mutableIntStateOf(0)
        private set
    internal var pendingHomeLaunch by mutableStateOf(true)
        private set
    var currentId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var longPress by mutableStateOf(false)
        private set
    var controller by mutableStateOf<MediaController?>(null)
        private set
    private var foreground = false
    private var playbackPageVisible = true
    internal val frames = VideoFrameCache(application, viewModelScope)
    private var pendingAutoplay = false
    private var requestedUnmute = false
    private var library = emptyList<VideoEntry>()
    private var scanJob: Job? = null
    private var scanGeneration = 0
    private val lifecyclePolicy = ForegroundPlaybackPolicy()
    private val positions = mutableMapOf<String, Long>()
    private val selection = mutableMapOf(
        0 to prefs.getString("saved_video_uri", null),
        1 to prefs.getString("saved_liked_video_uri", null),
    )
    private var future: ListenableFuture<MediaController>? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            currentId = player.currentMediaItem?.mediaId
            isPlaying = player.isPlaying
            currentId?.let { selection[activeMode] = it }
        }
        override fun onPlayerError(error: PlaybackException) {
            message = "无法播放当前视频，已尝试跳过。请检查文件是否损坏或目录权限是否有效。"
        }
    }

    init {
        // Fixed layout replaces all old visual overrides, including restored backups.
        prefs.edit {
            putInt("active_mode", 0)
            remove("blurRadius")
            remove("maskHeightDp")
            remove("headerHPad")
            remove("headerVPad")
        }
        // Picking a folder must never start playback behind the first-launch wizard.
        if (onboardingCompleted) connectPlayer()
        scan()
    }

    private fun connectPlayer() {
        if (future != null) return
        val application = getApplication<Application>()
        val connection = MediaController.Builder(application,
            SessionToken(application, ComponentName(application, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    extras.getString("playback_error")?.let { message = it }
                }
                override fun onDisconnected(controller: MediaController) {
                    this@PlayerViewModel.controller = null
                    isPlaying = false
                    message = "播放服务连接已断开，请重新打开应用。"
                }
            }).buildAsync()
        future = connection
        connection.addListener({
            try {
                controller = connection.get().also {
                    it.addListener(listener)
                    isPlaying = it.isPlaying
                    currentId = it.currentMediaItem?.mediaId
                    if (requestedUnmute) it.volume = 1f
                    it.sessionExtras.getString("playback_error")?.let { error -> message = error }
                }
                applyPlaybackSettings()
                syncQueue()
            } catch (e: Exception) {
                message = "无法连接播放服务，请重新打开应用。"
            }
        }, ContextCompat.getMainExecutor(application))
    }

    fun finishOnboarding() {
        if (onboardingCompleted || scanning) return
        if (!directoryReadable || !hasDirectoryGrant()) {
            directoryReadable = false
            message = "请先选择视频目录并授予读取权限。"
            return
        }
        setupPrefs.edit { putBoolean("onboarding_completed", true) }
        onboardingCompleted = true
        connectPlayer()
    }

    private fun hasDirectoryGrant(): Boolean = directory?.let { selected ->
        getApplication<Application>().contentResolver.persistedUriPermissions.any {
            it.uri.toString() == selected && it.isReadPermission
        }
    } ?: false

    private fun refreshDirectoryAccess() {
        if (directory == null || scanning) return
        if (!hasDirectoryGrant()) {
            directoryReadable = false
        } else scan()
    }

    fun playlist(mode: Int): List<VideoEntry> = if (mode == 0) videos else favoriteVideos(videos, likedIds)
    fun selectedId(mode: Int): String? = if (mode == activeMode) currentId ?: selection[mode] else selection[mode]
    fun dismissMessage() { message = null }

    fun selectDirectory(uri: Uri, grantedFlags: Int) {
        val resolver = getApplication<Application>().contentResolver
        if (grantedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            message = "所选目录没有读取权限，请重新选择。"
            return
        }
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            message = null
            val previous = directory
            directory = uri.toString()
            prefs.edit { putString("saved_directory_uri", directory) }
            if (previous != null && previous != directory) {
                try { resolver.releasePersistableUriPermission(previous.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { /* An already revoked grant needs no cleanup. */ }
            }
            scan()
        } catch (_: SecurityException) {
            message = "无法保留此目录的读取权限，请选择支持持久授权的目录。"
        }
    }

    fun scan() {
        scanJob?.cancel()
        val generation = ++scanGeneration
        directoryReadable = false
        val selected = directory ?: return
        scanning = true
        scanJob = viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    check(hasDirectoryGrant()) { "Missing persistent read permission" }
                    val root = selected.toUri()
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
                    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val result = mutableListOf<VideoEntry>()
                    val resolver = getApplication<Application>().contentResolver
                    val cursor = resolver.query(children, projection, null, null, null)
                        ?: throw java.io.IOException("Directory query failed")
                    cursor.use {
                        while (it.moveToNext()) {
                            val name = it.getString(1).orEmpty()
                            val mime = it.getString(2).orEmpty()
                            if (mime.startsWith("video/") || (mime != DocumentsContract.Document.MIME_TYPE_DIR &&
                                listOf(".mp4", ".mkv", ".webm", ".mov", ".m4v").any { extension -> name.endsWith(extension, true) })) {
                                val id = DocumentsContract.buildDocumentUriUsingTree(root, it.getString(0)).toString()
                                result += VideoEntry(id, name)
                            }
                        }
                    }
                    result
                }
                library = entries
                frames.onLibraryChanged()
                videos = orderVideos(entries, settings.shuffle)
                directoryReadable = true
                scanning = false
                syncQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                library = emptyList()
                videos = emptyList()
                scanning = false
                syncQueue()
                message = "无法读取目录，请检查目录是否仍然存在，或重新授权。"
            } finally { if (generation == scanGeneration) scanning = false }
        }
    }

    fun onAppLaunched() {
        changeMode(0)
        pendingHomeLaunch = true
    }

    internal fun onHomeLaunchHandled() { pendingHomeLaunch = false }

    fun changeMode(mode: Int) {
        if (mode == activeMode) return
        rememberPosition()
        activeMode = mode
        prefs.edit { putInt("active_mode", mode) }
        syncQueue(resetSelection = true)
    }

    fun selectVideo(id: String) {
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId == id) return
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id } ?: return
        rememberPosition()
        player.seekTo(index, positions[id] ?: 0L)
        player.prepare()
        lifecyclePolicy.userAction()
        if (playbackPageVisible && (foreground || settings.backgroundPlay)) player.play()
        currentId = id
    }

    fun toggleLike(id: String) {
        likedIds = if (id in likedIds) likedIds - id else likedIds + id
        prefs.edit { putStringSet("liked_video_uris", likedIds) }
        if (activeMode == 1) syncQueue()
    }

    fun togglePlayback() {
        val player = controller ?: return
        lifecyclePolicy.userAction()
        pendingAutoplay = false
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekToDefaultPosition()
            player.play()
        } else if (player.playWhenReady) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun onForegroundChanged(value: Boolean) {
        foreground = value
        if (value && !onboardingCompleted) refreshDirectoryAccess()
        if (!value) { onLongPressChanged(false); rememberPosition() }
        val player = controller ?: return
        if (value && playbackPageVisible && pendingAutoplay && player.mediaItemCount > 0) {
            pendingAutoplay = false
            player.play()
        }
        when (lifecyclePolicy.update(value && playbackPageVisible, settings.backgroundPlay && playbackPageVisible, player.playWhenReady)) {
            ForegroundPlaybackPolicy.Action.PLAY -> player.play()
            ForegroundPlaybackPolicy.Action.PAUSE -> player.pause()
            ForegroundPlaybackPolicy.Action.NONE -> Unit
        }
    }

    fun onPlaybackPageVisible(visible: Boolean) {
        if (playbackPageVisible == visible) return
        playbackPageVisible = visible
        onLongPressChanged(false)
        onForegroundChanged(foreground)
    }

    fun onLongPressChanged(value: Boolean) {
        longPress = value && !settings.global2x && foreground
        controller?.setPlaybackSpeed(if (settings.global2x || longPress) 2f else 1f)
    }

    fun unmute() { requestedUnmute = true; controller?.volume = 1f }

    fun updateSettings(value: PlayerSettings) {
        val shuffleChanged = settings.shuffle != value.shuffle
        settings = value
        if (value.global2x) longPress = false
        prefs.edit {
            putBoolean("isShuffleEnabled", value.shuffle)
            putBoolean("isAutoNext", value.autoNext)
            putInt("resizeMode", if (value.crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT)
            putBoolean("isGlobal2xSpeed", value.global2x)
            putBoolean("isBackgroundPlayAllowed", value.backgroundPlay)
            putBoolean("isColdStartMuted", value.coldMute)
            putString("app_theme", value.theme.preferenceValue)
        }
        applyPlaybackSettings()
        if (shuffleChanged) { videos = orderVideos(library, value.shuffle); syncQueue() }
    }

    private fun applyPlaybackSettings() {
        controller?.apply {
            repeatMode = if (settings.autoNext) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            setPlaybackSpeed(if (settings.global2x || longPress) 2f else 1f)
        }
    }

    private fun rememberPosition() {
        controller?.let { player ->
            player.currentMediaItem?.mediaId?.let { id ->
                positions[id] = player.currentPosition.coerceAtLeast(0L)
                selection[activeMode] = id
                prefs.edit {
                    putString(if (activeMode == 0) "saved_video_uri" else "saved_liked_video_uri", id)
                    putString("playback_uri", id)
                    putLong("playback_position", player.currentPosition.coerceAtLeast(0L))
                }
            }
        }
    }

    private fun syncQueue(resetSelection: Boolean = false) {
        if (!onboardingCompleted) return
        val player = controller ?: return
        if (scanning && videos.isEmpty()) return
        val target = playlist(activeMode)
        if (target.isEmpty()) { player.clearMediaItems(); currentId = null; return }
        val wasEmpty = player.mediaItemCount == 0
        val previousId = player.currentMediaItem?.mediaId
        val previousIndex = player.currentMediaItemIndex
        fun item(entry: VideoEntry) = MediaItem.Builder().setMediaId(entry.id).setUri(entry.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.name).build()).build()
        if (wasEmpty || resetSelection) {
            val selected = selection[activeMode]
            val index = target.indexOfFirst { it.id == selected }.coerceAtLeast(0)
            val id = target[index].id
            val position = positions[id] ?: if (prefs.getString("playback_uri", null) == id)
                prefs.getLong("playback_position", 0L).coerceAtLeast(0L) else 0L
            player.setMediaItems(target.map(::item), index, position)
            player.prepare()
            if (playbackPageVisible && (foreground || settings.backgroundPlay)) player.play() else pendingAutoplay = true
        } else {
            val existing = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
            val byId = target.associateBy { it.id }
            queueEdits(existing, target.map { it.id }).forEach { edit ->
                when (edit) {
                    is QueueEdit.Add -> player.addMediaItem(edit.index, item(byId.getValue(edit.id)))
                    is QueueEdit.Move -> player.moveMediaItem(edit.from, edit.to)
                    is QueueEdit.RemoveTail -> player.removeMediaItems(edit.from, edit.to)
                }
            }
            if (target.none { it.id == previousId }) {
                player.seekToDefaultPosition(indexAfterRemoval(previousIndex, target.size))
                player.prepare()
            }
        }
        currentId = player.currentMediaItem?.mediaId
        if ((!playbackPageVisible || (!foreground && !settings.backgroundPlay)) && player.playWhenReady) {
            lifecyclePolicy.update(false, false, true)
            player.pause()
        }
    }

    override fun onCleared() {
        rememberPosition()
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        super.onCleared()
    }
}
