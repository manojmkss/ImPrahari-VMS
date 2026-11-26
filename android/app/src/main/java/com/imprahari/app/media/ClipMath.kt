package com.imprahari.app.media

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The arithmetic of saving a clip: the trim range, its window and its file name. All times unix seconds. */
object ClipMath {
    /** The NVR's own limit for one clip (recordings.py MAX_EXPORT_SECONDS). */
    const val MAX_SECONDS = 2 * 60 * 60.0
    const val MIN_SECONDS = 1.0

    /** Widths the trim bar can show. */
    val SPANS = listOf(2 * 60.0, 10 * 60.0, 30 * 60.0, 2 * 60 * 60.0)

    /** The range a new clip starts with: 30 s either side of the moment on screen, never past [now]. */
    fun defaultRange(moment: Double, now: Double): Pair<Double, Double> {
        val end = minOf(moment + 30, now)
        val start = minOf(moment - 30, end - MIN_SECONDS)
        return start to end
    }

    /**
     * What the trim bar spans: [current] while the clip still fits inside it
     * (so the bar doesn't move under the finger), otherwise [span] wide
     * around the clip - never reaching past [now].
     */
    fun window(start: Double, end: Double, span: Double, now: Double, current: Pair<Double, Double>? = null): Pair<Double, Double> {
        if (current != null && current.second - current.first == maxOf(span, end - start) &&
            start >= current.first && end <= current.second
        ) return current
        val width = maxOf(span, end - start)
        val to = minOf((start + end) / 2 + width / 2, maxOf(now, end))
        return (to - width) to to
    }

    /** Why this range can't be saved, or null if it can. */
    fun problem(start: Double, end: Double): String? = when {
        end - start < MIN_SECONDS -> "The end must be after the start"
        end - start > MAX_SECONDS -> "Too long - a clip can be at most 2 hours"
        else -> null
    }

    /** Seconds of [start, end] that have footage, from the timeline's spans. */
    fun covered(spans: List<List<Double>>, start: Double, end: Double): Double =
        spans.sumOf { s -> if (s.size < 2) 0.0 else maxOf(0.0, minOf(end, s[1]) - maxOf(start, s[0])) }

    /** "1 min 05 s", "45 s", "1 h 20 min". */
    fun length(seconds: Double): String {
        val s = seconds.toLong()
        return when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min ${"%02d".format(Locale.ROOT, s % 60)} s"
            else -> "$s s"
        }
    }

    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")

    /** "Front door 2026-11-18 14-03-22.mp4": readable, sortable, safe on every file system. */
    fun fileName(camera: String, start: Double, zone: ZoneId): String {
        val safe = camera.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(60)
        val time = FILE_TIME.format(Instant.ofEpochMilli((start * 1000).toLong()).atZone(zone))
        return "${safe.ifEmpty { "Camera" }} $time.mp4"
    }

    /** The NVR's export address (GET /api/recordings/export). The clip is the
     *  camera's own H.264/H.265 at full quality: the NVR never converts. */
    fun exportPath(cameraId: Int, start: Double, end: Double): String =
        "/api/recordings/export?camera_id=$cameraId" +
            "&start=${"%.3f".format(Locale.ROOT, start)}&end=${"%.3f".format(Locale.ROOT, end)}"
}
