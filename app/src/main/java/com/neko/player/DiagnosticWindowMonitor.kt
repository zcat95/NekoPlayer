package com.neko.player

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** Attach only while the user records a test; normal playback pays no frame-observer cost. */
@Composable
internal fun DiagnosticWindowMonitor(recorder: PerformanceDiagnostics) {
    val activity = LocalActivity.current
    DisposableEffect(activity, recorder.running) {
        if (activity == null || !recorder.running) return@DisposableEffect onDispose { }
        val thread = HandlerThread("NekoFrameMetrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            recorder.uiFrame(metrics.getMetric(FrameMetrics.TOTAL_DURATION), metrics.getMetric(FrameMetrics.DEADLINE), lost)
        }
        activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        onDispose { activity.window.removeOnFrameMetricsAvailableListener(listener); thread.quitSafely() }
    }
}
