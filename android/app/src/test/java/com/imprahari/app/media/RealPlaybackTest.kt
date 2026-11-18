package com.imprahari.app.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream

/**
 * A real playback session, captured from the NVR's media websocket
 * (android/tools/capture_playback.py): recordings A (t0..t0+20 s), B
 * (t0+22..t0+42, so played straight across the 2 s gap) and C (from
 * t0+100), played from t0+5. Replayed through the app's own protocol code.
 */
class RealPlaybackTest {
    private data class Msg(val text: String?, val bytes: ByteArray?)

    private fun capture(): Pair<Double, List<Msg>> {
        val stream = DataInputStream(javaClass.classLoader!!.getResourceAsStream("playback_capture.bin")!!.buffered())
        val out = ArrayList<Msg>()
        while (stream.available() > 0) {
            val kind = stream.readByte().toInt().toChar()
            val payload = ByteArray(stream.readInt()).also { stream.readFully(it) }
            out += if (kind == 'T') Msg(String(payload, Charsets.UTF_8), null) else Msg(null, payload)
        }
        val facts = Json.parseToJsonElement(out.first().text!!).jsonObject["facts"]!!.jsonObject
        return facts["t0"]!!.jsonPrimitive.double to out.drop(1)
    }

    @Test
    fun twoFilesPlayAsOneStreamThenTheGapIsReported() {
        val (t0, messages) = capture()
        val feeds = ArrayList<Feed>()
        val events = ArrayList<PlaybackProtocol.Event>()
        val protocol = PlaybackProtocol { origin -> Feed(sessionOrigin = origin).also { feeds += it } }
        for (m in messages) {
            if (m.text != null) protocol.onText(m.text)?.let { events += it }
            else events += protocol.onBinary(m.bytes!!)
        }

        // A and B continue one player stream; then the 58 s gap ends the session.
        assertEquals(1, feeds.size)
        assertEquals(1, events.count { it is PlaybackProtocol.Event.NewFeed })
        val gap = events.last() as PlaybackProtocol.Event.Gap
        assertEquals(t0 + 100, gap.nextAt!!, 0.001)
        assertEquals(t0 + 41, gap.at!!, 1.0)

        // Every video fragment, in the player's (rebased) time, mapped back to
        // wall-clock time - which must be where it really is in A or B.
        val feed = feeds.single()
        assertTrue(feed.closed) // the gap ended the feed: the player drains, then ENDED
        val boxes = generateSequence { feed.take() }.toList()
        var scales = emptyMap<Int, Long>()
        val walls = ArrayList<Double>()
        for (b in boxes) {
            when (Mp4.type(b, 4)) {
                "moov" -> scales = Mp4.trackTimescales(b)
                "moof" -> {
                    val root = Mp4.Box("moof", 0, 8, b.size)
                    for (traf in Mp4.children(b, root.payload, root.end).filter { it.type == "traf" }) {
                        val tfhd = Mp4.child(b, traf, "tfhd")!!
                        val track = Mp4.u32(b, tfhd.payload + 4).toInt()
                        if (track != 1) continue // video
                        val tfdt = Mp4.child(b, traf, "tfdt")!!
                        val t = if (b[tfdt.payload].toInt() == 1) Mp4.u64(b, tfdt.payload + 4) else Mp4.u32(b, tfdt.payload + 4)
                        walls += feed.wallAt(t * 1000 / scales.getValue(track))!!
                    }
                }
            }
        }
        assertTrue("fragments: ${walls.size}", walls.size >= 10)
        // Player time only moves forward - across the change of file too.
        assertEquals(walls.sorted(), walls)
        // Starts at the keyframe at or before the requested t0+5.
        assertTrue("first ${walls.first() - t0}", walls.first() - t0 in 3.9..5.01)
        // Footage from A stays within A, footage from B within B, nothing in between.
        assertTrue(walls.all { it - t0 in 0.0..20.0 || it - t0 in 22.0..42.0 })
        assertTrue(walls.any { it - t0 in 0.0..20.0 } && walls.any { it - t0 in 22.0..42.0 })
        // The jump across the gap is the gap itself (~2 s plus one fragment), not 0 or 20 s.
        val jump = walls.zipWithNext().maxOf { (a, b) -> b - a }
        assertTrue("largest step $jump", jump in 2.0..5.0)
    }
}
