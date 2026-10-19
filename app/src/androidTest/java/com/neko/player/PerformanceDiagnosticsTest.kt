package com.neko.player

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceDiagnosticsTest {
    @Test fun reportsExcludeVideoIdentifiersFreezeOnStopAndSurviveRecorderRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "diagnostics-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(folder, "files").apply { mkdirs() }
            override fun getCacheDir() = File(folder, "cache").apply { mkdirs() }
        }
        val recorder = PerformanceDiagnostics(isolated, installCrashHandler = false)
        val main = InstrumentationRegistry.getInstrumentation()
        var restored: PerformanceDiagnostics? = null
        try {
            main.runOnMainSync {
                recorder.foreground(true)
                recorder.start()
                recorder.requested("content://secret-provider/private-video-name.mp4", "swipe")
                recorder.rendered("content://secret-provider/private-video-name.mp4")
                recorder.dropped(3)
                recorder.uiFrame(30_000_000, 16_000_000, 0)
                recorder.error("ERROR_CODE_IO_FILE_NOT_FOUND")
                recorder.stop()
            }
            val first = JSONObject(recorder.reportText())
            assertEquals(1, first.getJSONObject("summary").getInt("measured_first_frames"))
            assertEquals(3, first.getJSONObject("summary").getInt("dropped_video_frames"))
            assertEquals(1, first.getJSONObject("summary").getInt("ui_frames_over_deadline"))
            assertFalse(first.toString().contains("private-video-name"))
            assertFalse(first.toString().contains("secret-provider"))
            assertFalse(recorder.summaryText().contains("private-video-name"))
            SystemClock.sleep(60)
            assertEquals(first.getLong("elapsed_ms"), JSONObject(recorder.reportText()).getLong("elapsed_ms"))
            recorder.closeForTests()
            restored = PerformanceDiagnostics(isolated, installCrashHandler = false)
            val saved = JSONObject(restored.reportText())
            assertFalse(saved.getBoolean("recording"))
            assertEquals(1, saved.getJSONObject("summary").getInt("measured_first_frames"))
            val exported = restored.exportFile()
            assertTrue(exported.name.endsWith(".json"))
            assertFalse(exported.readText().contains("private-video-name"))
        } finally {
            recorder.closeForTests()
            restored?.closeForTests()
            folder.deleteRecursively()
        }
    }
}
