package com.js8call.example.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsAndGeoTest {

    private fun near(expected: LatLon, actual: LatLon?, toleranceKm: Double) {
        assertNotNull(actual)
        val d = Geo.distanceKm(expected, actual!!)
        assertTrue("off by $d km: $actual", d <= toleranceKm)
    }

    // ------------------------------------------------------------- geo

    @Test
    fun mgrsMatchesGeoTrans() {
        // Reference values from the NGA GeoTrans library (python "mgrs" package).
        val wm = LatLon(38.8895, -77.0353)
        assertEquals("18S UJ 23478 06483", Geo.latLonToMgrs(wm, 5))
        assertEquals("18S UJ 2347 0648", Geo.latLonToMgrs(wm, 4))
        assertEquals("18S UJ 23 06", Geo.latLonToMgrs(wm, 2))
        assertEquals("56H LH 34900 52288", Geo.latLonToMgrs(LatLon(-33.8568, 151.2153), 5)) // Sydney
        assertEquals("32V KN 97230 00510", Geo.latLonToMgrs(LatLon(60.39, 5.32), 5))       // Bergen (zone 32 exception)
        assertEquals("27W VM 54138 13689", Geo.latLonToMgrs(LatLon(64.1466, -21.9426), 5)) // Reykjavik
    }

    @Test
    fun mgrsRoundTripsAcrossTheGlobe() {
        val points = listOf(
            LatLon(38.8895, -77.0353), LatLon(-33.8568, 151.2153), LatLon(51.5007, -0.1246),
            LatLon(64.1466, -21.9426), LatLon(-54.8019, -68.3030), LatLon(0.5, 32.6), LatLon(60.39, 5.32),
        )
        for (p in points) {
            val mgrs = Geo.latLonToMgrs(p, 5)!!
            near(p, Geo.mgrsToLatLon(mgrs), 0.01)
            near(p, Geo.mgrsToLatLon(mgrs.replace(" ", "")), 0.01)
        }
    }

    @Test
    fun maidenheadBothWays() {
        near(LatLon(41.729, -72.708), Geo.maidenheadToLatLon("FN31pr"), 0.5)
        assertEquals("FN31PR", Geo.latLonToMaidenhead(LatLon(41.729, -72.708), 6))
        assertEquals("EM73", Geo.latLonToMaidenhead(LatLon(33.75, -84.39), 4))
        assertNull(Geo.maidenheadToLatLon("ZZ99"))
    }

    @Test
    fun parseLocationAcceptsAllThreeForms() {
        near(LatLon(38.8895, -77.0353), Geo.parseLocation("18S UJ 2348 0648"), 0.02)
        assertNotNull(Geo.parseLocation("EM73"))
        near(LatLon(35.1, -80.2), Geo.parseLocation("35.1, -80.2"), 0.01)
        assertNull(Geo.parseLocation("ROAD BLOCKED"))
    }

    // ------------------------------------------------------------- reports

    @Test
    fun encodeCleansValuesAndSkipsEmptyFields() {
        val line = Reports.encode(
            Reports.SALUTE,
            mapOf("S" to "about 20", "A" to "line at fuel: station", "L" to "17S NU 1234 5678", "U" to "", "T" to "181320Z"),
        )
        assertEquals("#SALUTE S:ABOUT 20 A:LINE AT FUEL STATION L:17S NU 1234 5678 T:181320Z", line)
    }

    @Test
    fun parseRoundTrips() {
        val values = mapOf("S" to "4", "A" to "MOVING NORTH", "L" to "18S UJ 2348 0648", "U" to "UNK", "T" to "181320Z", "E" to "2 TRUCKS")
        val r = Reports.parse(Reports.encode(Reports.SALUTE, values))!!
        assertEquals(Reports.SALUTE, r.type)
        assertEquals(values, r.values)
        near(LatLon(38.8895, -77.0353), r.location, 0.02)
    }

    @Test
    fun parseFindsTagAfterOtherTextAndIgnoresUnknownTags() {
        val r = Reports.parse("RELAY FROM W4QRP #CHECKIN S:GOOD L:EM73")!!
        assertEquals("GOOD", r.values["S"])
        assertEquals("EM73", r.values["L"])
        assertNull(Reports.parse("#WEATHER RAIN"))
        assertNull(Reports.parse("NO TAG HERE"))
    }

    @Test
    fun dtgFormat() {
        // 2026-11-18 13:42 UTC
        assertEquals("181342Z", Reports.dtg(1795009320000L))
    }

    // Found by the Kotlin fuzzer.
    @Test
    fun dtgRejectsImpossibleTimes() {
        val now = 1_795_000_000_000L
        assertNull(Reports.dtgToMs("271183", now))  // minute 83
        assertNull(Reports.dtgToMs("272500Z", now)) // hour 25
        assertNull(Reports.dtgToMs("001200Z", now)) // day 0
        assertNull(Reports.dtgToMs("321200Z", now)) // day 32
        assertNotNull(Reports.dtgToMs(Reports.dtg(now), now))
    }

    // Found by the Kotlin fuzzer: a square on the edge of zone 1 came out at longitude -180.6.
    @Test
    fun mgrsAtTheAntimeridianWraps() {
        val p = Geo.parseLocation("1RAP")
        assertNotNull(p)
        assertTrue("$p", p!!.lon in -180.0..180.0 && p.lat in -90.0..90.0)
    }
}
