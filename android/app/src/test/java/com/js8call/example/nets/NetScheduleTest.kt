package com.js8call.example.nets

import com.js8call.example.nets.NetSchedule.Home
import com.js8call.example.nets.NetSchedule.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class NetScheduleTest {
    /** 2026-11-23 is a Monday. */
    private fun utc(day: Int, h: Int, m: Int = 0): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, h, m)
    }.timeInMillis

    private val ghost40 = Net("g40", "GhostNet 40m", 7_107_000, NetSchedule.EVERY_DAY, 0 * 60, 2 * 60, speed = 4, heartbeatMin = 30)
    private val wedNet = Net("w", "Wednesday 80m", 3_575_000, NetSchedule.WED, 23 * 60, 1 * 60) // runs past midnight
    private val home = Home(14_078_000, heartbeatMin = 0)

    @Test fun mondayIsDayZero() = assertEquals(0, NetSchedule.dayIndex(utc(23, 12)))

    @Test fun activeAndHome() {
        assertEquals("g40", NetSchedule.target(listOf(ghost40), home, utc(23, 1)).net?.id)
        val t = NetSchedule.target(listOf(ghost40), home, utc(23, 3))
        assertNull(t.net)
        assertEquals(14_078_000L, t.dialHz)
        assertEquals(utc(23, 2), t.sinceMs)
    }

    @Test fun pastMidnightOnTheStartDayOnly() {
        // Wednesday 25th 23:00 to Thursday 01:00.
        assertEquals("w", NetSchedule.activeAt(listOf(wedNet), utc(26, 0, 30))?.net?.id)
        assertNull(NetSchedule.activeAt(listOf(wedNet), utc(27, 0, 30)))
        assertNull(NetSchedule.activeAt(listOf(wedNet), utc(24, 23, 30)))
    }

    @Test fun laterStartWinsAnOverlap() {
        val inner = Net("i", "Inner", 3_578_000, NetSchedule.EVERY_DAY, 60, 90)
        val nets = listOf(ghost40, inner)
        assertEquals("i", NetSchedule.activeAt(nets, utc(23, 1, 15))?.net?.id)
        assertEquals("g40", NetSchedule.activeAt(nets, utc(23, 1, 45))?.net?.id)
        assertEquals(listOf("g40" to "i"), NetSchedule.overlaps(nets, utc(23, 0)).map { it.first.id to it.second.id })
    }

    @Test fun nextChangeAndUpcoming() {
        val (at, next) = NetSchedule.nextChange(listOf(ghost40), home, utc(23, 1))!!
        assertEquals(utc(23, 2), at)
        assertNull(next.net)
        val up = NetSchedule.upcoming(listOf(ghost40, wedNet), home, utc(25, 12), max = 4)
        // Wednesday's net runs into Thursday; GhostNet starts at 0000 and, starting later, takes over.
        assertEquals(listOf(utc(25, 23), utc(26, 0), utc(26, 2), utc(27, 0)), up.map { it.first })
        assertEquals(listOf("w", "g40", null, "g40"), up.map { it.second.net?.id })
    }

    @Test fun backToBackNetsAreOneChange() {
        val a = Net("a", "A", 7_107_000, NetSchedule.EVERY_DAY, 60, 120)
        val b = Net("b", "B", 3_575_000, NetSchedule.EVERY_DAY, 120, 180)
        val (at, next) = NetSchedule.nextChange(listOf(a, b), home, utc(23, 1, 30))!!
        assertEquals(utc(23, 2), at)
        assertEquals("b", next.net?.id)
    }

    @Test fun disabledOrNoDaysNeverRuns() {
        assertNull(NetSchedule.activeAt(listOf(ghost40.copy(enabled = false)), utc(23, 1)))
        assertNull(NetSchedule.activeAt(listOf(ghost40.copy(days = 0)), utc(23, 1)))
    }

    @Test fun storageRoundTrip() {
        val nets = listOf(ghost40, wedNet.copy(name = "Tab\there", notes = "line1\nline2", enabled = false))
        val (back, h) = NetSchedule.decode(NetSchedule.encode(nets, home))
        assertEquals(nets, back)
        assertEquals(home, h)
        assertEquals(Home(), NetSchedule.decode(null).second)
    }

    @Test fun text() {
        assertEquals("2300", NetSchedule.hhmm(23 * 60))
        assertEquals("Weekdays", NetSchedule.daysText(31))
        assertEquals("Wed", NetSchedule.daysText(NetSchedule.WED))
        assertEquals("7.107", NetSchedule.mhz(7_107_000))
        assertEquals("7.1075", NetSchedule.mhz(7_107_500))
    }

    @Test fun switcherDecisions() {
        val nets = listOf(ghost40)
        val now = utc(23, 0, 0) + 10_000
        assertTrue(NetSwitcher.decide(nets, home, false, null, 0, now, false, false) is NetSwitcher.Decision.Idle)
        val sw = NetSwitcher.decide(nets, home, true, null, 0, now, false, false)
        assertTrue(sw is NetSwitcher.Decision.Switch)
        val key = (sw as NetSwitcher.Decision.Switch).target.key
        assertTrue(NetSwitcher.decide(nets, home, true, key, 0, now, false, false) is NetSwitcher.Decision.Idle)
        // Never cuts a transmission; waits for an incoming message only for a while.
        assertTrue(NetSwitcher.decide(nets, home, true, "home:0", 0, now, true, false) is NetSwitcher.Decision.Wait)
        assertTrue(NetSwitcher.decide(nets, home, true, "home:0", 0, now, false, true) is NetSwitcher.Decision.Wait)
        assertTrue(NetSwitcher.decide(nets, home, true, "home:0", 0, now + 3 * 60_000, false, true) is NetSwitcher.Decision.Switch)
        // A hand-made change pauses it until the next scheduled change.
        val until = NetSwitcher.overrideUntil(nets, home, now)
        assertEquals(utc(23, 2), until)
        assertTrue(NetSwitcher.decide(nets, home, true, "home:0", until, now, false, false) is NetSwitcher.Decision.Idle)
        assertTrue(NetSwitcher.sameDial(7_107_000, 7_107_400))
    }
}
