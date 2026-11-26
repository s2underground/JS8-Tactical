package com.js8call.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsTimeTest {
    private val ms = 1_000_000L

    @Test
    fun offsetCorrectsForFixAge() {
        // Fix taken at GPS 12:00:00.000 (elapsed 1000 s); read 800 ms later, device clock says 12:00:02.300.
        val fixUtc = 1_700_000_000_000L
        val off = GpsTime.offsetMs(fixUtc, 1_000_000 * ms, 1_000_800 * ms, fixUtc + 2_300)
        assertEquals(-1_500L, off) // device clock 1.5 s fast: add -1500 ms
    }

    @Test
    fun slowClockGetsAPositiveOffset() {
        val fixUtc = 1_700_000_000_000L
        assertEquals(1_200L, GpsTime.offsetMs(fixUtc, 0, 200 * ms, fixUtc - 1_000))
    }

    @Test
    fun combineTakesTheMedianAndSpread() {
        val r = GpsTime.combine(listOf(s(1_210, acc = 8f), s(1_250, acc = 5f), s(1_190, acc = 12f)))!!
        assertEquals(1_210L, r.offsetMs)
        assertEquals(60L, r.spreadMs)
        assertEquals(5f, r.bestAccuracyM!!, 0f)
        assertEquals(3, r.samples)
        assertNull(GpsTime.combine(emptyList()))
    }

    @Test
    fun goodFixHasNoConcerns() {
        val r = GpsTime.combine(listOf(s(1_210), s(1_250), s(1_190)))!!
        assertTrue(GpsTime.concerns(r, currentDriftMs = 1_000).isEmpty())
    }

    @Test
    fun flagsMockWeakDisagreeingAndStaleFixes() {
        val r = GpsTime.combine(listOf(s(0, acc = 300f, mock = true, age = 20_000), s(900)))!!
        val c = GpsTime.concerns(r, 0)
        assertTrue(GpsTime.Concern.MOCK in c)
        assertTrue(GpsTime.Concern.DISAGREE in c)
        assertTrue(GpsTime.Concern.STALE in c)
        assertFalse(GpsTime.Concern.POOR_ACCURACY in c) // the better fix was 10 m
        assertTrue(GpsTime.needsCare(c))
        val noAcc = GpsTime.combine(listOf(GpsTime.Sample(0, 100, null, false)))!!
        assertTrue(GpsTime.Concern.POOR_ACCURACY in GpsTime.concerns(noAcc, 0))
    }

    @Test
    fun largeChangeIsANoteNotAWarning() {
        val r = GpsTime.combine(listOf(s(8_000), s(8_020), s(8_010)))!!
        val c = GpsTime.concerns(r, 0)
        assertEquals(listOf(GpsTime.Concern.LARGE_CHANGE), c)
        assertFalse(GpsTime.needsCare(c))
    }

    private fun s(offset: Long, acc: Float? = 10f, mock: Boolean = false, age: Long = 300) =
        GpsTime.Sample(offset, age, acc, mock)
}
