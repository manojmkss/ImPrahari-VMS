package com.imprahari.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {
    // ---- tiny MP4 box builders ------------------------------------------------
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

    private fun init(vararg traks: ByteArray) =
        box("ftyp", "isom".toByteArray(), u32(0)) + box("moov", *traks)

    private fun fragment(seq: Long, trackId: Long, time: Long, v1: Boolean = true) = box(
        "moof",
        fullBox("mfhd", 0, u32(seq)),
        box("traf", fullBox("tfhd", 0, u32(trackId)), fullBox("tfdt", if (v1) 1 else 0, if (v1) u64(time) else u32(time))),
    ) + box("mdat", byteArrayOf(1, 2, 3, 4))

    /** All boxes out of the feed, in order, until it runs dry. */
    private fun drain(feed: Feed): List<ByteArray> {
        feed.close()
        return generateSequence { feed.take() }.toList()
    }

    private fun tfdtOf(moof: ByteArray, trackId: Int): Long {
        val root = Mp4.Box("moof", 0, 8, moof.size)
        for (traf in Mp4.children(moof, root.payload, root.end).filter { it.type == "traf" }) {
            val tfhd = Mp4.child(moof, traf, "tfhd")!!
            if (Mp4.u32(moof, tfhd.payload + 4).toInt() != trackId) continue
            val tfdt = Mp4.child(moof, traf, "tfdt")!!
            return if (moof[tfdt.payload].toInt() == 1) Mp4.u64(moof, tfdt.payload + 4) else Mp4.u32(moof, tfdt.payload + 4)
        }
        error("no traf for track $trackId")
    }

    // ---- tests -----------------------------------------------------------------
    @Test
    fun readsTrackTimescalesFromInit() {
        val i = init(trak(1, 90_000), trak(2, 16_000))
        assertEquals(mapOf(1 to 90_000L, 2 to 16_000L), Mp4.trackTimescales(i))
    }

    @Test
    fun rebasesAStreamJoinedHoursInToStartAtZero() {
        val hourIn = 3_600L * 90_000
        val feed = Feed()
        feed.push(init(trak(1, 90_000)) + fragment(1, 1, hourIn) + fragment(2, 1, hourIn + 3_000))
        val boxes = drain(feed)
        assertEquals(listOf("ftyp", "moov", "moof", "mdat", "moof", "mdat"), boxes.map { Mp4.type(it, 4) })
        assertEquals(0L, tfdtOf(boxes[2], 1))
        assertEquals(3_000L, tfdtOf(boxes[4], 1))
    }

    @Test
    fun rebasesVersion0Tfdt() {
        val feed = Feed()
        feed.push(init(trak(1, 1_000)) + fragment(1, 1, 50_000, v1 = false) + fragment(2, 1, 50_040, v1 = false))
        val moofs = drain(feed).filter { Mp4.type(it, 4) == "moof" }
        assertEquals(listOf(0L, 40L), moofs.map { tfdtOf(it, 1) })
    }

    @Test
    fun boxesSplitAcrossMessagesAreReassembled() {
        val stream = init(trak(1, 90_000)) + fragment(1, 1, 900_000) + fragment(2, 1, 903_000)
        val whole = Feed().also { it.push(stream) }.let(::drain)
        // Worst case: the socket hands us one byte at a time.
        val trickled = Feed().also { f -> stream.forEach { f.push(byteArrayOf(it)) } }.let(::drain)
        assertEquals(whole.map { it.toList() }, trickled.map { it.toList() })
    }

    @Test
    fun audioAndVideoShareOneOriginSoTheyStayInSync() {
        // Video at t=3600.000 s (90 kHz), audio at t=3600.100 s (16 kHz).
        val feed = Feed()
        feed.push(
            init(trak(1, 90_000), trak(2, 16_000)) +
                fragment(1, 1, 3_600L * 90_000) +
                fragment(2, 2, 3_600L * 16_000 + 1_600)
        )
        val moofs = drain(feed).filter { Mp4.type(it, 4) == "moof" }
        assertEquals(0L, tfdtOf(moofs[0], 1))
        assertEquals(1_600L, tfdtOf(moofs[1], 2)) // still 0.1 s after the video
    }

    @Test
    fun overflowClosesTheFeedAndSignals() {
        var overflowed = false
        val feed = Feed(maxQueuedBytes = 100, onOverflow = { overflowed = true })
        feed.push(init(trak(1, 90_000)) + fragment(1, 1, 0) + fragment(2, 1, 3_000))
        assertTrue(overflowed)
        assertTrue(feed.closed)
    }

    @Test
    fun corruptFramingClosesTheFeed() {
        var signalled = false
        val feed = Feed(onOverflow = { signalled = true })
        feed.push(u32(3) + "junk".toByteArray()) // size smaller than a box header
        assertTrue(signalled)
        assertNull(feed.take())
    }

    @Test
    fun endOfStreamIsSticky() {
        val feed = Feed()
        feed.close()
        assertNull(feed.take())
        assertNull(feed.take())
    }
}
