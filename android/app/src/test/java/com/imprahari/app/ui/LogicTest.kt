package com.imprahari.app.ui

import com.imprahari.app.net.ApiClient
import com.imprahari.app.net.VisitFilter
import com.imprahari.app.push.AlertPayload
import com.imprahari.app.security.Modes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LogicTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    @Test
    fun scrubBarMapsTimeToPixelsAndBack() {
        val center = 1_800_000_000.0
        val window = 3600.0
        assertEquals(500f, TimelineMath.xOf(center, center, window, 1000f), 1e-3f)
        assertEquals(0f, TimelineMath.xOf(center - 1800, center, window, 1000f), 1e-3f)
        assertEquals(center + 900, TimelineMath.wallOf(750f, center, window, 1000f), 1e-6)
        for (x in listOf(0f, 123f, 999f)) {
            assertEquals(x, TimelineMath.xOf(TimelineMath.wallOf(x, center, window, 1000f), center, window, 1000f), 1e-2f)
        }
    }

    @Test
    fun pickingAMomentWithoutFootageGoesToTheNextFootage() {
        val spans = listOf(listOf(100.0, 200.0), listOf(500.0, 900.0))
        assertEquals(150.0, TimelineMath.snap(spans, 150.0)!!, 0.0)
        assertEquals(500.0, TimelineMath.snap(spans, 300.0)!!, 0.0) // gap -> next footage
        assertEquals(890.0, TimelineMath.snap(spans, 2000.0)!!, 0.0) // after the last -> near its end
        assertNull(TimelineMath.snap(emptyList(), 10.0))
        assertTrue(TimelineMath.covered(spans, 900.0))
        assertFalse(TimelineMath.covered(spans, 901.0))
    }

    @Test
    fun ticksFallOnLocalClockSteps() {
        // 2026-11-15 10:07:30 IST = 04:37:30 UTC.
        val t = java.time.ZonedDateTime.of(2026, 11, 15, 10, 7, 30, 0, ist).toEpochSecond().toDouble()
        val ticks = TimelineMath.ticks(t, 3600.0, ist) // 10-minute steps, 09:37:30 .. 10:37:30
        val labels = ticks.map { java.time.Instant.ofEpochSecond(it.toLong()).atZone(ist).toLocalTime().toString() }
        assertEquals(listOf("09:40", "09:50", "10:00", "10:10", "10:20", "10:30"), labels)
        assertEquals(
            java.time.ZonedDateTime.of(2026, 11, 15, 0, 0, 0, 0, ist).toEpochSecond().toDouble(),
            TimelineMath.dayStart(t, ist), 0.0,
        )
    }

    @Test
    fun visitsQueryCarriesEveryFilter() {
        val path = ApiClient.visitsPath(
            1_794_700_000, 1_794_786_400,
            VisitFilter(person = "Asha Rao", what = "vehicles", cameraId = 3, group = "Front & side"),
        )
        assertEquals(
            "/api/security/incidents?limit=200&start=2026-11-14T23%3A46%3A40Z&end=2026-11-15T23%3A46%3A40Z" +
                "&person=Asha%20Rao&what=vehicles&camera_id=3&group=Front%20%26%20side",
            path,
        )
        val everything = ApiClient.visitsPath(0, 1, VisitFilter(what = null))
        assertFalse(everything.contains("what="))
        assertTrue(ApiClient.visitsPath(0, 1, VisitFilter(person = VisitFilter.STRANGERS)).contains("person=__strangers__"))
    }

    @Test
    fun alertPayloadFromTheNvr() {
        val json = """{"title":"Gate","body":"Person at the gate\nmore","url":"/events/9","tag":"motion-1",
            "event_id":9,"urgent":true,"image":"/api/public/events/9/snapshot.jpg?exp=1&sig=x",
            "camera_id":1,"at":1794700000.5,"something_new":1}"""
        val a = AlertPayload.parse(json.toByteArray())!!
        assertEquals(9, a.eventId)
        assertEquals(1, a.cameraId)
        assertEquals(1794700000.5, a.at!!, 0.0)
        assertTrue(a.urgent)
        // An older NVR without camera/time still gives a notification.
        val old = AlertPayload.parse("""{"title":"T","body":"B"}""".toByteArray())!!
        assertNull(old.cameraId)
        assertNull(AlertPayload.parse("not json".toByteArray()))
    }

    @Test
    fun tileArmsAndDisarms() {
        assertEquals("away", Modes.toggled("home"))
        assertEquals("away", Modes.toggled("disarmed"))
        assertEquals("home", Modes.toggled("away"))
        assertEquals("home", Modes.toggled("night"))
        assertEquals("Off", Modes.label("disarmed"))
    }
}
