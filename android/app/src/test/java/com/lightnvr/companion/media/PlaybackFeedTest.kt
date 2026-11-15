package com.lightnvr.companion.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playback through several recording files in one player: each file's
 * fragment times start at its own 0 and the server says where the file sits
 * in the session (`offset`); the feed must put them on one running timeline
 * and map the player's position back to wall-clock time.
 */
class PlaybackFeedTest {
    private fun u32(v: Long) = ByteArray(4).also { Mp4.putU32(it, 0, v) }
    private fun u64(v: Long) = ByteArray(8).also { Mp4.putU64(it, 0, v) }
    private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = cat(*payload)
        return u32(8L + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }
    private fun fullBox(type: String, version: Int, vararg payload: ByteArray) =
        box(type, byteArrayOf(version.toByte(), 0, 0, 0), *payload)
    private fun trak(trackId: Long, timescale: Long) = box(
        "trak",
        fullBox("tkhd", 0, u32(0), u32(0), u32(trackId), ByteArray(68)),
        box("mdia", fullBox("mdhd", 0, u32(0), u32(0), u32(timescale), u32(0))),
    )
    private fun init(vararg traks: ByteArray) = box("ftyp", "isom".toByteArray(), u32(0)) + box("moov", *traks)
    private fun fragment(trackId: Long, time: Long) = box(
        "moof",
        fullBox("mfhd", 0, u32(1)),
        box("traf", fullBox("tfhd", 0, u32(trackId)), fullBox("tfdt", 1, u64(time))),
    ) + box("mdat", byteArrayOf(1, 2, 3, 4))

    private fun tfdts(feed: Feed): List<Long> {
        feed.close()
        return generateSequence { feed.take() }.toList()
            .filter { Mp4.type(it, 4) == "moof" }
            .map { moof ->
                val root = Mp4.Box("moof", 0, 8, moof.size)
                val traf = Mp4.children(moof, root.payload, root.end).first { it.type == "traf" }
                val tfdt = Mp4.child(moof, traf, "tfdt")!!
                Mp4.u64(moof, tfdt.payload + 4)
            }
    }

    @Test
    fun filesContinueOneTimelineAcrossInits() {
        // Session origin 1000 s. File A starts at the origin; the viewer joined
        // 12 s into it (the server starts at the keyframe at 10 s). File B
        // starts 60 s into the session and has a different timescale.
        val feed = Feed(sessionOrigin = 1000.0)
        feed.beginPiece(0.0)
        feed.push(init(trak(1, 90_000)))
        feed.push(fragment(1, 10L * 90_000))
        feed.push(fragment(1, 11L * 90_000))
        assertEquals(1010.0, feed.wallAt(0)!!, 1e-6) // player 0 = the first fragment
        assertEquals(1011.5, feed.wallAt(1_500)!!, 1e-6)

        feed.beginPiece(60.0)
        feed.push(init(trak(1, 1_000)))
        feed.push(fragment(1, 0)) // B's own 0 = session 60 s
        feed.push(fragment(1, 2_000))
        // Rebased to the first fragment (session 10 s), in each file's timescale.
        assertEquals(listOf(0L, 90_000L, 50_000L, 52_000L), tfdts(feed))
    }

    @Test
    fun noPositionBeforeTheFirstFragment() {
        val feed = Feed(sessionOrigin = 1000.0)
        feed.push(init(trak(1, 90_000)))
        assertNull(feed.wallAt(0))
    }

    @Test
    fun liveIsUnchangedByTheOffsetMachinery() {
        // Live: no beginPiece, origin 0 - same rebasing as before.
        val feed = Feed()
        feed.push(init(trak(1, 90_000)))
        feed.push(fragment(1, 7_200L * 90_000))
        feed.push(fragment(1, 7_201L * 90_000))
        assertEquals(listOf(0L, 90_000L), tfdts(feed))
    }

    @Test
    fun aNewFileContinuesTheFeedOnlyWhenNothingChanged() {
        val feed = Feed()
        feed.push(init(trak(1, 90_000), trak(2, 16_000)))
        val same = init(trak(1, 90_000), trak(2, 48_000)) // a timescale may differ
        val videoOnly = init(trak(1, 90_000))
        assertTrue(PlaybackProtocol.continuesFeed(reset = false, current = feed, initSegment = same))
        assertFalse(PlaybackProtocol.continuesFeed(reset = true, current = feed, initSegment = same))
        assertFalse(PlaybackProtocol.continuesFeed(reset = false, current = feed, initSegment = videoOnly))
        assertFalse(PlaybackProtocol.continuesFeed(reset = false, current = null, initSegment = same))
        feed.close()
        assertFalse(PlaybackProtocol.continuesFeed(reset = false, current = feed, initSegment = same))
    }
}
