package com.neko.player

import android.content.Context
import android.content.SharedPreferences
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class VideoDirectory(val uri: String, val enabled: Boolean = true,
                                   val readable: Boolean = false, val videoCount: Int = 0)

internal fun replaceDirectory(entries: List<VideoDirectory>, uri: String, replaced: String?): List<VideoDirectory> {
    val selected = VideoDirectory(uri)
    if (entries.any { it.uri == uri }) return entries.filterNot { it.uri == replaced && it.uri != uri }
        .map { if (it.uri == uri) selected else it }
    if (entries.any { it.uri == replaced }) return entries.map { if (it.uri == replaced) selected else it }
    return entries + selected
}

internal fun mediaIdentity(id: String): String = runCatching {
    val uri = id.toUri()
    "document:${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
}.getOrDefault(id)

internal fun directoryLabel(id: String): String = runCatching {
    val document = DocumentsContract.getTreeDocumentId(id.toUri())
    if (document.startsWith("primary:")) "内部存储/${document.removePrefix("primary:")}" else document.replace(':', '/')
}.getOrDefault("视频目录")

internal class DirectoryStore(private val prefs: SharedPreferences) {
    fun load(): List<VideoDirectory> {
        val stored = prefs.getString("video_directories", null)
        if (stored != null) runCatching {
            val array = JSONArray(stored)
            return List(array.length()) { index ->
                val item = array.getJSONObject(index)
                VideoDirectory(item.getString("uri"), item.optBoolean("enabled", true))
            }.distinctBy { it.uri }
        }
        return prefs.getString("saved_directory_uri", null)?.let { listOf(VideoDirectory(it)) }.orEmpty()
    }

    fun save(entries: List<VideoDirectory>) {
        val array = JSONArray()
        entries.distinctBy { it.uri }.forEach { array.put(JSONObject().put("uri", it.uri).put("enabled", it.enabled)) }
        prefs.edit {
            putString("video_directories", array.toString())
            putString("saved_directory_uri", entries.firstOrNull { it.enabled }?.uri)
        }
    }
}

internal data class DirectoryScan(val directories: List<VideoDirectory>, val entries: List<VideoEntry>)

/** Serial, cancellable provider reads: a failed or revoked folder never discards other folders. */
internal class VideoLibrary(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()

    suspend fun scan(directories: List<VideoDirectory>): DirectoryScan = suspendCancellableCoroutine { result ->
        val signal = CancellationSignal()
        val future = worker.submit {
            try {
                val libraries = mutableListOf<List<VideoEntry>>()
                val scanned = directories.map { directory ->
                    signal.throwIfCanceled()
                    if (!directory.enabled) directory.copy(readable = false, videoCount = 0) else try {
                        val root = directory.uri.toUri()
                        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
                        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val entries = mutableListOf<VideoEntry>()
                        val cursor = context.contentResolver.query(children, projection, null, null, null, signal)
                            ?: throw java.io.IOException("Directory query failed")
                        cursor.use {
                            while (it.moveToNext()) {
                                signal.throwIfCanceled()
                                val name = it.getString(1).orEmpty()
                                val mime = it.getString(2).orEmpty()
                                if (mime.startsWith("video/") || (mime != DocumentsContract.Document.MIME_TYPE_DIR &&
                                    VIDEO_EXTENSIONS.any { extension -> name.endsWith(extension, true) })) {
                                    val uri = DocumentsContract.buildDocumentUriUsingTree(root, it.getString(0)).toString()
                                    entries += VideoEntry(uri, name, mediaIdentity(uri), directory.uri)
                                }
                            }
                        }
                        libraries += entries
                        directory.copy(readable = true, videoCount = entries.size)
                    } catch (error: OperationCanceledException) { throw error }
                    catch (_: Exception) { directory.copy(readable = false, videoCount = 0) }
                }
                signal.throwIfCanceled()
                if (result.isActive) result.resume(DirectoryScan(scanned, mergeVideos(libraries)))
            } catch (error: Exception) { if (result.isActive) result.resumeWithException(error) }
        }
        result.invokeOnCancellation { signal.cancel(); future.cancel(true) }
    }

    fun close() { worker.shutdownNow() }

    private companion object { val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".webm", ".mov", ".m4v") }
}
