package com.imprahari.app.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Runs real FFmpeg output through [Feed]. live_hevc_sample.mp4 is 12 s of
 * HEVC (5 fps, a keyframe every 2 s) packaged with the NVR's exact live flags:
 * `-c:v libx265 -tag:v hvc1 -f mp4 -movflags empty_moov+default_base_moof+frag_every_frame`.
 *
 * Without `frag_discont`, fragment decode times count from when *that FFmpeg
 * process* started. The NVR shares one process per camera across viewers, so
 * a viewer who joins later gets init + fragments that start mid-timeline -
 * which is what [joinedAt] reproduces.
 */
class RealStreamTest {
    private val raw: ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("live_hevc_sample.mp4")) { "fixture missing" }
            .use { it.readBytes() }
    private val boxes = Mp4.children(raw, 0, raw.size)
    private val scale = Mp4.trackTimescales(raw).values.single()

    private fun bytesOf(b: Mp4.Box) = raw.copyOfRange(b.start, b.end)

    private fun tfdt(moof: ByteArray): Long {
        val traf = Mp4.children(moof, 8, moof.size).first { it.type == "traf" }
        val t = Mp4.child(moof, traf, "tfdt")!!
        return if (moof[t.payload].toInt() == 1) Mp4.u64(moof, t.payload + 4) else Mp4.u32(moof, t.payload + 4)
    }

    /** What the server sends a viewer joining at fragment [index]: the init
     *  segment, then the stream from that (keyframe) fragment on. */
    private fun joinedAt(index: Int): Pair<ByteArray, List<Mp4.Box>> {
        val init = boxes.takeWhile { it.type != "moof" }
        val firstMoof = boxes.indexOfFirst { it.type == "moof" }
        val tail = boxes.drop(firstMoof + 2 * index) // each fragment is moof + mdat
        val parts = init + tail
        return parts.fold(ByteArray(0)) { acc, b -> acc + bytesOf(b) } to parts
    }

    @Test
    fun fixtureIsARealOneFramePerFragmentHevcStream() {
        assertEquals("ftyp", boxes[0].type)
        assertEquals("moov", boxes[1].type)
        assertEquals("one fragment per frame (12 s x 5 fps)", 60, boxes.count { it.type == "moof" })
        assertTrue("codec is hvc1 (HEVC)", String(raw, Charsets.ISO_8859_1).contains("hvc1"))
        val first = boxes.first { it.type == "moof" }
        assertEquals("process-relative time starts at 0", 0L, tfdt(bytesOf(first)))
    }

    @Test
    fun aViewerJoiningMidStreamIsRebasedToZeroWithFramesIntact() {
        val (stream, parts) = joinedAt(40) // frame 40 = 8 s in, a keyframe; 20 frames follow
        val joinMoof = parts.first { it.type == "moof" }
        val joinSeconds = tfdt(bytesOf(joinMoof)).toDouble() / scale
        assertEquals("the joined stream really starts mid-timeline", 8.0, joinSeconds, 0.01)

        // Deliver it the way a websocket might: arbitrary message boundaries.
        val feed = Feed()
        val rnd = Random(42)
        var p = 0
        while (p < stream.size) {
            val n = minOf(rnd.nextInt(1, 900), stream.size - p)
            feed.push(stream.copyOfRange(p, p + n))
            p += n
        }
        feed.close()
        val out = generateSequence { feed.take() }.toList()

        assertEquals(parts.map { it.type }, out.map { Mp4.type(it, 4) })
        val times = out.filter { Mp4.type(it, 4) == "moof" }.map { tfdt(it) }
        assertEquals("rebased to start at zero", 0L, times.first())
        assertTrue("decode times strictly increase", times.zipWithNext().all { (a, b) -> b > a })
        assertEquals("spacing preserved (0.2 s per frame)", 2.0, times[10].toDouble() / scale, 0.01)

        // Only tfdt may change: the init segment and every mdat (the actual
        // HEVC frames) must come through byte-for-byte.
        parts.zip(out).forEach { (box, got) ->
            if (box.type != "moof") assertArrayEquals(bytesOf(box), got)
            else assertEquals(box.end - box.start, got.size)
        }
    }
}
