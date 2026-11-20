package com.js8call.example.net

import com.js8call.example.net.NetRoster.Msg
import com.js8call.example.net.NetRoster.Seen
import com.js8call.example.net.NetRoster.Sent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetRosterTest {
    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    private fun input(
        messages: List<Msg>,
        sent: List<Sent> = emptyList(),
        stations: Map<String, Seen> = emptyMap(),
        expected: List<String> = emptyList(),
    ) = NetRoster.Input("@GHOSTNET", t0, t0 + 60 * min, "N0ME", messages, sent, stations, expected)

    @Test
    fun checkInsComeFromTrafficToTheGroupOnly() {
        val r = NetRoster.build(input(listOf(
            Msg("K1AAA", "@GHOSTNET", "#CHECKIN S:GOOD L:EM73", t0 + 5 * min, true),
            Msg("K1AAA", "@GHOSTNET", "QRV ALL DAY", t0 + 20 * min, true),
            Msg("W2BBB", "@OTHER", "HELLO", t0 + 6 * min, true),            // another group
            Msg("W3CCC", "@GHOSTNET", "EARLY", t0 - 10 * min, true),         // before the net
            Msg("N0ME", "@GHOSTNET", "#CHECKIN S:GOOD", t0 + 7 * min, true), // our own echo
        ), stations = mapOf("K1AAA" to Seen("EM73", -12, t0 + 20 * min))))
        assertEquals(listOf("K1AAA"), r.checkedIn.map { it.call })
        val k = r.checkedIn.single()
        assertEquals(2, k.messages)
        assertEquals(NetRoster.Status.GOOD, k.status)
        assertEquals("QRV ALL DAY", k.lastText)
        assertEquals("EM73", k.grid)
        assertEquals(-12, k.snr)
        assertEquals(t0 + 5 * min, k.firstMs)
    }

    @Test
    fun precedenceAndEmergenciesSortFirst() {
        val r = NetRoster.build(input(listOf(
            Msg("A1A", "@GHOSTNET", "ROUTINE CHECKING IN", t0 + 1 * min, true),
            Msg("B2B", "@GHOSTNET", "#CHECKIN S:EMERG L:FN20 N:INJURED", t0 + 3 * min, true),
            Msg("C3C", "@GHOSTNET", "IMMEDIATE ROAD CLOSED AT BRIDGE", t0 + 4 * min, true),
            Msg("D4D", "@GHOSTNET", "#CHECKIN S:NEED N:WATER", t0 + 2 * min, true),
        )))
        assertEquals(listOf("C3C", "B2B", "D4D", "A1A"), r.checkedIn.map { it.call })
        assertEquals(NetRoster.Precedence.IMMEDIATE, r.checkedIn[0].precedence)
        assertEquals(NetRoster.Status.EMERGENCY, r.checkedIn[1].status)
        assertEquals("FN20", r.checkedIn[1].grid) // from the report when the station table has no grid
    }

    @Test
    fun missingSplitsOnTheAirFromNotHeardAndMatchesPortableCalls() {
        val r = NetRoster.build(input(
            listOf(Msg("K1AAA/P", "@GHOSTNET", "IN", t0 + 1 * min, true)),
            stations = mapOf(
                "W2BBB" to Seen(null, -18, t0 + 30 * min),
                "W3CCC" to Seen(null, -5, t0 - 120 * min), // heard, but before the net
            ),
            expected = listOf("k1aaa", "W2BBB", "W3CCC", "W4DDD"),
        ))
        assertEquals(listOf("W2BBB", "W3CCC", "W4DDD"), r.missing.map { it.call })
        assertEquals(t0 + 30 * min, r.missing[0].lastHeardMs)
        assertNull(r.missing[1].lastHeardMs)
        assertNull(r.missing[2].lastHeardMs)
        assertTrue(r.checkedIn.single().expected)
        assertEquals("W2BBB W3CCC W4DDD PSE CHECK IN", NetRoster.rollCallText(r.missing))
    }

    @Test
    fun answeredOnlyCountsSendsAfterTheCheckIn() {
        val r = NetRoster.build(input(
            listOf(
                Msg("K1AAA", "@GHOSTNET", "IN", t0 + 10 * min, true),
                Msg("W2BBB", "@GHOSTNET", "IN", t0 + 10 * min, true),
            ),
            sent = listOf(Sent("K1AAA", "ACK", t0 + 11 * min), Sent("W2BBB", "SNR?", t0 + 2 * min)),
        ))
        val byCall = r.checkedIn.associateBy { it.call }
        assertTrue(byCall.getValue("K1AAA").answered)
        assertFalse(byCall.getValue("W2BBB").answered)
        assertEquals(1, r.answeredCount)
        val log = r.toText()
        assertTrue(log.startsWith("NET LOG @GHOSTNET"))
        assertTrue(log.contains("2 checked in"))
    }

    @Test
    fun noExpectedListMeansNoMissing() {
        val r = NetRoster.build(input(emptyList(), stations = mapOf("W2BBB" to Seen(null, 0, t0 + min))))
        assertTrue(r.checkedIn.isEmpty())
        assertTrue(r.missing.isEmpty())
    }
}
