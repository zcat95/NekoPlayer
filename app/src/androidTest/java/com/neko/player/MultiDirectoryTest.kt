package com.neko.player

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDirectoryTest {
    private fun uri(root: String) = "content://com.neko.player.test.library/tree/$root"

    @Test fun legacyDirectoryMigratesAndEnablementSurvivesReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("directory_migration_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("saved_directory_uri", uri("a")).commit()
        try {
            val store = DirectoryStore(prefs)
            assertEquals(listOf(VideoDirectory(uri("a"))), store.load())
            store.save(listOf(VideoDirectory(uri("a"), false), VideoDirectory(uri("b"))))
            assertEquals(listOf(VideoDirectory(uri("a"), false), VideoDirectory(uri("b"))), DirectoryStore(prefs).load())
            store.save(emptyList())
            assertTrue(DirectoryStore(prefs).load().isEmpty())
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun overlappingFoldersMergeAndRevokedOrDisabledFoldersDoNotHideHealthyVideos() = runBlocking {
        val library = VideoLibrary(ApplicationProvider.getApplicationContext())
        try {
            val result = library.scan(listOf(VideoDirectory(uri("a")), VideoDirectory(uri("b")),
                VideoDirectory(uri("denied")), VideoDirectory(uri("disabled"), false)))
            assertEquals(setOf("shared", "a-only", "b-only"), result.entries.map { it.identity.substringAfterLast(':') }.toSet())
            assertEquals(3, result.entries.size)
            assertEquals(listOf(true, true, false, false), result.directories.map { it.readable })
            assertEquals(0, library.scan(listOf(VideoDirectory(uri("empty")))).entries.size)
            assertTrue(library.scan(listOf(VideoDirectory(uri("empty")))).directories.single().readable)
        } finally { library.close() }
    }

    @Test fun cancellingSlowProviderReleasesTheSingleScanWorker() = runBlocking {
        val library = VideoLibrary(ApplicationProvider.getApplicationContext())
        try {
            val scan = async { library.scan(listOf(VideoDirectory(uri("slow")))) }
            delay(100)
            scan.cancelAndJoin()
            val next = withTimeout(3_000) { library.scan(listOf(VideoDirectory(uri("a")))) }
            assertEquals(2, next.entries.size)
        } finally { library.close() }
    }

    @Test fun likingAnAliasIsIdempotentAndUnlikingRemovesTheSharedFavorite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("aliases_test_$name", mode)
        }
        val prefs = app.getSharedPreferences("neko_prefs", Context.MODE_PRIVATE)
        val first = uri("a") + "/document/shared"
        val alias = uri("b") + "/document/shared"
        prefs.edit().clear().putStringSet("liked_video_uris", setOf(first)).commit()
        val store = ViewModelStore()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[PlayerViewModel::class.java]
                assertTrue(model.isLiked(alias))
                assertFalse(model.likeVideo(alias))
                model.toggleLike(alias)
                assertFalse(model.isLiked(first))
                assertTrue(model.likeVideo(first))
                assertTrue(model.isLiked(alias))
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            prefs.edit().clear().commit()
        }
    }
}
