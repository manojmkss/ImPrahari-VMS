package com.imprahari.app.ui

import java.time.Instant
import java.time.ZoneId

/**
 * The playback scrub bar: a window of [windowSec] seconds of wall time
 * centred on the moment on screen, [width] px wide. Pure, for tests.
 */
object TimelineMath {
    fun xOf(wall: Double, center: Double, windowSec: Double, width: Float): Float =
        (((wall - center) / windowSec + 0.5) * width).toFloat()

    fun wallOf(x: Float, center: Double, windowSec: Double, width: Float): Double =
        center + (x / width - 0.5) * windowSec

    /** Whether [t] has footage, given the NVR's merged [start, end] spans. */
    fun covered(spans: List<List<Double>>, t: Double): Boolean = spans.any { it[0] <= t && t <= it[1] }

    /**
     * Where to actually play when the viewer picks [t]: there if it has
     * footage, else the start of the next footage after it, else the end of
     * the last footage before it (null when there's none at all).
     */
    fun snap(spans: List<List<Double>>, t: Double): Double? {
        if (covered(spans, t)) return t
        spans.filter { it[0] > t }.minByOrNull { it[0] }?.let { return it[0] }
        // Nothing later: a few seconds before the end of the latest footage.
        return spans.filter { it[1] < t }.maxByOrNull { it[1] }?.let { maxOf(it[0], it[1] - 10.0) }
    }

    /** Local midnight (unix s) of the day [wall] falls in. */
    fun dayStart(wall: Double, zone: ZoneId): Double =
        Instant.ofEpochMilli((wall * 1000).toLong()).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toEpochSecond().toDouble()

    /** Seconds between ticks on the bar, for a window this wide. */
    fun tickStep(windowSec: Double): Double = when {
        windowSec <= 20 * 60 -> 5 * 60.0
        windowSec <= 2 * 3600 -> 10 * 60.0
        windowSec <= 8 * 3600 -> 3600.0
        else -> 3 * 3600.0
    }

    /** Tick times (unix s) inside the window, aligned to local wall-clock steps. */
    fun ticks(center: Double, windowSec: Double, zone: ZoneId): List<Double> {
        val step = tickStep(windowSec)
        val from = center - windowSec / 2
        val to = center + windowSec / 2
        val day = dayStart(from, zone)
        var t = day + Math.ceil((from - day) / step) * step
        val out = ArrayList<Double>()
        while (t <= to) {
            out += t
            t += step
        }
        return out
    }
}
