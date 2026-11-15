package com.lightnvr.companion.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomTest {
    private val view = Size(1000f, 500f)

    /** Where a picture point (px from the picture's centre) lands on screen. */
    private fun screenOf(p: Offset, scale: Float, offset: Offset) =
        p * scale + offset + Offset(view.width / 2, view.height / 2)

    @Test
    fun pinchingKeepsThePointUnderTheFingers() {
        val centroid = Offset(800f, 100f) // top right
        val before = Offset(800f, 100f) - Offset(500f, 250f) // picture point under it at 1x
        val (s, o) = ZoomMath.transform(1f, Offset.Zero, view, centroid, Offset.Zero, 2f)
        assertEquals(2f, s, 0f)
        val after = screenOf(before, s, o)
        assertEquals(centroid.x, after.x, 0.01f)
        assertEquals(centroid.y, after.y, 0.01f)
    }

    @Test
    fun neverZoomsOutPastTheWholePictureNorBeyond8x() {
        assertEquals(1f, ZoomMath.transform(1f, Offset.Zero, view, Offset(500f, 250f), Offset.Zero, 0.3f).first, 0f)
        assertEquals(8f, ZoomMath.transform(6f, Offset.Zero, view, Offset(500f, 250f), Offset.Zero, 3f).first, 0f)
        // Back at 1x the picture is centred again.
        assertEquals(Offset.Zero, ZoomMath.transform(2f, Offset(300f, 100f), view, Offset(10f, 10f), Offset.Zero, 0.1f).second)
    }

    @Test
    fun panningStopsAtThePicturesEdges() {
        // At 2x the picture is 2000x1000: it can shift at most 500 / 250.
        val (_, o) = ZoomMath.transform(2f, Offset.Zero, view, Offset(500f, 250f), Offset(5000f, -5000f), 1f)
        assertEquals(Offset(500f, -250f), o)
    }

    @Test
    fun doubleTapZoomsIntoTheSpotThenBackOut() {
        val tap = Offset(250f, 125f)
        val (s, o) = ZoomMath.doubleTap(1f, view, tap)
        assertEquals(ZoomMath.DOUBLE_TAP_SCALE, s, 0f)
        val under = tap - Offset(500f, 250f)
        val after = screenOf(under, s, o)
        assertEquals(tap.x, after.x, 0.01f) // the tapped spot stays put
        assertEquals(tap.y, after.y, 0.01f)
        assertEquals(1f to Offset.Zero, ZoomMath.doubleTap(s, view, tap))
    }

    @Test
    fun miniMapShowsTheVisiblePart() {
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), ZoomMath.visible(1f, Offset.Zero, view), 1e-5f)
        // 2x, shifted fully right (we see the left half of the picture).
        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.5f, 0.5f), ZoomMath.visible(2f, Offset(500f, 0f), view), 1e-5f)
    }
}
