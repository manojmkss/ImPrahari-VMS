package com.lightnvr.companion.media

/** Just enough ISO-BMFF (MP4) box handling for the NVR's fragmented-MP4 stream. */
internal object Mp4 {
    data class Box(val type: String, val start: Int, val payload: Int, val end: Int)

    fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

    fun putU32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v ushr 24).toByte()
        b[p + 1] = (v ushr 16).toByte()
        b[p + 2] = (v ushr 8).toByte()
        b[p + 3] = v.toByte()
    }

    fun putU64(b: ByteArray, p: Int, v: Long) {
        putU32(b, p, v ushr 32)
        putU32(b, p + 4, v and 0xFFFFFFFFL)
    }

    fun type(b: ByteArray, p: Int): String = String(b, p, 4, Charsets.ISO_8859_1)

    /** Child boxes laid out in [from, to). Stops at the first malformed size. */
    fun children(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            var header = 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = u64(b, p + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong() // "runs to the end"
            }
            if (size < header || p + size > to) break
            out += Box(type(b, p + 4), p, p + header, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    fun child(b: ByteArray, parent: Box, type: String): Box? =
        children(b, parent.payload, parent.end).firstOrNull { it.type == type }

    /** track_ID -> timescale (ticks per second), read from an init segment's moov. */
    fun trackTimescales(init: ByteArray): Map<Int, Long> {
        val moov = children(init, 0, init.size).firstOrNull { it.type == "moov" } ?: return emptyMap()
        val out = HashMap<Int, Long>()
        for (trak in children(init, moov.payload, moov.end).filter { it.type == "trak" }) {
            val tkhd = child(init, trak, "tkhd") ?: continue
            // full box: version(1) flags(3), then v0: 4+4 / v1: 8+8 bytes of times
            val trackId = u32(init, tkhd.payload + 4 + if (init[tkhd.payload].toInt() == 1) 16 else 8).toInt()
            val mdhd = child(init, trak, "mdia")?.let { child(init, it, "mdhd") } ?: continue
            val timescale = u32(init, mdhd.payload + 4 + if (init[mdhd.payload].toInt() == 1) 16 else 8)
            if (timescale > 0) out[trackId] = timescale
        }
        return out
    }
}

/** The time (seconds) a stream's first fragment had - what the player shows as 0. */
internal class Origin {
    @Volatile
    var seconds: Double? = null
}

/**
 * Shifts every fragment's decode time (tfdt) so the stream starts at ~0.
 *
 * A live stream is one FFmpeg per camera shared by every viewer, so a viewer
 * that joins hours in receives fragments timestamped hours in. A progressive
 * player starts its clock at 0 and would sit waiting for that time to arrive.
 * All tracks share one origin *in seconds*, so audio and video stay in sync.
 *
 * Playback goes through several recording files in one stream: each file's
 * times start at its own 0, and [offsetSeconds] (the file's start relative to
 * the playback session) puts them on one timeline. The [origin] is shared by
 * every file's rebaser, so the player's clock keeps running across files.
 */
internal class TimestampRebaser(
    private val timescales: Map<Int, Long>,
    private val offsetSeconds: Double = 0.0,
    private val origin: Origin = Origin(),
) {
    fun rewrite(b: ByteArray, moof: Mp4.Box) {
        for (traf in Mp4.children(b, moof.payload, moof.end).filter { it.type == "traf" }) {
            val tfhd = Mp4.child(b, traf, "tfhd") ?: continue
            val trackId = Mp4.u32(b, tfhd.payload + 4).toInt()
            val scale = timescales[trackId] ?: continue
            val tfdt = Mp4.child(b, traf, "tfdt") ?: continue
            val v1 = b[tfdt.payload].toInt() == 1
            val time = if (v1) Mp4.u64(b, tfdt.payload + 4) else Mp4.u32(b, tfdt.payload + 4)
            val seconds = time.toDouble() / scale + offsetSeconds
            val start = origin.seconds ?: seconds.also { origin.seconds = it }
            val shifted = maxOf(0L, Math.round((seconds - start) * scale))
            if (v1) Mp4.putU64(b, tfdt.payload + 4, shifted) else Mp4.putU32(b, tfdt.payload + 4, shifted)
        }
    }
}
