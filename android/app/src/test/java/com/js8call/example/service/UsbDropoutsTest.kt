package com.js8call.example.service

import com.js8call.example.service.UsbDropouts.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbDropoutsTest {
    private val now = 10_000_000L

    @Test
    fun dropoutsRightAfterSendingLookLikeRf() {
        val e = listOf(Event(now - 300_000, 2_000, false), Event(now - 200_000, -1, true), Event(now - 60_000, 4_000, false))
        assertEquals(3 to 3, UsbDropouts.rfPattern(e, now))
    }

    @Test
    fun dropoutsWithNoSendNearbyDoNot() {
        val e = listOf(Event(now - 300_000, 120_000, false), Event(now - 200_000, -1, false), Event(now - 60_000, 400_000, false))
        assertNull(UsbDropouts.rfPattern(e, now))
    }

    @Test
    fun twoDropoutsOrOldOnesAreNotAPattern() {
        assertNull(UsbDropouts.rfPattern(listOf(Event(now - 1_000, 1_000, false), Event(now - 2_000, 1_000, false)), now))
        val old = List(5) { Event(now - 40 * 60_000L, 1_000, true) }
        assertNull(UsbDropouts.rfPattern(old, now))
    }
}
