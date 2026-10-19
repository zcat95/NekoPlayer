package com.neko.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract

/** Synthetic documents only; packaged in the test APK, never in the shipped app. */
class LibraryFixtureProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor {
        val root = DocumentsContract.getTreeDocumentId(uri)
        if (root == "denied") throw SecurityException("Synthetic revoked folder")
        if (root == "slow") while (true) {
            cancellationSignal?.throwIfCanceled()
            try { Thread.sleep(20) } catch (_: InterruptedException) { throw OperationCanceledException() }
        }
        return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)).apply {
            if (root != "empty") {
                addRow(arrayOf("shared", "shared.mp4", "video/mp4"))
                addRow(arrayOf("$root-only", "$root.mp4", "video/mp4"))
            }
        }
    }
    override fun getType(uri: Uri) = "vnd.android.document/directory"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
