package com.js8call.example.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMemoryTest {

    @Test
    fun oneWayCallIsNotConfirmedUntilAnswered() {
        val m = MapMemory()
        m.noteLink("W4QRP", "KN4XYZ", 1_000)
        val l = m.links().single()
        assertEquals("KN4XYZ", l.a)
        assertFalse(l.confirmed)
        m.noteLink("KN4XYZ", "W4QRP", 2_000)
        assertTrue(m.links().single().confirmed)
        assertEquals(2_000, m.links().single().lastHeardMs)
    }

    @Test
    fun latestSignalReportWinsEvenWhenOlderOneArrivesLater() {
        val m = MapMemory()
        m.noteLink("AC4LT", "KN4XYZ", 5_000, snr = -9)
        m.noteLink("KN4XYZ", "AC4LT", 3_000, snr = -15) // older, e.g. history replayed out of order
        val l = m.links().single()
        assertEquals(-9, l.snr)
        assertEquals("AC4LT", l.reporter)
        assertTrue(l.confirmed)
    }

    @Test
    fun hearingListConfirmsLink() {
        val m = MapMemory()
        m.noteLink("W4QRP", "KF4MNO", 1_000, hearing = true)
        assertTrue(m.links().single().confirmed)
        assertNull(m.links().single().snr)
    }

    @Test
    fun keepsPreciseGridInSameSquareButFollowsAMove() {
        val m = MapMemory()
        m.noteStation("N0DEF", "EM74TU", 1_000, -10)
        m.noteStation("N0DEF", "EM74", 2_000, -12)
        assertEquals("EM74TU", m.grid("N0DEF"))
        m.noteStation("N0DEF", "EM75", 3_000, -12)
        assertEquals("EM75", m.grid("N0DEF"))
        m.noteStation("N0DEF", "EM75AB", 2_500, -12) // older but sharper: keep the square, add precision
        assertEquals("EM75AB", m.grid("N0DEF"))
    }

    @Test
    fun survivesSaveAndLoadAndLeavesDemoOut() {
        val m = MapMemory()
        m.noteStation("KN4XYZ", "EM73", 1_000, -9)
        m.noteStation("DEMO1", "EM72", 1_000, -9)
        m.noteLink("KN4XYZ", "W4QRP", 1_500, snr = -11)
        m.noteLink("DEMO1", "KN4XYZ", 1_500)
        assertTrue(m.dirty)
        val text = m.serialize(exclude = setOf("DEMO1"))
        assertFalse(m.dirty)
        val back = MapMemory.parse(text + "garbage\tline\nS\tBAD\tZZ99\t1\t1\n")
        assertEquals(listOf("KN4XYZ"), back.stations().map { it.call })
        val l = back.links().single()
        assertEquals(-11, l.snr)
        assertEquals("KN4XYZ", l.reporter)
        assertEquals(m.links().first { it.b == "W4QRP" }, l)
    }

    @Test
    fun pruneDropsOldEntries() {
        val m = MapMemory()
        m.noteStation("OLD", "EM70", 1_000, 0)
        m.noteStation("NEW", "EM71", 9_000, 0)
        m.noteLink("OLD", "NEW", 1_000)
        m.prune(5_000)
        assertEquals(listOf("NEW"), m.stations().map { it.call })
        assertTrue(m.links().isEmpty())
    }

    @Test
    fun mergeKeepsNewestOfBoth() {
        val disk = MapMemory().apply {
            noteStation("KN4XYZ", "EM73", 1_000, -9)
            noteLink("KN4XYZ", "W4QRP", 1_000, snr = -20)
        }
        val live = MapMemory().apply {
            noteStation("KN4XYZ", "EM83", 5_000, -3)
            noteLink("W4QRP", "KN4XYZ", 4_000, snr = -7)
        }
        live.merge(disk)
        assertEquals("EM83", live.grid("KN4XYZ"))
        val l = live.links().single()
        assertEquals(-7, l.snr)
        assertTrue(l.confirmed) // both directions seen
    }

    @Test
    fun bandsAreKeptAndOldFilesStillLoad() {
        val m = MapMemory()
        m.noteStation("KN4XYZ", "EM73", 1_000, -9, "40m")
        m.noteStation("KN4XYZ", "EM73", 2_000, -9, "20m")
        m.noteLink("KN4XYZ", "W4QRP", 2_000, snr = -11, band = "20m")
        val back = MapMemory.parse(m.serialize())
        assertEquals(setOf("40m", "20m"), back.stations().single().bands)
        assertEquals(setOf("20m"), back.links().single().bands)
        // A file written before bands were kept.
        val old = MapMemory.parse("S\tN0DEF\tEM74\t1000\t-5\nL\tA1AA\tB2BB\t1000\t-\t-\t0\t1\n")
        assertEquals(emptySet<String>(), old.stations().single().bands)
        assertEquals(emptySet<String>(), old.links().single().bands)
    }
}
