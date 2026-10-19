package com.neko.player

import android.app.Application
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
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
    val preferUnwatched: Boolean = true,
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
    private val directoryStore = DirectoryStore(prefs)
    private val videoLibrary = VideoLibrary(application)
    private var knownGrants = application.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    private val watchHistory = WatchHistory(application)
    private var watchedIds = watchHistory.watchedIds()
    private val historyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // Do not reorder a running feed when a clip crosses the watched threshold.
        watchedIds = watchHistory.watchedIds()
    }
    var settings by mutableStateOf(PlayerSettings(
        shuffle = prefs.getBoolean("isShuffleEnabled", true),
        preferUnwatched = prefs.getBoolean("preferUnwatched", true),
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
    private var likedKeys by mutableStateOf(likedIds.map(::mediaIdentity).toSet())
    private var favorites by mutableStateOf<List<VideoEntry>>(emptyList())
    internal var directories by mutableStateOf(directoryStore.load())
        private set
    val directory: String? get() = directories.firstOrNull { it.enabled }?.uri ?: directories.firstOrNull()?.uri
    var onboardingCompleted by mutableStateOf(setupPrefs.getBoolean("onboarding_completed", false))
        private set
    var directoryReadable by mutableStateOf(false)
        private set
    val directoryLabel: String?
        get() = directory?.let { com.neko.player.directoryLabel(it) }
    val directorySummary: String? get() = if (directories.size > 1)
        "${directories.count { it.enabled }} / ${directories.size} 个目录参与播放" else directoryLabel
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
    private var foreground by mutableStateOf(false)
    private var playbackPageVisible by mutableStateOf(true)
    internal val previewsAllowed: Boolean get() = foreground && playbackPageVisible
    internal val diagnostics = PerformanceDiagnostics.get(application)
    var diagnosticsExportStatus by mutableStateOf<String?>(null)
        private set
    internal val frames = VideoFrameCache(application, viewModelScope)
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() { frames.clear() }
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) frames.clear()
        }
    }
    var playbackCacheBytes by mutableStateOf<Long?>(null)
        private set
    var clearingCache by mutableStateOf(false)
        private set
    var cacheStatus by mutableStateOf<String?>(null)
        private set
    private var preloadIds = emptyList<String>()
    private var preloadScrolling = false
    private var pendingAutoplay = false
    private var requestedUnmute = false
    private var library = emptyList<VideoEntry>()
    private var scanJob: Job? = null
    private var scanGeneration = 0
    private val lifecyclePolicy = ForegroundPlaybackPolicy()
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
        application.registerComponentCallbacks(memoryCallbacks)
        watchHistory.preferences.registerOnSharedPreferenceChangeListener(historyListener)
        // Fixed layout replaces all old visual overrides, including restored backups.
        prefs.edit {
            putInt("active_mode", 0)
            remove("blurRadius")
            remove("maskHeightDp")
            remove("headerHPad")
            remove("headerVPad")
            remove("playback_position")
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
                sendPreloadWindow()
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

    private fun hasDirectoryGrant(): Boolean {
        val granted = getApplication<Application>().contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        return directories.any { it.enabled && it.readable && it.uri in granted }
    }

    private fun refreshDirectoryAccess() {
        if (directory == null || scanning) return
        if (!hasDirectoryGrant()) {
            directoryReadable = false
        } else scan()
    }

    fun playlist(mode: Int): List<VideoEntry> = if (mode == 0) videos else favorites
    fun isLiked(id: String?): Boolean = id != null && mediaIdentity(id) in likedKeys
    fun selectedId(mode: Int): String? = if (mode == activeMode) currentId ?: selection[mode] else selection[mode]
    fun dismissMessage() { message = null }

    fun selectDirectory(uri: Uri, grantedFlags: Int, replaceUri: String? = null) {
        val resolver = getApplication<Application>().contentResolver
        if (grantedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            message = "所选目录没有读取权限，请重新选择。"
            return
        }
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            message = null
            val replaced = replaceUri ?: if (!onboardingCompleted) directory else null
            val entry = VideoDirectory(uri.toString())
            directories = replaceDirectory(directories, entry.uri, replaced)
            directoryStore.save(directories)
            diagnostics.event("directory_added_or_reauthorized")
            pruneDisabledVideos()
            if (replaced != null && replaced != entry.uri) releaseDirectoryGrant(replaced)
            scan()
        } catch (_: SecurityException) {
            message = "无法保留此目录的读取权限，请选择支持持久授权的目录。"
        }
    }

    internal fun setDirectoryEnabled(uri: String, enabled: Boolean) {
        directories = directories.map { if (it.uri == uri) it.copy(enabled = enabled) else it }
        directoryStore.save(directories)
        diagnostics.event("directory_enablement_changed")
        pruneDisabledVideos()
        scan()
    }

    internal fun removeDirectory(uri: String) {
        directories = directories.filterNot { it.uri == uri }
        directoryStore.save(directories)
        diagnostics.event("directory_removed")
        pruneDisabledVideos()
        scan()
        releaseDirectoryGrant(uri)
    }

    private fun releaseDirectoryGrant(uri: String) {
        if (directories.any { it.uri == uri }) return
        runCatching { getApplication<Application>().contentResolver
            .releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun pruneDisabledVideos() {
        val enabled = directories.filter { it.enabled }.map { it.uri }.toSet()
        library = library.filter { it.sourceDirectory == null || it.sourceDirectory in enabled }
        videos = videos.filter { it.sourceDirectory == null || it.sourceDirectory in enabled }
        updateFavorites()
        if (videos.isEmpty()) { controller?.clearMediaItems(); currentId = null } else syncQueue()
    }

    fun scan() {
        scanJob?.cancel()
        val generation = ++scanGeneration
        val selected = directories.toList()
        directoryReadable = false
        message = null
        knownGrants = getApplication<Application>().contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        if (selected.none { it.enabled }) {
            scanning = false
            library = emptyList()
            videos = emptyList()
            updateFavorites()
            syncQueue()
            return
        }
        scanning = true
        scanJob = viewModelScope.launch {
            try {
                val result = videoLibrary.scan(selected)
                if (generation != scanGeneration) return@launch
                directories = result.directories
                library = result.entries
                frames.onLibraryChanged()
                videos = orderedLibrary()
                updateFavorites()
                directoryReadable = directories.any { it.enabled && it.readable }
                scanning = false
                syncQueue()
                if (!directoryReadable) message = "启用的目录暂时无法读取，请到视频目录中重新授权。"
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation != scanGeneration) return@launch
                library = emptyList()
                videos = emptyList()
                updateFavorites()
                scanning = false
                syncQueue()
                message = "无法读取目录，请检查目录或重新授权。"
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
        rememberSelection()
        activeMode = mode
        prefs.edit { putInt("active_mode", mode) }
        syncQueue(resetSelection = true)
    }

    fun selectVideo(id: String) {
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId == id) return
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id } ?: return
        rememberSelection()
        diagnostics.requested(id, "swipe")
        player.seekToDefaultPosition(index)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        lifecyclePolicy.userAction()
        if (playbackPageVisible && (foreground || settings.backgroundPlay)) player.play()
        currentId = id
    }

    fun toggleLike(id: String) {
        val identity = mediaIdentity(id)
        likedIds = if (identity in likedKeys) likedIds.filterNot { mediaIdentity(it) == identity }.toSet()
            else likedIds + id + identity
        likedKeys = likedIds.map(::mediaIdentity).toSet()
        prefs.edit { putStringSet("liked_video_uris", likedIds) }
        updateFavorites()
        if (activeMode == 1) syncQueue()
    }

    fun likeVideo(id: String): Boolean {
        if (isLiked(id)) return false
        toggleLike(id)
        return true
    }

    private fun updateFavorites() { favorites = videos.filter { it.identity in likedKeys } }

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
        diagnostics.foreground(value)
        if (value && onboardingCompleted) {
            val grants = getApplication<Application>().contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
            if (grants != knownGrants) scan()
        }
        if (!value) frames.clear()
        if (value && playbackPageVisible) frames.prefetch(playlist(activeMode), currentId)
        if (value && !onboardingCompleted) refreshDirectoryAccess()
        if (!value) { onLongPressChanged(false); rememberSelection() }
        val player = controller ?: return
        sendPreloadWindow()
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
        diagnostics.playbackVisible(visible)
        if (!visible) frames.cancelPending()
        onLongPressChanged(false)
        onForegroundChanged(foreground)
        if (visible) frames.prefetch(playlist(activeMode), currentId)
    }

    fun onLongPressChanged(value: Boolean): Boolean {
        val previous = longPress
        longPress = value && !settings.global2x && foreground && playbackPageVisible && isPlaying
        if (longPress != previous) controller?.setPlaybackSpeed(if (settings.global2x || longPress) 2f else 1f)
        return longPress && !previous
    }

    internal fun prefetchVideos(mode: Int, window: PreloadWindow) {
        if (mode != activeMode) return
        val entries = playlist(mode)
        if (foreground && playbackPageVisible) frames.prefetch(entries, window.indices) else frames.cancelPending()
        preloadIds = window.indices.mapNotNull { entries.getOrNull(it)?.id }
        preloadScrolling = window.scrolling
        sendPreloadWindow()
    }

    private fun sendPreloadWindow() {
        controller?.sendCustomCommand(SessionCommand(PlaybackCommands.PREFETCH, Bundle.EMPTY), Bundle().apply {
            putStringArrayList(PlaybackCommands.IDS, ArrayList(preloadIds))
            putBoolean(PlaybackCommands.SCROLLING, preloadScrolling || !playbackPageVisible ||
                (!foreground && !settings.backgroundPlay))
        })
    }

    fun refreshCacheUsage() { requestCacheCommand(clear = false) }

    fun onRenderedFirstFrame(id: String) { diagnostics.rendered(id) }

    fun shareDiagnostics(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { diagnostics.exportFile() }
                val app = getApplication<Application>()
                onReady(FileProvider.getUriForFile(app, "${app.packageName}.reports", file))
                diagnosticsExportStatus = "已打开分享菜单"
            } catch (_: Exception) { diagnosticsExportStatus = "报告导出失败，请稍后重试" }
        }
    }

    fun saveDiagnostics(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = diagnostics.reportText()
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                        ?: throw java.io.IOException("Cannot open report destination")
                }
                diagnosticsExportStatus = "报告已保存，可以在本对话上传 JSON 文件"
            } catch (_: Exception) { diagnosticsExportStatus = "保存失败，请重新选择保存位置" }
        }
    }

    fun copyDiagnosticsSummary() {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { diagnostics.summaryText() }
                getApplication<Application>().getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Neko Player 诊断摘要", text))
                diagnosticsExportStatus = "摘要已复制，可直接粘贴到聊天并补充使用感受"
            } catch (_: Exception) { diagnosticsExportStatus = "复制失败，请先记录一次测试" }
        }
    }

    fun clearCache() {
        if (clearingCache) return
        frames.clear()
        requestCacheCommand(clear = true)
    }

    private fun requestCacheCommand(clear: Boolean) {
        val player = controller ?: run { cacheStatus = "播放服务暂未连接"; return }
        if (!clear && !clearingCache) cacheStatus = null
        if (clear) { clearingCache = true; cacheStatus = "正在清理…" }
        val result = player.sendCustomCommand(SessionCommand(
            if (clear) PlaybackCommands.CLEAR_CACHE else PlaybackCommands.CACHE_SIZE, Bundle.EMPTY), Bundle.EMPTY)
        result.addListener({
            try {
                val reply = result.get()
                check(reply.resultCode == SessionResult.RESULT_SUCCESS)
                playbackCacheBytes = reply.extras.getLong(PlaybackCommands.BYTES)
                if (clear) cacheStatus = "缓存已清理"
            } catch (_: Exception) { cacheStatus = if (clear) "清理失败，请重试" else "暂时无法读取缓存" }
            finally { if (clear) clearingCache = false }
        }, ContextCompat.getMainExecutor(getApplication<Application>()))
    }

    fun unmute() { requestedUnmute = true; controller?.volume = 1f }

    fun updateSettings(value: PlayerSettings) {
        val orderChanged = settings.shuffle != value.shuffle || settings.preferUnwatched != value.preferUnwatched
        settings = value
        if (value.global2x) longPress = false
        prefs.edit {
            putBoolean("isShuffleEnabled", value.shuffle)
            putBoolean("preferUnwatched", value.preferUnwatched)
            putBoolean("isAutoNext", value.autoNext)
            putInt("resizeMode", if (value.crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT)
            putBoolean("isGlobal2xSpeed", value.global2x)
            putBoolean("isBackgroundPlayAllowed", value.backgroundPlay)
            putBoolean("isColdStartMuted", value.coldMute)
            putString("app_theme", value.theme.preferenceValue)
        }
        applyPlaybackSettings()
        if (orderChanged) { videos = orderedLibrary(); updateFavorites(); syncQueue() }
    }

    private fun orderedLibrary(): List<VideoEntry> {
        val watchedKeys = watchedIds.map(::mediaIdentity).toSet()
        val effectiveWatched = watchedIds + library.filter { it.identity in watchedKeys }.map { it.id }
        return orderVideos(library, settings.shuffle, effectiveWatched, settings.preferUnwatched)
    }

    private fun applyPlaybackSettings() {
        diagnostics.settings(mapOf("shuffle" to settings.shuffle, "prefer_unwatched" to settings.preferUnwatched,
            "auto_next" to settings.autoNext, "crop" to settings.crop, "global_2x" to settings.global2x,
            "background_play" to settings.backgroundPlay, "cold_mute" to settings.coldMute))
        controller?.apply {
            repeatMode = if (settings.autoNext) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            setPlaybackSpeed(if (settings.global2x || longPress) 2f else 1f)
        }
    }

    private fun rememberSelection() {
        controller?.let { player ->
            player.currentMediaItem?.mediaId?.let { id ->
                selection[activeMode] = id
                prefs.edit {
                    putString(if (activeMode == 0) "saved_video_uri" else "saved_liked_video_uri", id)
                    putString("playback_uri", id)
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
            val saved = selection[activeMode]
            val selected = target.firstOrNull { it.identity == saved?.let(::mediaIdentity) }?.id ?: saved
            val effectiveWatched = watchedIds + target.filter { it.identity in watchedIds }.map { it.id }
            val index = initialVideoIndex(target, selected, effectiveWatched,
                wasEmpty && settings.shuffle && settings.preferUnwatched)
            val id = target[index].id
            diagnostics.requested(id, "queue_start")
            player.setMediaItems(target.map(::item), index, 0L)
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
                val aliasIndex = target.indexOfFirst { it.identity == previousId?.let(::mediaIdentity) }
                val nextIndex = if (aliasIndex >= 0) aliasIndex else indexAfterRemoval(previousIndex, target.size)
                target.getOrNull(nextIndex)?.let { diagnostics.requested(it.id, "queue_edit") }
                player.seekToDefaultPosition(nextIndex)
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
        videoLibrary.close()
        frames.clear()
        getApplication<Application>().unregisterComponentCallbacks(memoryCallbacks)
        watchHistory.preferences.unregisterOnSharedPreferenceChangeListener(historyListener)
        rememberSelection()
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        super.onCleared()
    }
}
