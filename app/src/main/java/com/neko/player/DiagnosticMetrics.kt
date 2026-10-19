package com.neko.player

import kotlin.math.ceil

internal fun percentile95(values: List<Long>): Long? = values.takeIf { it.isNotEmpty() }?.sorted()
    ?.let { it[(ceil(it.size * .95).toInt() - 1).coerceAtLeast(0)] }

internal fun cpuPercent(cpuMs: Long, elapsedMs: Long): Double? =
    if (elapsedMs > 0 && cpuMs >= 0) cpuMs * 100.0 / elapsedMs else null

internal data class PerformanceSample(
    val elapsedMs: Long, val foreground: Boolean, val playing: Boolean,
    val pssKb: Int, val javaHeapBytes: Long, val nativeHeapBytes: Long,
    val graphicsKb: Int?, val cpuMs: Long, val batteryPercent: Int?, val chargeUah: Long?,
    val charging: Boolean, val batteryTemperatureC: Double?, val thermalStatus: Int,
    val previewBytes: Long,
)
