package com.js8call.example.prop

import com.js8call.example.reports.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Sanity checks against well-known HF behaviour (not precise predictions):
 * the model must get the obvious cases right.
 */
class PropagationModelTest {

    private val atlanta = LatLon(33.75, -84.39)
    private fun utc(y: Int, mo: Int, d: Int, h: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
        clear(); set(y, mo - 1, d, h, 0, 0); timeInMillis
    }
    private val noonLocal = utc(2026, 10, 15, 17)   // 13:00 EDT, sun high
    private val midnightLocal = utc(2026, 10, 15, 5) // 01:00 EDT
    private val quiet = SpaceWeather(sfi = 150.0, kIndex = 1.0, updatedMs = 0, source = "test")
    private val lowSolar = SpaceWeather(sfi = 70.0, kIndex = 1.0, updatedMs = 0, source = "test")
    private val highSolar = SpaceWeather(sfi = 220.0, kIndex = 1.0, updatedMs = 0, source = "test")

    private fun rel(km: Double, band: Band, time: Long, wx: SpaceWeather, bearing: Double = 0.0): Double {
        val dest = PropagationModel.destination(atlanta, bearing, km)
        return PropagationModel.reliability(PropagationModel.path(atlanta, dest, time, wx), band.mhz)
    }

    @Test
    fun subsolarPointAtJuneSolsticeNoonUtc() {
        val s = Sun.subsolarPoint(utc(2026, 6, 21, 12))
        assertEquals(23.4, s.lat, 0.3)
        assertEquals(0.0, s.lon, 1.5)
    }

    @Test
    fun fortyMetresWorksAtNightRegionally() =
        assertTrue(rel(1000.0, Band.B40, midnightLocal, quiet) > 0.8)

    @Test
    fun twentyMetresClosedAtNightAtLowSolar() =
        assertTrue(rel(2000.0, Band.B20, midnightLocal, lowSolar) < 0.2)

    @Test
    fun twentyMetresOpenInDaytime() =
        assertTrue(rel(2000.0, Band.B20, noonLocal, quiet) > 0.8)

    @Test
    fun tenMetresNeedsHighSolarActivity() {
        assertTrue(rel(2500.0, Band.B10, noonLocal, lowSolar) < 0.2)
        assertTrue(rel(2500.0, Band.B10, noonLocal, highSolar) > 0.6)
    }

    @Test
    fun eightyMetresAbsorbedAtMiddayOverDistance() {
        assertTrue(rel(1500.0, Band.B80, noonLocal, quiet) < 0.3)
        assertTrue(rel(1500.0, Band.B80, midnightLocal, quiet) > 0.8)
    }

    @Test
    fun shortRangeUsesFortyByDayAndEightyByNight() {
        // 40m NVIS works in daylight; at night foF2 drops below 7 MHz and 40m skips over
        // nearby stations (the familiar night-time skip zone), while 80m covers them.
        assertTrue(rel(200.0, Band.B40, noonLocal, quiet) > 0.8)
        assertTrue(rel(200.0, Band.B40, midnightLocal, quiet) < 0.2)
        assertTrue(rel(200.0, Band.B80, midnightLocal, quiet) > 0.8)
        // Ground wave still covers very short range.
        assertTrue(rel(20.0, Band.B80, noonLocal, quiet) > 0.5)
    }

    @Test
    fun geomagneticStormLowersMuf() {
        val dest = PropagationModel.destination(atlanta, 0.0, 2000.0)
        val calm = PropagationModel.path(atlanta, dest, noonLocal, quiet)
        val storm = PropagationModel.path(atlanta, dest, noonLocal, quiet.copy(kIndex = 8.0))
        assertTrue(storm.mufMHz < calm.mufMHz * 0.9)
    }

    @Test
    fun stoplightHasOneValuePerBucket() {
        val buckets = listOf(200.0, 800.0, 2000.0, 5000.0)
        val s = PropagationModel.stoplight(atlanta, Band.B40, midnightLocal, quiet, buckets)
        assertEquals(4, s.size)
        assertTrue(s.all { it in 0.0..1.0 })
    }

    @Test
    fun bandForDialFrequency() {
        assertEquals(Band.B40, Band.forDialHz(7_078_000))
        assertEquals(Band.B20, Band.forDialHz(14_078_000))
        assertEquals(null, Band.forDialHz(145_000_000))
    }
}
