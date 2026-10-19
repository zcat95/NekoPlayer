package com.neko.player

import android.app.ActivityManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Opt-in, bounded, local measurements. Playable URIs are held only for matching a pending frame. */
internal class PerformanceDiagnostics internal constructor(private val context: Context, installCrashHandler: Boolean = true) {
    private val lock = Any()
    private val diskLock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val file = AtomicFile(File(context.filesDir, "diagnostics/latest.json"))
    private var session: Session? = null
    @Volatile private var active = false
    @Volatile private var generation = 0L
    @Volatile private var foreground = false
    @Volatile private var playbackVisible = true
    @Volatile private var playing = false
    @Volatile private var previewBytes = 0L
    @Volatile private var playbackSettings = emptyMap<String, Boolean>()
    var running by mutableStateOf(false)
        private set
    var hasReport by mutableStateOf(file.baseFile.isFile)
        private set
    var status by mutableStateOf(if (hasReport) "已保留上一次报告" else "尚未开始记录")
        private set
    private var pendingId: String? = null
    private var pendingAtMs = 0L
    private var bufferingAtMs: Long? = null

    private data class Session(
        val startedAt: Long = System.currentTimeMillis(),
        val startedElapsed: Long = SystemClock.elapsedRealtime(),
        val cpuAtStart: Long = Process.getElapsedCpuTime(),
        val initialSettings: Map<String, Boolean> = emptyMap(),
        val samples: MutableList<PerformanceSample> = mutableListOf(),
        val firstFrames: MutableList<Long> = mutableListOf(),
        var switches: Int = 0, var droppedFrames: Int = 0, var bufferingMs: Long = 0,
        var bufferCount: Int = 0, var videoFormat: String? = null,
        val errors: MutableList<String> = mutableListOf(),
        val events: MutableList<Pair<Long, String>> = mutableListOf(),
        var stopReason: String? = null,
        var stoppedElapsed: Long? = null,
        var stoppedCpuMs: Long? = null,
        var crash: List<String>? = null,
        var uiFrames: Int = 0, var slowUiFrames: Int = 0, var uiFrameMs: Double = 0.0,
        var maxUiFrameMs: Double = 0.0, var lostUiMetrics: Int = 0,
    )

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            val token = generation
            worker.execute {
                if (!active || token != generation) return@execute
                val result = runCatching { sample(); persist() }
                val snapshot = synchronized(lock) { session?.let { Triple(it.startedElapsed, it.samples.size, it.firstFrames.size) } }
                main.post {
                    if (!active || token != generation || snapshot == null) return@post
                    if (result.isFailure) {
                        error("DIAGNOSTIC_SAMPLING_${result.exceptionOrNull()?.javaClass?.simpleName}")
                        active = false
                        running = false
                        status = "诊断采样中断，已有内容可导出；也可重新记录"
                        return@post
                    }
                    val seconds = (SystemClock.elapsedRealtime() - snapshot.first) / 1000
                    status = "已记录 ${seconds / 60} 分 ${seconds % 60} 秒，${snapshot.third} 次首帧，${snapshot.second} 个采样"
                    hasReport = true
                    if (seconds >= 30 * 60) stop("达到 30 分钟上限")
                    else main.postDelayed(this, if (foreground || playing) 5_000 else 30_000)
                }
            }
        }
    }

    init {
        if (installCrashHandler) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                if (active) runCatching {
                    active = false
                    synchronized(lock) {
                        finishBuffering()
                        session?.apply {
                            stopReason = "uncaught_exception"
                            stoppedElapsed = SystemClock.elapsedRealtime()
                            stoppedCpuMs = Process.getElapsedCpuTime() - cpuAtStart
                            crash = listOf(error.javaClass.name) + error.stackTrace.take(20).map {
                                "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                            }
                        }
                    }
                    persist()
                }
                if (previous != null) previous.uncaughtException(thread, error) else Process.killProcess(Process.myPid())
            }
        }
    }

    fun start() {
        generation++
        main.removeCallbacks(ticker)
        synchronized(lock) { session = Session(initialSettings = playbackSettings); pendingId = null; bufferingAtMs = null }
        active = true
        running = true
        hasReport = true
        status = "正在记录，返回播放页正常使用即可"
        main.post(ticker)
    }

    fun stop(reason: String = "手动停止") {
        if (!active) return
        generation++
        synchronized(lock) {
            finishBuffering()
            session?.stopReason = reason
            session?.stoppedElapsed = SystemClock.elapsedRealtime()
            session?.let { it.stoppedCpuMs = Process.getElapsedCpuTime() - it.cpuAtStart }
            pendingId = null
        }
        active = false
        running = false
        main.removeCallbacks(ticker)
        status = "记录已结束，可以分享或保存报告"
        worker.execute { runCatching { sample(); persist() }.onSuccess { main.post { hasReport = true } } }
    }

    fun foreground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        event(if (value) "foreground" else "background")
        if (!value) synchronized(lock) { pendingId = null }
    }

    fun playbackVisible(value: Boolean) {
        playbackVisible = value
        if (!value) synchronized(lock) { pendingId = null }
    }

    fun playing(value: Boolean) { playing = value }
    fun previewBytes(value: Long) { previewBytes = value }
    fun settings(values: Map<String, Boolean>) {
        if (playbackSettings != values) event("settings_changed")
        playbackSettings = values.toMap()
    }

    fun requested(id: String, reason: String) {
        if (!active || !foreground || !playbackVisible) return
        synchronized(lock) {
            if (pendingId == id) return
            pendingId = id
            pendingAtMs = SystemClock.elapsedRealtime()
            session?.apply { switches++; appendEvent("request:$reason") }
        }
    }

    fun rendered(id: String) {
        if (!active) return
        synchronized(lock) {
            if (id != pendingId) return
            val elapsed = (SystemClock.elapsedRealtime() - pendingAtMs).coerceAtLeast(0)
            session?.apply {
                if (firstFrames.size < 500) firstFrames += elapsed
                appendEvent("first_frame")
            }
            pendingId = null
        }
    }

    fun buffering(value: Boolean) {
        if (!active) return
        synchronized(lock) {
            if (value && bufferingAtMs == null) {
                bufferingAtMs = SystemClock.elapsedRealtime()
                session?.let { it.bufferCount++ }
            } else if (!value) finishBuffering()
        }
    }

    private fun finishBuffering() {
        bufferingAtMs?.let { start -> session?.let { it.bufferingMs += SystemClock.elapsedRealtime() - start } }
        bufferingAtMs = null
    }

    fun dropped(count: Int) { if (active) synchronized(lock) { session?.let { it.droppedFrames += count } } }
    fun uiFrame(totalNanos: Long, deadlineNanos: Long, lostMetrics: Int) {
        if (!active || totalNanos < 0) return
        synchronized(lock) { session?.apply {
            uiFrames++
            if (deadlineNanos > 0 && totalNanos > deadlineNanos) slowUiFrames++
            val millis = totalNanos / 1_000_000.0
            uiFrameMs += millis
            maxUiFrameMs = maxOf(maxUiFrameMs, millis)
            lostUiMetrics += lostMetrics
        } }
    }
    fun videoFormat(mime: String?, width: Int, height: Int, rate: Float) {
        if (active) synchronized(lock) { session?.videoFormat = "${mime ?: "unknown"} ${width}x$height ${rate}fps" }
    }
    fun error(code: String) {
        if (active) synchronized(lock) { session?.errors?.let { if (it.size < 100) it += code } }
    }
    fun event(name: String) { if (active) synchronized(lock) { session?.appendEvent(name) } }
    private fun Session.appendEvent(name: String) {
        if (events.size < 300) events += (SystemClock.elapsedRealtime() - startedElapsed) to name
    }

    private fun sample() {
        val current = synchronized(lock) { session } ?: return
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        val charge = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 }
        val runtime = Runtime.getRuntime()
        val value = PerformanceSample((current.stoppedElapsed ?: SystemClock.elapsedRealtime()) - current.startedElapsed, foreground, playing,
            memory.totalPss, runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize(),
            memory.memoryStats["summary.graphics"]?.toIntOrNull(), current.stoppedCpuMs ?: (Process.getElapsedCpuTime() - current.cpuAtStart),
            percent, charge, (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            batteryIntent?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0), power.currentThermalStatus, previewBytes)
        synchronized(lock) {
            if (session === current && current.samples.size < 400) current.samples += value
        }
    }

    /** Copy collections before serializing, so rendering callbacks never wait for JSON or disk I/O. */
    private fun snapshot(): JSONObject? {
        val copy = synchronized(lock) {
            session?.let { it.copy(samples = it.samples.toMutableList(), firstFrames = it.firstFrames.toMutableList(),
                errors = it.errors.toMutableList(), events = it.events.toMutableList(), crash = it.crash?.toList()) }
        } ?: return null
        val elapsed = ((copy.stoppedElapsed ?: SystemClock.elapsedRealtime()) - copy.startedElapsed).coerceAtLeast(0)
        val first = copy.samples.firstOrNull()
        val last = copy.samples.lastOrNull()
        val activity = context.getSystemService(ActivityManager::class.java)
        val display = context.resources.displayMetrics
        return JSONObject().apply {
            put("schema", 1)
            put("app_version", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("started_at_epoch_ms", copy.startedAt)
            put("elapsed_ms", elapsed)
            put("recording", active)
            put("stop_reason", copy.stopReason ?: JSONObject.NULL)
            put("settings_at_start", JSONObject(copy.initialSettings))
            put("settings_at_export", JSONObject(playbackSettings))
            put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("memory_class_mb", activity.memoryClass).put("low_ram", activity.isLowRamDevice)
                .put("screen", "${display.widthPixels}x${display.heightPixels}"))
            put("summary", JSONObject().put("requested_switches", copy.switches).put("measured_first_frames", copy.firstFrames.size)
                .put("first_frame_p95_ms", percentile95(copy.firstFrames) ?: JSONObject.NULL)
                .put("first_frame_average_ms", copy.firstFrames.takeIf { it.isNotEmpty() }?.average() ?: JSONObject.NULL)
                .put("peak_pss_kb", copy.samples.maxOfOrNull { it.pssKb } ?: JSONObject.NULL)
                .put("last_background_pss_kb", copy.samples.lastOrNull { !it.foreground }?.pssKb ?: JSONObject.NULL)
                .put("cpu_percent_of_one_core", last?.let { cpuPercent(it.cpuMs, it.elapsedMs) } ?: JSONObject.NULL)
                .put("dropped_video_frames", copy.droppedFrames).put("buffering_count", copy.bufferCount)
                .put("ui_frames", copy.uiFrames).put("ui_frames_over_deadline", copy.slowUiFrames)
                .put("ui_frame_average_ms", if (copy.uiFrames > 0) copy.uiFrameMs / copy.uiFrames else JSONObject.NULL)
                .put("ui_frame_max_ms", copy.maxUiFrameMs).put("lost_ui_metric_callbacks", copy.lostUiMetrics)
                .put("buffering_ms", copy.bufferingMs)
                .put("device_battery_percent_change", if (first?.batteryPercent != null && last?.batteryPercent != null)
                    last.batteryPercent - first.batteryPercent else JSONObject.NULL)
                .put("last_video_format", copy.videoFormat ?: JSONObject.NULL))
            put("first_frame_callback_ms", JSONArray(copy.firstFrames))
            put("errors", JSONArray(copy.errors))
            put("crash", copy.crash?.let(::JSONArray) ?: JSONObject.NULL)
            put("events", JSONArray(copy.events.map { (ms, name) -> JSONObject().put("ms", ms).put("event", name) }))
            put("samples", JSONArray(copy.samples.map { s -> JSONObject().put("elapsed_ms", s.elapsedMs)
                .put("foreground", s.foreground).put("playing", s.playing).put("pss_kb", s.pssKb)
                .put("java_heap_bytes", s.javaHeapBytes).put("native_heap_bytes", s.nativeHeapBytes)
                .put("graphics_kb", s.graphicsKb ?: JSONObject.NULL).put("process_cpu_ms", s.cpuMs)
                .put("battery_percent", s.batteryPercent ?: JSONObject.NULL).put("charge_uah", s.chargeUah ?: JSONObject.NULL)
                .put("charging", s.charging).put("battery_temperature_c", s.batteryTemperatureC ?: JSONObject.NULL)
                .put("thermal_status", s.thermalStatus).put("preview_cache_bytes", s.previewBytes) }))
            put("notes", JSONArray(listOf("No video names, paths, URIs or video files are included.",
                "First-frame time measures selection to the renderer callback, not a camera measurement of display latency.",
                "Battery changes describe the whole device, not app-only energy; charging and screen use affect them.",
                "Sampling adds overhead. Background samples can be delayed by Android sleep.")))
        }
    }

    private fun persist() {
        val text = snapshot()?.toString(2) ?: return
        synchronized(diskLock) {
            file.baseFile.parentFile?.mkdirs()
            val output = file.startWrite()
            try { output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }
    }

    /** Call from an I/O worker. A previous process's unfinished record is retained, never auto-resumed. */
    fun reportText(): String {
        snapshot()?.let { return it.toString(2) }
        val stored = synchronized(diskLock) { file.openRead().bufferedReader().use { it.readText() } }
        val json = JSONObject(stored)
        if (json.optBoolean("recording")) {
            json.put("recording", false)
            json.put("restored_after_process_exit", true)
        }
        return json.toString(2)
    }

    fun exportFile(): File {
        val folder = File(context.cacheDir, "diagnostic-reports").apply { mkdirs() }
        folder.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
            ?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return File(folder, "NekoPlayer-${BuildConfig.VERSION_NAME}-$stamp.json").apply { writeText(reportText()) }
    }

    internal fun summaryText(): String {
        val report = JSONObject(reportText())
        val device = report.getJSONObject("device")
        val summary = report.getJSONObject("summary")
        fun metric(key: String, divisor: Double = 1.0): String = summary.optDouble(key, Double.NaN)
            .takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.1f", it / divisor) } ?: "暂无"
        val seconds = report.optLong("elapsed_ms") / 1000
        return listOf(
            "Neko Player ${report.optString("app_version")} 诊断摘要",
            "机型：${device.optString("manufacturer")} ${device.optString("model")}，Android ${device.optString("android")}",
            "记录：${seconds / 60} 分 ${seconds % 60} 秒",
            "结束状态：${if (report.optBoolean("restored_after_process_exit")) "应用退出后恢复了最后一次记录" else report.optString("stop_reason", "记录中")}",
            "首帧回调：平均 ${metric("first_frame_average_ms")} ms，P95 ${metric("first_frame_p95_ms")} ms，共 ${summary.optInt("measured_first_frames")} 次",
            "进程内存：峰值 ${metric("peak_pss_kb", 1024.0)} MB，最后一次后台 ${metric("last_background_pss_kb", 1024.0)} MB",
            "CPU：${metric("cpu_percent_of_one_core")} %（以一个核心为基准）",
            "视频丢帧：${summary.optInt("dropped_video_frames")}；界面超时帧：${summary.optInt("ui_frames_over_deadline")}/${summary.optInt("ui_frames")}",
            "缓冲：${summary.optInt("buffering_count")} 次，共 ${summary.optLong("buffering_ms")} ms",
            "整机电量变化：${metric("device_battery_percent_change")} 个百分点（不能视为应用单独耗电）",
            "错误代码：${report.optJSONArray("errors") ?: "[]"}",
            "我的观察：请补充卡顿、黑屏、误触出现的操作和大概时间。",
        ).joinToString("\n")
    }

    @androidx.annotation.VisibleForTesting
    internal fun closeForTests() {
        active = false
        main.removeCallbacks(ticker)
        worker.shutdown()
        worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    companion object {
        // get() retains applicationContext only; an Activity is never stored in this singleton.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: PerformanceDiagnostics? = null
        fun get(context: Context): PerformanceDiagnostics = instance ?: synchronized(this) {
            instance ?: PerformanceDiagnostics(context.applicationContext).also { instance = it }
        }
    }
}
