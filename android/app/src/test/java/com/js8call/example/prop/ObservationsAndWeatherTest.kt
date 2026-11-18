package com.js8call.example.prop

import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ObservationsAndWeatherTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun observationRoundTripsThroughText() {
        val o = Observation(1_700_000_000_000L, "N0DEF", "EM74", Band.B40, -15, Observation.Kind.HEARD_ME)
        assertEquals(o, Observation.decode(o.encode()))
        assertEquals(Observation(1L, "K1ABC", null, Band.B20, 3, Observation.Kind.HEARD), Observation.decode("1,K1ABC,,B20,3,HEARD"))
        assertNull(Observation.decode("garbage"))
    }

    @Test
    fun storePersistsDedupsAndDropsOldEntries() {
        val dir = Files.createTempDirectory("obs").toFile()
        val f = File(dir, "o.csv")
        val now = System.currentTimeMillis()
        f.writeText(Observation(now - 40 * day, "OLD1", "EM70", Band.B40, -10, Observation.Kind.HEARD).encode() + "\n")
        val store = ObservationStore(f)
        store.add(Observation(now, "N0DEF", "EM74", Band.B40, -15, Observation.Kind.HEARD))
        store.add(Observation(now + 60_000, "N0DEF", "EM74", Band.B40, -12, Observation.Kind.HEARD)) // same window
        store.add(Observation(now, "N0DEF", "EM74", Band.B80, -12, Observation.Kind.HEARD))
        store.add(Observation(now, "FAKE", "EM74", Band.B80, -12, Observation.Kind.HEARD), persist = false)
        assertEquals(3, store.all().size)
        val reloaded = ObservationStore(f).all()
        assertEquals(listOf("N0DEF", "N0DEF"), reloaded.map { it.call })
    }

    @Test
    fun relevantUsesRecentTrafficNowAndSameHourOnEarlierDaysLater() {
        val now = 1_763_460_000_000L // fixed instant
        val all = listOf(
            Observation(now - hour, "NOW1", "EM74", Band.B40, -10, Observation.Kind.HEARD),
            Observation(now - day + 6 * hour, "LATER", "FN31", Band.B40, -10, Observation.Kind.HEARD),
            Observation(now - 2 * day + 6 * hour, "LATER2", null, Band.B40, -10, Observation.Kind.HEARD),
            Observation(now - hour, "OTHERBAND", "EM74", Band.B20, -10, Observation.Kind.HEARD),
        )
        val grids = mapOf("LATER2" to "DM79")
        val atNow = ObservationStore.relevant(all, Band.B40, now, now) { grids[it] }.map { it.obs.call }
        assertEquals(listOf("NOW1"), atNow)
        val in6h = ObservationStore.relevant(all, Band.B40, now + 6 * hour, now) { grids[it] }
        assertEquals(setOf("LATER", "LATER2"), in6h.map { it.obs.call }.toSet())
        // Yesterday counts more than the day before.
        assertTrue(in6h.first { it.obs.call == "LATER" }.weight > in6h.first { it.obs.call == "LATER2" }.weight)
    }

    @Test
    fun pathObservationsRoundTripAndStayOffTheHeardLayer() {
        val o = Observation(1_700_000_000_000L, "K1ABC", "FN42", Band.B40, -10, Observation.Kind.PATH, by = "N0DEF")
        assertEquals(o, Observation.decode(o.encode()))
        val now = o.timeMs + 600_000
        val grids = mapOf("N0DEF" to "EM74")
        assertTrue(ObservationStore.relevant(listOf(o), Band.B40, now, now) { grids[it] }.isEmpty())
        val paths = ObservationStore.relevantPaths(listOf(o), Band.B40, now, now) { grids[it] }
        assertEquals(1, paths.size)
        assertTrue(paths[0].from.lat < paths[0].to.lat) // EM74 is south of FN42
    }

    @Test
    fun noaaParsersAcceptBothKpLayoutsAndFlux() {
        val flux = """[{"time_tag":"2026-11-16T20:00:00","flux":151.2},{"time_tag":"2026-11-17T20:00:00","flux":148.0}]"""
        assertEquals(148.0, SpaceWeatherRepository.parseFlux(flux)!!, 0.01)
        val objects = """[{"time_tag":"2026-11-18T10:00:00","kp_index":2,"estimated_kp":2.33},{"time_tag":"2026-11-18T10:01:00","kp_index":3,"estimated_kp":3.0}]"""
        assertEquals(3.0, SpaceWeatherRepository.parseKp(objects)!!, 0.01)
        val rows = """[["time_tag","Kp","a_running","station_count"],["2026-11-18 06:00:00.000","1.67","6","8"],["2026-11-18 09:00:00.000","2.33","9","8"]]"""
        assertEquals(2.33, SpaceWeatherRepository.parseKp(rows)!!, 0.01)
    }

    @Test
    fun coverageGridIsFastAndShowsSkipZoneOnTwentyAtNight() {
        val me = LatLon(36.0, -86.0)
        val night = 1_763_438_400_000L // 04:00Z
        val wx = SpaceWeather(150.0, 2.0, 0, "test")
        val t0 = System.nanoTime()
        val grid = PropagationModel.coverageGrid(me, Band.B40, night, wx, 240, 180, 85.0)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(240 * 180, grid.size)
        assertTrue("took $ms ms", ms < 3000)
        assertTrue(grid.any { it > 0.5f })
        val line = PropagationModel.terminator(night)
        assertEquals(181, line.size)
        assertTrue(line.all { it.lat in -90.0..90.0 })
    }
}
