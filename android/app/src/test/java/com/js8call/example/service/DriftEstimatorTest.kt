package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriftEstimatorTest {

    @Test
    fun oneStationGivesItsOwnDrift() {
        val e = DriftEstimator()
        e.record(1500f, 300, 0)
        assertEquals(300L, e.estimate(0))
    }

    @Test
    fun aTalkativeStationCountsOnce() {
        val e = DriftEstimator()
        // Three stations agree on about +200 ms; a fourth sends 50 frames claiming +2000 ms.
        e.record(800f, 190, 0); e.record(1200f, 210, 0); e.record(1900f, 200, 0)
        repeat(50) { e.record(2400f, 2000, it * 1000L) }
        assertEquals(205L, e.estimate(50_000))
        assertEquals(4, e.lanes(50_000))
    }

    @Test
    fun onlyTheLatestFromEachLaneCounts() {
        val e = DriftEstimator()
        e.record(1500f, 100, 0)
        e.record(1505f, 400, 1_000) // same lane, newer
        e.record(1000f, 200, 1_000)
        assertEquals(300L, e.estimate(1_000))
    }

    @Test
    fun oldSamplesAgeOut() {
        val e = DriftEstimator(windowMs = 60_000)
        e.record(1500f, 900, 0)
        e.record(1000f, 100, 50_000)
        assertEquals(100L, e.estimate(70_000))
        assertNull(e.estimate(200_000))
    }

    @Test
    fun impossibleInputIsIgnored() {
        val e = DriftEstimator()
        e.record(Float.NaN, 5000, 0)
        e.record(Float.POSITIVE_INFINITY, 5000, 0)
        assertNull(e.estimate(0))
    }

    @Test
    fun automaticSyncNeedsSeveralLanesAndMovesInSteps() {
        val e = DriftEstimator()
        e.record(1500f, 4000, 0); e.record(1525f, 4000, 0)
        // Two lanes (perhaps one station on two offsets): not enough to move the clock.
        assertNull(e.estimate(0, DriftEstimator.MIN_LANES))
        e.record(1550f, 4000, 0)
        assertEquals(4000L, e.estimate(0, DriftEstimator.MIN_LANES))
        // A 4 s correction is reached a second at a time.
        assertEquals(1000L, DriftEstimator.step(0, 4000))
        assertEquals(-1000L, DriftEstimator.step(0, -4000))
        assertEquals(350L, DriftEstimator.step(300, 350))
    }
}
