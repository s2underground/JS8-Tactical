package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnAirTextTest {

    @Test fun grid4KeepsOnlyTheSquare() {
        assertEquals("EM73", OnAirText.grid4("em73tu45"))
        assertEquals("EM73", OnAirText.grid4(" EM73TU "))
        assertEquals("FN31", OnAirText.grid4("FN31"))
        assertEquals("", OnAirText.grid4("EM7"))
        assertEquals("", OnAirText.grid4("ZZ99"))   // not a locator
        assertEquals("", OnAirText.grid4(null))
    }

    // The station's own locator: GPS fills in 8 characters, but only the square is ever added.
    @Test fun heartbeatAndCqGetTheSquareOnly() {
        assertEquals("@HB HEARTBEAT EM73", OnAirText.withHeartbeatGrid("@HB HEARTBEAT", "EM73tu45"))
        assertEquals("CQ CQ CQ EM73", OnAirText.withHeartbeatGrid("CQ CQ CQ ", "em73tu"))
        assertEquals("HB EM73", OnAirText.withHeartbeatGrid("HB", "EM73"))
    }

    @Test fun noGridAddedToOtherTextOrWhenOneIsThere() {
        assertEquals("W1ABC SNR?", OnAirText.withHeartbeatGrid("W1ABC SNR?", "EM73"))
        assertEquals("CQ FN31", OnAirText.withHeartbeatGrid("CQ FN31", "EM73"))
        // Typed as a 6-character locator: left alone (it used to get " EM73" added as well).
        assertEquals("HB EM73TU", OnAirText.withHeartbeatGrid("HB EM73TU", "EM73"))
        assertEquals("CQ", OnAirText.withHeartbeatGrid("CQ", ""))
        assertEquals("CQ", OnAirText.withHeartbeatGrid("CQ", "not a grid"))
    }

    @Test fun directedCallGoesInFrontOnce() {
        assertEquals("W1ABC HELLO", OnAirText.withDirected("HELLO", "w1abc"))
        assertEquals("W1ABC HELLO", OnAirText.withDirected("W1ABC HELLO", "W1ABC"))
        assertEquals("HELLO", OnAirText.withDirected(" HELLO ", ""))
        assertEquals("CQ CQ EM73", OnAirText.withDirected("CQ CQ EM73", "W1ABC"))
        assertEquals("@HB HEARTBEAT EM73", OnAirText.withDirected("@HB HEARTBEAT EM73", "W1ABC"))
        assertEquals("`RAW", OnAirText.withDirected("`RAW", "W1ABC"))
        assertEquals("", OnAirText.withDirected("   ", "W1ABC"))
    }

    @Test fun heardCallsign() {
        assertEquals("W1ABC", OnAirText.heardCallsign("W1ABC: @HB HEARTBEAT FN31"))
        assertEquals("W1ABC", OnAirText.heardCallsign("w1abc>KN4CRD HELLO"))
        assertEquals("VE3/W1ABC", OnAirText.heardCallsign("VE3/W1ABC: HELLO"))
        assertNull(OnAirText.heardCallsign("@ALLCALL CQ"))
        assertNull(OnAirText.heardCallsign("CQ CQ CQ"))
        assertNull(OnAirText.heardCallsign("HEARTBEAT"))
        assertNull(OnAirText.heardCallsign("HELLO THERE"))
        assertNull(OnAirText.heardCallsign("   "))
    }

    @Test fun spotGrid() {
        assertEquals("FN31", OnAirText.spotGrid("W1ABC: @HB HEARTBEAT fn31"))
        assertEquals("FN31PR", OnAirText.spotGrid("W1ABC: CQ FN31PR"))
        assertNull(OnAirText.spotGrid("W1ABC: HELLO"))
        assertNull(OnAirText.spotGrid("W1ABC: FN31PR45X"))
    }

    private val noon = 1_764_331_200_000L // 2025-11-28 12:00:00Z

    @Test fun decodeTimeLandsOnTheNearestDay() {
        assertEquals(noon / 1000 + 30, OnAirText.decodeTimeToEpochSeconds(120030, noon))
        // Just after midnight, a decode stamped 23:59:45 belongs to the previous day.
        val justAfterMidnight = noon + 12 * 3_600_000L + 10_000L
        assertEquals((noon + 12 * 3_600_000L - 15_000L) / 1000, OnAirText.decodeTimeToEpochSeconds(235945, justAfterMidnight))
        // Just before midnight, 00:00:05 is the next day.
        val justBefore = noon + 12 * 3_600_000L - 10_000L
        assertEquals((noon + 12 * 3_600_000L + 5_000L) / 1000, OnAirText.decodeTimeToEpochSeconds(5, justBefore))
    }

    @Test fun invalidDecodeTimeReadsAsNow() {
        assertEquals(noon / 1000, OnAirText.decodeTimeToEpochSeconds(-1, noon))
        assertEquals(noon / 1000, OnAirText.decodeTimeToEpochSeconds(996000, noon))
        assertEquals(noon / 1000, OnAirText.decodeTimeToEpochSeconds(126100, noon))
        assertEquals(noon / 1000, OnAirText.decodeTimeToEpochSeconds(120099, noon))
    }

    @Test fun pskSpot() {
        val s = OnAirText.pskSpot("W1ABC: @HB HEARTBEAT FN31", "KN4CRD", 7_078_000L, 1500.4f, 120000, noon)
        assertEquals(OnAirText.Spot("W1ABC", "FN31", 7_079_500L, noon / 1000), s)
    }

    @Test fun noSpotForOurselvesOrWithoutGridOrDial() {
        assertNull(OnAirText.pskSpot("KN4CRD: @HB HEARTBEAT EM73", "KN4CRD", 7_078_000L, 1500f, 120000, noon))
        assertNull(OnAirText.pskSpot("KN4CRD/P: CQ EM73", "KN4CRD", 7_078_000L, 1500f, 120000, noon))
        assertNull(OnAirText.pskSpot("W1ABC: HELLO", "KN4CRD", 7_078_000L, 1500f, 120000, noon))
        assertNull(OnAirText.pskSpot("W1ABC: CQ FN31", "KN4CRD", 0L, 1500f, 120000, noon))
        assertNull(OnAirText.pskSpot("W1ABC: CQ FN31", "KN4CRD", 7_078_000L, Float.NaN, 120000, noon))
        assertNull(OnAirText.pskSpot("W1ABC: CQ FN31", "KN4CRD", 7_078_000L, -5f, 120000, noon))
    }
}
