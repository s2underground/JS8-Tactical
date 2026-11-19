package com.js8call.example.atak

import com.js8call.example.reports.LatLon
import com.js8call.example.reports.Reports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class CotTest {

    private val now = 1794960000000L // 2026-11-18T00:00:00Z

    private fun parse(xml: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream()).documentElement

    private fun Element.child(name: String): Element = getElementsByTagName(name).item(0) as Element

    @Test
    fun salutReportBecomesMagentaSpotMapWithFullRemarks() {
        val r = Reports.parse("#SALUTE S:4 A:LINE AT FUEL STATION L:17SNU12345678 U:CIVILIANS T:181320Z E:CARS")!!
        val p = Cot.reportPoint(r, "KX4ABC", r.location!!, now - 60_000, 42L, 24 * 3_600_000L, now)
        val e = parse(Cot.xml(p, now))
        assertEquals("event", e.tagName)
        assertEquals("b-m-p-s-m", e.getAttribute("type"))
        assertEquals("JS8-Tactical.KX4ABC.SALUTE.42", e.getAttribute("uid"))
        assertEquals("2026-11-18T00:00:00.000Z", e.getAttribute("time"))
        assertEquals("2026-11-18T23:59:00.000Z", e.getAttribute("stale"))  // 24 h after heard
        assertEquals("SALUTE KX4ABC", e.child("contact").getAttribute("callsign"))
        assertEquals(Cot.MAGENTA.toString(), e.child("color").getAttribute("argb"))
        assertEquals("COT_MAPPING_SPOTMAP/b-m-p-s-m/${Cot.MAGENTA}", e.child("usericon").getAttribute("iconsetpath"))
        assertEquals(7.071, e.child("point").getAttribute("ce").toDouble(), 0.01) // 8-digit MGRS = 10 m cell
        val remarks = e.child("remarks").textContent
        assertTrue(remarks, remarks.contains("Activity: LINE AT FUEL STATION"))
        assertTrue(remarks, remarks.contains("Equipment: CARS"))
        assertTrue(remarks, remarks.contains("±7 m"))
    }

    @Test
    fun checkinColoursFollowStatus() {
        fun color(text: String) = Cot.colorFor(Reports.parse(text)!!)
        assertEquals(Cot.RED, color("#CHECKIN S:EMERG L:EM12"))
        assertEquals(Cot.YELLOW, color("#CHECKIN S:NEED L:EM12"))
        assertEquals(Cot.GREEN, color("#CHECKIN S:GOOD L:EM12"))
        assertEquals(Cot.RED, color("#SITREP S:RED L:EM12"))
        assertEquals(Cot.CYAN, color("#POSREP L:EM12"))
    }

    @Test
    fun checkinNameShowsStatus() {
        val r = Reports.parse("#CHECKIN S:EMERG L:EM12KX N:BROKEN LEG")!!
        val p = Cot.reportPoint(r, "W1AW", r.location!!, now, 7L, 3_600_000L, now)
        assertEquals("CHECKIN W1AW (EMERG)", p.callsign)
    }

    @Test
    fun precisionFollowsWhatWasSent() {
        assertEquals(70_710.0, Cot.ceFor("17SNU"), 1.0)          // 100 km square
        assertEquals(7.071, Cot.ceFor("17S NU 1234 5678"), 0.01)
        assertEquals(0.7071, Cot.ceFor("17SNU1234567890"), 0.001)
        assertTrue(Cot.ceFor("EM12") in 100_000.0..120_000.0)     // 1° × 2° at 32 N: ~111 × 188 km
        assertTrue(Cot.ceFor("EM12KX") in 2_500.0..5_000.0)
        assertEquals(10.0, Cot.ceFor("32.7767,-96.7970"), 0.0)
        assertEquals(9_999_999.0, Cot.ceFor("SOMEWHERE"), 0.0)
        assertEquals("±7 m", Cot.precisionText("17SNU12345678"))
    }

    @Test
    fun hostileTextIsEscapedAndStillValidXml() {
        val r = Reports.parse("#SPOT S:2 A:<script>&\"' L:EM12")!!
        val p = Cot.reportPoint(r, "N0CALL", r.location!!, now, 1L, 3_600_000L, now)
        val e = parse(Cot.xml(p, now))
        assertTrue(e.child("remarks").textContent.contains("<SCRIPT>&\"'"))
        // Odd characters in a callsign can't break the uid.
        val q = Cot.reportPoint(r, "N0CALL/P \"X\"", r.location!!, now, 1L, 3_600_000L, now)
        assertTrue(q.uid, Regex("[A-Za-z0-9._/-]+").matches(q.uid))
        parse(Cot.xml(q, now))
    }

    @Test
    fun staleCountsFromWhenHeard() {
        // A report replayed from history is not made fresh again.
        val r = Reports.parse("#POSREP L:EM12")!!
        val p = Cot.reportPoint(r, "W1AW", r.location!!, now - 10 * 3_600_000L, 1L, 3_600_000L, now)
        assertEquals(now - 9 * 3_600_000L, p.staleMs)
    }

    @Test
    fun stationAndDeleteEvents() {
        val s = Cot.stationPoint("W1AW", "FN31", LatLon(41.5, -73.0), now, -12, 3_600_000L, now)
        val e = parse(Cot.xml(s, now))
        assertEquals("W1AW (JS8)", e.child("contact").getAttribute("callsign"))
        assertEquals(Cot.WHITE.toString(), e.child("color").getAttribute("argb"))

        val d = parse(Cot.deleteXml(s.uid, now))
        assertEquals("t-x-d-d", d.getAttribute("type"))
        assertEquals(s.uid, d.child("link").getAttribute("uid"))
        assertEquals(1, d.getElementsByTagName("__forcedelete").length)
    }

    @Test
    fun signatureIgnoresStaleButSeesContent() {
        val r = Reports.parse("#POSREP L:EM12")!!
        val a = Cot.reportPoint(r, "W1AW", r.location!!, now, 1L, 3_600_000L, now)
        val b = Cot.reportPoint(r, "W1AW", r.location!!, now, 1L, 3_600_000L, now + 60_000)
        assertEquals(a.signature, b.signature)
        val r2 = Reports.parse("#POSREP L:EM12 S:MOVING")!!
        val c = Cot.reportPoint(r2, "W1AW", r2.location!!, now, 1L, 3_600_000L, now)
        assertTrue(a.signature != c.signature)
    }
}
