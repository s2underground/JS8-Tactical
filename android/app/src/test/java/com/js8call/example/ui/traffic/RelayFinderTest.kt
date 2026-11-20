package com.js8call.example.ui.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayFinderTest {
    private val now = 10 * 3_600_000L
    private val min = 60_000L

    private fun st(call: String, weHear: Int? = null, weMin: Long? = null, heardUs: Int? = null, usMin: Long? = null) =
        call to RelayFinder.Station(call, weHear, weMin?.let { now - it * min }, heardUs, usMin?.let { now - it * min })

    private fun link(a: String, b: String, agoMin: Long, snr: Int? = null, reporter: String? = null, both: Boolean = false, hearing: Boolean = false) =
        RelayFinder.Link(minOf(a, b), maxOf(a, b), now - agoMin * min, snr, reporter, both, hearing)

    @Test
    fun ranksRelaysByTheirWeakerLeg() {
        val stations = mapOf(
            st("W4QRP", heardUs = -8, usMin = 5),
            st("KD8SKZ", weHear = -3, weMin = 2),
            st("N0DEF", weHear = -20, weMin = 200),
        )
        val links = listOf(
            link("W4QRP", "K9DGY", 10, snr = -11, reporter = "W4QRP"),
            link("KD8SKZ", "K9DGY", 3, both = true),
            link("N0DEF", "K9DGY", 30, snr = -2, reporter = "N0DEF"),
        )
        val plan = RelayFinder.plan("K9DGY", stations, links, now)
        assertEquals(listOf("W4QRP", "KD8SKZ", "N0DEF"), plan.options.map { it.relay })
        assertEquals(RelayFinder.Leg.Kind.HEARD_US, plan.options[0].toRelay.kind)
        assertEquals(RelayFinder.Leg.Kind.REPORTED, plan.options[0].relayToTarget.kind)
        assertNull(plan.targetHeardUs)
    }

    @Test
    fun skipsStationsWeHaveNoPathToAndOldEvidence() {
        val stations = mapOf(st("W4QRP", heardUs = -8, usMin = 5))
        val links = listOf(
            link("W4QRP", "K9DGY", 7 * 60, snr = -5), // older than 6 h
            link("AB1CD", "K9DGY", 5, snr = -5),       // AB1CD: no path from us
        )
        assertTrue(RelayFinder.plan("K9DGY", stations, links, now).options.isEmpty())
    }

    @Test
    fun reportsADirectPathWhenTheTargetHeardUs() {
        val plan = RelayFinder.plan("K9DGY", mapOf(st("K9DGY", heardUs = -12, usMin = 4)), emptyList(), now)
        assertNotNull(plan.targetHeardUs)
        assertEquals(-12, plan.targetHeardUs!!.snr)
    }

    @Test
    fun relayTextIsStandardJs8() {
        assertEquals("W4QRP>K9DGY>WATER AT 1800", RelayFinder.relayText("w4qrp", "k9dgy", " water at 1800 "))
    }
}
