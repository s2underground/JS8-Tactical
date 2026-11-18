package com.js8call.example.prop

import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.example.reports.Reports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class StationAndLearningTest {

    private val me = LatLon(36.0, -86.0)

    // ---------------------------------------------------------------- station profile

    @Test
    fun slowerModesKeepMorePathsThanFasterOnes() {
        assertEquals(1.0, StationModel.retained(Js8Speed.SLOW.thresholdDb), 1e-9)
        assertEquals(0.878, StationModel.retained(Js8Speed.NORMAL.thresholdDb), 1e-9)
        assertTrue(StationModel.retained(Js8Speed.TURBO.thresholdDb) < StationModel.retained(Js8Speed.FAST.thresholdDb))
        assertTrue(StationModel.retained(-38.0) > 1.3) // 10 dB better than the WSPR floor
    }

    @Test
    fun takeOffAngleFallsWithHopLength() {
        assertTrue(StationModel.elevationDeg(100.0) > 70)
        val mid = StationModel.elevationDeg(1000.0)
        assertTrue(mid in 15.0..40.0)
        assertTrue(StationModel.elevationDeg(3200.0) < 8)
    }

    @Test
    fun moreMarginMeansHigherScoreButNeverAboveOne() {
        val dest = PropagationModel.destination(me, 45.0, 1200.0)
        val wx = SpaceWeather(130.0, 2.0, 0, "t")
        val path = PropagationModel.path(me, dest, 1763490000000L, wx)
        val qrp = StationProfile(powerW = 1.0, speedOverride = Js8Speed.TURBO)
        val qro = StationProfile(powerW = 100.0, speedOverride = Js8Speed.SLOW)
        assertTrue(StationModel.marginDb(qro, path, Band.B20) - StationModel.marginDb(qrp, path, Band.B20) > 25)
        assertTrue(StationModel.adjust(0.4, qro, path, Band.B20) > StationModel.adjust(0.4, qrp, path, Band.B20))
        assertTrue(StationModel.adjust(0.9, qro, path, Band.B20) <= 1.0)
    }

    @Test
    fun nvisDipoleFavoursCloseInAndVerticalFavoursFarOut() {
        val near = StationModel.elevationDeg(150.0)
        val far = StationModel.elevationDeg(3000.0)
        assertTrue(Antenna.NVIS_DIPOLE.gainDb(near, Band.B40) > Antenna.VERTICAL.gainDb(near, Band.B40) + 10)
        assertTrue(Antenna.VERTICAL.gainDb(far, Band.B20) > Antenna.NVIS_DIPOLE.gainDb(far, Band.B20) + 8)
        assertTrue(Antenna.MOBILE_WHIP.gainDb(30.0, Band.B80) < Antenna.MOBILE_WHIP.gainDb(30.0, Band.B10) - 10)
    }

    // ---------------------------------------------------------------- learning

    private fun sigmoid(x: Double) = 1 / (1 + exp(-x))

    @Test
    fun learnsAStationThatIsBetterCloseInOnFortyMetres() {
        val rnd = Random(7)
        val pairs = ArrayList<LearningPair>()
        val far = PropagationModel.destination(me, 90.0, 1500.0)
        val near = PropagationModel.destination(me, 90.0, 150.0)
        repeat(1500) { i ->
            val isNear = i % 2 == 0
            val truth = sigmoid(ln(0.4 / 0.6) - 0.5 + if (isNear) 1.5 else 0.0)
            pairs += LearningPair(i * 3_600_000L, "S$i", if (isNear) near else far, Band.B40, rnd.nextDouble() < truth, if (isNear) 150.0 else 1500.0)
        }
        val c = Learner.fit(pairs) { 0.4 }
        val nearOff = c.offset(Band.B40, 150.0)
        val farOff = c.offset(Band.B40, 1500.0)
        assertTrue("near $nearOff far $farOff", nearOff - farOff > 1.0)
        assertEquals(0.0, c.offset(Band.B20, 150.0), 1e-9)
        assertTrue(c.adjust(0.4, Band.B40, 150.0) > 0.4)
    }

    @Test
    fun aFewLuckyContactsBarelyMoveTheCorrection() {
        val near = PropagationModel.destination(me, 90.0, 150.0)
        val pairs = (0 until 12).map { LearningPair(it * 3_600_000L, "S$it", near, Band.B80, true, 150.0) }
        val c = Learner.fit(pairs) { 0.5 }
        assertTrue(abs(c.offset(Band.B80, 150.0)) < 1.0)
    }

    @Test
    fun listeningHoursAndHeardStationsBecomeTests() {
        val hour = ListeningLog.hourStart(1_763_460_000_000L)
        val obs = listOf(
            Observation(hour + 600_000, "N0DEF", "EM74", Band.B40, -12, Observation.Kind.HEARD),
            Observation(hour - 3_000_000, "W4QRP", "EM83", Band.B40, -15, Observation.Kind.HEARD), // heard just before
            Observation(hour + 900_000, "K1ABC", null, Band.B40, -9, Observation.Kind.PATH, by = "N0DEF"),
        )
        val grids = mapOf("K1ABC" to "FN42")
        val pairs = Learner.pairs(obs, mapOf((hour to Band.B40) to 45.0), { grids[it] }, me)
        assertEquals(setOf("N0DEF", "W4QRP", "K1ABC"), pairs.map { it.call }.toSet())
        assertTrue(pairs.first { it.call == "N0DEF" }.heard)
        assertTrue(!pairs.first { it.call == "W4QRP" }.heard)
        // Hours we weren't listening produce nothing.
        assertTrue(Learner.pairs(obs, emptyMap(), { grids[it] }, me).isEmpty())
    }

    @Test
    fun nowcastFindsAStrongerIonosphereAndStaysPutWithoutEvidence() {
        val wx = SpaceWeather(120.0, 1.0, 0, "t")
        val t = 1763490000000L // 18:20Z
        val truth = Predictor(me, wx, StationProfile(adjust = false, learn = false), nowcast = Nowcast(1.2, 0))
        val sky = PropagationModel.Sky(t)
        val rnd = Random(3)
        val own = (0 until 400).map { i ->
            val to = PropagationModel.destination(me, (i * 37 % 360).toDouble(), 500.0 + (i % 20) * 200)
            val band = Band.entries[i % Band.entries.size]
            val s = truth.score(to, sky, band, 1.2)
            LearningPair(t, "S$i", to, band, rnd.nextDouble() < s.coerceIn(0.02, 0.98), Geo.distanceKm(me, to))
        }
        val base = Predictor(me, wx, StationProfile(adjust = false, learn = false))
        val nc = Learner.nowcast(own, emptyList(), emptyList(), emptyList(), 0.0) { from, to, band, time, phi ->
            if (from == null) base.score(to, PropagationModel.Sky(time), band, phi) else 0.5
        }
        assertTrue("phi ${nc.phi}", nc.phi > 1.05)
        val none = Learner.nowcast(own.take(5), emptyList(), emptyList(), emptyList(), 0.0) { _, _, _, _, _ -> 0.5 }
        assertEquals(1.0, none.phi, 1e-9)
    }

    @Test
    fun listeningLogPersistsAndCompacts() {
        val f = File(Files.createTempDirectory("listen").toFile(), "l.csv")
        val now = System.currentTimeMillis()
        val log = ListeningLog(f)
        repeat(30) { log.add(now, Band.B40, 1.0) }
        log.add(now, Band.B20, 5.0)
        log.flush()
        val again = ListeningLog(f)
        assertEquals(30.0, again.hours()[ListeningLog.hourStart(now) to Band.B40]!!, 1e-6)
        assertNull(again.hours()[ListeningLog.hourStart(now) to Band.B20]) // under 20 minutes
        assertEquals(35.0 / 60, again.totalHours(), 1e-6)
    }

    // ---------------------------------------------------------------- solar data off-grid

    private val outlook = """
        :Product: 27-day Space Weather Outlook Table 27DO.txt
        :Issued: 2026 Nov 16 0130 UTC
        #   UTC      Radio Flux   Planetary   Largest
        #  Date       10.7 cm      A Index    Kp Index
        2026 Nov 16     125           5          2
        2026 Nov 17     120          12          4
        2026 Nov 18     118           8          3
    """.trimIndent()

    @Test
    fun parsesTheNoaaOutlook() {
        val days = SpaceWeatherRepository.parseOutlook(outlook)
        assertEquals(3, days.size)
        assertEquals("2026-11-17", days[1].date)
        assertEquals(120.0, days[1].flux, 1e-9)
        assertEquals(12, days[1].ap)
        assertEquals(4.0, days[1].kpMax, 1e-9)
    }

    @Test
    fun fallsBackFromLiveToOutlookToSolarRotation() {
        val now = 1_794_960_000_000L + 12 * 3_600_000L // 2026-11-18 12:00Z
        val forecast = SpaceWeatherRepository.parseOutlook(outlook)
        val fresh = SpaceWeather(130.0, 2.0, now - 3_600_000L, SpaceWeatherRepository.SOURCE_NOAA)
        assertEquals(fresh, SpaceWeatherRepository.choose(fresh, forecast, emptyMap(), now, now))
        val stale = fresh.copy(updatedMs = now - 5 * 86_400_000L)
        val f = SpaceWeatherRepository.choose(stale, forecast, emptyMap(), now, now)
        assertEquals(SpaceWeatherRepository.SOURCE_FORECAST, f.source)
        assertEquals(118.0, f.sfi, 1e-9)
        val history = mapOf(SpaceWeatherRepository.dateKey(now + 10 * 86_400_000L - 27 * 86_400_000L) to 99.0)
        val r = SpaceWeatherRepository.choose(stale, forecast, history, now + 10 * 86_400_000L, now)
        assertEquals(SpaceWeatherRepository.SOURCE_RECURRENCE, r.source)
        assertEquals(99.0, r.sfi, 1e-9)
        assertEquals(stale, SpaceWeatherRepository.choose(stale, emptyList(), emptyMap(), now, now))
    }

    @Test
    fun solarReportRoundTripsOverTheAir() {
        val now = 1_763_460_000_000L
        val line = Reports.encode(Reports.SWX, mapOf("F" to "121", "K" to "1", "T" to Reports.dtg(now - 2 * 3_600_000L)))
        assertTrue(line.startsWith("#SWX "))
        val r = Reports.parse("N0DEF: @TEAM $line")!!
        assertEquals(Reports.SWX, r.type)
        assertEquals("121", r.values["F"])
        val at = Reports.dtgToMs(r.values["T"]!!, now)!!
        assertEquals((now - 2 * 3_600_000L).toDouble(), at.toDouble(), 60_000.0)
        assertNull(Reports.dtgToMs("garbage", now))
    }
}
