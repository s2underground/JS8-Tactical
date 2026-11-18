package com.js8call.example.prop

import com.js8call.example.reports.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The model's coefficients were fitted to WSPR reception reports
 * (tools/propdata). These tests pin the Kotlin port to the Python reference
 * used for fitting, and check behaviour the reception data showed.
 */
class PropagationModelTest {

    private data class Golden(
        val txLat: Double, val txLon: Double, val rxLat: Double, val rxLon: Double,
        val timeMs: Long, val mhz: Double, val sfi: Double, val k: Double,
        val raw: Double, val score: Double, val muf: Double, val luf: Double,
    )

    /** From tools/propdata/model.py v2_raw with the shipped coefficients. */
    private val golden = listOf(
        Golden(36.0, -86.0, 40.0, -75.0, 1763490000000L, 7.078, 121.0, 0.3, raw = 0.298874, score = 0.505286, muf = 25.0807, luf = 2.6224),
        Golden(36.0, -86.0, 51.5, 0.0, 1763490000000L, 14.078, 121.0, 0.3, raw = 0.215446, score = 0.355766, muf = 23.8073, luf = 6.3277),
        Golden(36.0, -86.0, -33.9, 151.2, 1763438400000L, 14.078, 150.0, 4.0, raw = 0.000000, score = 0.000000, muf = 17.3841, luf = 19.3465),
        Golden(36.0, -86.0, 36.5, -85.0, 1763438400000L, 3.578, 90.0, 2.0, raw = 0.351561, score = 0.602572, muf = 5.4963, luf = 0.1310),
        Golden(65.0, -20.0, 35.0, 139.0, 1750000000000L, 21.078, 180.0, 5.5, raw = 0.000000, score = 0.000000, muf = 10.4703, luf = 13.4578),
        Golden(48.0, 2.0, 41.0, 12.5, 1750064400000L, 28.078, 130.0, 1.0, raw = 0.141071, score = 0.236875, muf = 19.9471, luf = 3.9568),
        Golden(36.0, -86.0, 37.0, -84.0, 1763484000000L, 7.078, 121.0, 0.3, raw = 0.484943, score = 0.841317, muf = 14.0288, luf = 1.1553),
        Golden(36.0, -86.0, 51.5, 0.0, 1763484000000L, 28.078, 121.0, 0.3, raw = 0.000000, score = 0.000000, muf = 26.1577, luf = 7.1804),
        Golden(-34.0, 18.5, 52.0, 5.0, 1700000000000L, 21.078, 160.0, 2.0, raw = 0.000000, score = 0.000000, muf = 19.0213, luf = 1.3051),
        Golden(10.0, -67.0, 40.0, -74.0, 1690000000000L, 24.922, 75.0, 3.7, raw = 0.021036, score = 0.079008, muf = 22.6114, luf = 0.6840),
    )

    @Test
    fun matchesPythonReference() {
        for (g in golden) {
            val wx = SpaceWeather(g.sfi, g.k, 0, "test")
            val p = PropagationModel.path(LatLon(g.txLat, g.txLon), LatLon(g.rxLat, g.rxLon), g.timeMs, wx)
            assertEquals("muf $g", g.muf, p.mufMHz, 1e-3)
            assertEquals("luf $g", g.luf, p.lufMHz, 1e-3)
            assertEquals("raw $g", g.raw, PropagationModel.rawScore(p, g.mhz), 1e-4)
            assertEquals("score $g", g.score, PropagationModel.reliability(p, g.mhz), 1e-4)
        }
    }

    private val atlanta = LatLon(33.75, -84.39)
    private fun utc(y: Int, mo: Int, d: Int, h: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
        clear(); set(y, mo - 1, d, h, 0, 0); timeInMillis
    }
    private val noonLocal = utc(2026, 10, 15, 17)   // 13:00 EDT
    private val midnightLocal = utc(2026, 10, 15, 5) // 01:00 EDT
    private val quiet = SpaceWeather(sfi = 150.0, kIndex = 1.0, updatedMs = 0, source = "test")

    private fun avg(km: Double, band: Band, time: Long, wx: SpaceWeather) =
        PropagationModel.stoplight(atlanta, band, time, wx, listOf(km)).single()

    @Test
    fun subsolarPointAtJuneSolsticeNoonUtc() {
        val s = Sun.subsolarPoint(utc(2026, 6, 21, 12))
        assertEquals(23.4, s.lat, 0.3)
        assertEquals(0.0, s.lon, 1.5)
    }

    @Test
    fun fortyMetresCoversShortRangeByDayAndSkipsAtNight() {
        assertTrue(avg(200.0, Band.B40, noonLocal, quiet) > 0.66)
        assertTrue(avg(200.0, Band.B40, midnightLocal, quiet) < 0.10)
        assertTrue(avg(200.0, Band.B80, midnightLocal, quiet) > 0.66)
    }

    @Test
    fun lowBandsAbsorbedAtMiddayAndOpenAtNight() {
        assertTrue(avg(800.0, Band.B80, noonLocal, quiet) < 0.33)
        assertTrue(avg(2000.0, Band.B80, midnightLocal, quiet) > 0.66)
        assertTrue(avg(2000.0, Band.B160, noonLocal, quiet) < 0.10)
    }

    @Test
    fun highBandsAreDaytimeLongHaulBands() {
        assertTrue(avg(2000.0, Band.B15, noonLocal, quiet) > 0.66)
        assertTrue(avg(2000.0, Band.B15, midnightLocal, quiet) < 0.10)
        assertTrue(avg(200.0, Band.B15, noonLocal, quiet) < 0.10)
    }

    @Test
    fun higherSolarFluxRaisesTheMuf() {
        val dest = PropagationModel.destination(atlanta, 45.0, 3000.0)
        val low = PropagationModel.path(atlanta, dest, noonLocal, quiet.copy(sfi = 70.0))
        val high = PropagationModel.path(atlanta, dest, noonLocal, quiet.copy(sfi = 220.0))
        assertTrue(high.mufMHz > low.mufMHz * 1.15)
    }

    @Test
    fun geomagneticStormLowersMuf() {
        val dest = PropagationModel.destination(atlanta, 0.0, 2000.0)
        val calm = PropagationModel.path(atlanta, dest, noonLocal, quiet)
        val storm = PropagationModel.path(atlanta, dest, noonLocal, quiet.copy(kIndex = 8.0))
        assertTrue(storm.mufMHz < calm.mufMHz * 0.9)
    }

    @Test
    fun summerSporadicEOpensTenMetresShortSkip() {
        val june = utc(2026, 6, 20, 17)
        val dest = PropagationModel.destination(atlanta, 0.0, 1200.0)
        val es = PropagationModel.path(atlanta, dest, june, quiet.copy(sfi = 75.0))
        val winter = PropagationModel.path(atlanta, dest, utc(2026, 12, 20, 17), quiet.copy(sfi = 75.0))
        assertTrue(es.esBase > 0.05)
        assertTrue(winter.esBase < 0.01)
    }

    @Test
    fun calibrationIsMonotonic() {
        var last = -1.0
        var x = 0.0
        while (x <= 1.0) {
            val v = PropagationModel.calibrate(x)
            assertTrue(v >= last)
            last = v
            x += 0.01
        }
        assertEquals(1.0, PropagationModel.calibrate(1.0), 1e-9)
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
