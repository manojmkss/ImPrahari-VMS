package com.imprahari.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ClipMathTest {
    private val now = 1_763_465_000.0

    @Test
    fun `a new clip is a minute around the moment, never in the future`() {
        assertEquals((now - 630) to (now - 570), ClipMath.defaultRange(now - 600, now))
        val (s, e) = ClipMath.defaultRange(now - 10, now)
        assertEquals(now, e, 0.0)
        assertEquals(now - 40, s, 0.0)
        val (s2, e2) = ClipMath.defaultRange(now + 5, now) // a clock a little ahead
        assertTrue(e2 - s2 >= ClipMath.MIN_SECONDS && e2 <= now)
    }

    @Test
    fun `the trim bar holds still while the clip fits and follows it when it doesn't`() {
        val w = ClipMath.window(now - 700, now - 600, 600.0, now)
        assertEquals(600.0, w.second - w.first, 0.0)
        assertTrue(w.first <= now - 700 && w.second >= now - 600)
        assertSame(w, ClipMath.window(now - 690, now - 610, 600.0, now, w)) // dragging inside: same window
        val moved = ClipMath.window(now - 2000, now - 610, 600.0, now, w)
        assertTrue(moved.second - moved.first >= 1390) // grew to hold the clip
        assertTrue(moved.first <= now - 2000)
        val atNow = ClipMath.window(now - 20, now, 600.0, now)
        assertEquals(now, atNow.second, 0.0) // never past now
    }

    @Test
    fun `ranges the NVR would refuse are caught before asking it`() {
        assertNull(ClipMath.problem(0.0, 60.0))
        assertNotNull(ClipMath.problem(60.0, 60.0))
        assertNotNull(ClipMath.problem(60.0, 30.0))
        assertNull(ClipMath.problem(0.0, ClipMath.MAX_SECONDS))
        assertNotNull(ClipMath.problem(0.0, ClipMath.MAX_SECONDS + 1))
    }

    @Test
    fun `footage in the range is measured from the timeline`() {
        val spans = listOf(listOf(0.0, 100.0), listOf(200.0, 300.0))
        assertEquals(100.0, ClipMath.covered(spans, 50.0, 250.0), 0.0)
        assertEquals(0.0, ClipMath.covered(spans, 120.0, 180.0), 0.0)
    }

    @Test
    fun `file names are readable and safe`() {
        val utc = ZoneId.of("UTC")
        assertEquals("Front door 2025-11-18 11-23-20.mp4", ClipMath.fileName("Front door", now, utc))
        assertEquals("Gate A B 2025-11-18 11-23-20.mp4", ClipMath.fileName(" Gate/A:B? ", now, utc))
        assertEquals("Camera 2025-11-18 11-23-20.mp4", ClipMath.fileName("///", now, utc))
        assertEquals("1 min 05 s", ClipMath.length(65.0))
        assertEquals("1 h 20 min", ClipMath.length(4800.0))
    }

    @Test
    fun `export address uses plain decimals`() {
        assertEquals(
            "/api/recordings/export?camera_id=3&start=1763465000.000&end=1763465060.500&h264=true",
            ClipMath.exportPath(3, now, now + 60.5, true),
        )
        assertTrue(!ClipMath.exportPath(3, now, now + 1, false).contains("h264"))
    }
}
