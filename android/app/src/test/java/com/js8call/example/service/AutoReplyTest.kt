package com.js8call.example.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AutoReplyTest {
    private val me = AutoReply.Station(
        callsign = "W2XYZ",
        info = "FT-891 20W EFHW",
        status = "QRV",
        grid = "fn42",
        lastTx = "W2XYZ: K1ABC MSG HELLO  ",
    )
    private val heard = AutoReply.Heard { exclude -> listOf("K1ABC", "N3QRS", "W2XYZ", "KD9KUA").filterNot { it in exclude } }
    private val nobody = AutoReply.Heard { emptyList() }

    private fun q(text: String) = RelayProtocol.parseDirectedCommand(text)!!

    private lateinit var saved: Locale
    @Before fun save() { saved = Locale.getDefault() }
    @After fun restore() { Locale.setDefault(saved) }

    // --- SNR text

    @Test
    fun snrLooksLikeJs8Call() {
        assertEquals("+05", AutoReply.formatSnr(5))
        assertEquals("+00", AutoReply.formatSnr(0))
        assertEquals("-05", AutoReply.formatSnr(-5))
        assertEquals("-24", AutoReply.formatSnr(-24))
        assertEquals("+60", AutoReply.formatSnr(60))
        assertEquals("", AutoReply.formatSnr(61))
        assertEquals("", AutoReply.formatSnr(-61))
    }

    @Test
    fun snrUsesPlainDigitsInEveryLanguage() {
        listOf("ar", "fa-IR", "bn-BD", "mr-IN", "ne-NP", "my-MM").forEach { tag ->
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals(tag, "-12", AutoReply.formatSnr(-12))
            assertEquals(tag, "+07", AutoReply.formatSnr(7))
        }
    }

    // --- Heartbeats

    @Test
    fun acksSomeoneElsesHeartbeat() {
        assertEquals("W2XYZ: K1ABC HEARTBEAT SNR -12", AutoReply.heartbeatAck("k1abc: @HB HEARTBEAT FN31", -12, me))
        assertEquals(
            "W2XYZ: K1ABC HEARTBEAT SNR -12 MSG ID 3",
            AutoReply.heartbeatAck("K1ABC: @HB HEARTBEAT FN31", -12, me) { target -> if (target == "K1ABC") " MSG ID 3" else "" }
        )
    }

    @Test
    fun neverAcksItsOwnHeartbeatOrABadReading() {
        assertNull(AutoReply.heartbeatAck("W2XYZ: @HB HEARTBEAT FN42", -10, me))
        assertNull(AutoReply.heartbeatAck("w2xyz: @HB HEARTBEAT FN42", -10, me))
        assertNull(AutoReply.heartbeatAck("K1ABC: @HB HEARTBEAT FN31", -99, me))
        assertNull(AutoReply.heartbeatAck("K1ABC: W2XYZ SNR?", -10, me))
    }

    @Test
    fun aSenderThatIsNotACallsignGetsNothing() {
        // Free text shaped like a heartbeat or a query would otherwise put its words on the air
        // under our callsign ("W2XYZ: ANY WORDS HERE HEARTBEAT SNR -10").
        assertNull(AutoReply.heartbeatAck("ANY WORDS HERE: @HB HEARTBEAT FN31", -10, me))
        assertNull(AutoReply.heartbeatAck("K2X>N3QRS: @HB HEARTBEAT FN31", -10, me))
        assertNull(AutoReply.directedReply(DirectedCommand("ANY WORDS", "W2XYZ", "SNR?", ""), -8, me, heard))
        assertNull(AutoReply.directedReply(q("K2X>N3QRS: W2XYZ SNR?"), -8, me, heard))
        assertNull(AutoReply.directedReply(q("<....>: W2XYZ SNR?"), -8, me, heard))
    }

    // --- Direct queries

    @Test
    fun answersEachQueryAddressedToMe() {
        assertEquals("W2XYZ: K1ABC SNR -08", AutoReply.directedReply(q("K1ABC: W2XYZ SNR?"), -8, me, heard))
        assertEquals("W2XYZ: K1ABC SNR -08", AutoReply.directedReply(q("K1ABC: W2XYZ ?"), -8, me, heard))
        assertEquals("W2XYZ: K1ABC INFO FT-891 20W EFHW", AutoReply.directedReply(q("K1ABC: W2XYZ INFO?"), -8, me, heard))
        assertEquals("W2XYZ: K1ABC STATUS QRV", AutoReply.directedReply(q("K1ABC: W2XYZ STATUS?"), -8, me, heard))
        assertEquals("W2XYZ: K1ABC GRID FN42", AutoReply.directedReply(q("K1ABC: W2XYZ GRID?"), -8, me, heard))
        // HEARING leaves out the asker and ourselves.
        assertEquals("W2XYZ: K1ABC HEARING N3QRS KD9KUA", AutoReply.directedReply(q("K1ABC: W2XYZ HEARING?"), -8, me, heard))
        // AGN? repeats the last transmission as sent.
        assertEquals("W2XYZ: K1ABC MSG HELLO", AutoReply.directedReply(q("K1ABC: W2XYZ AGN?"), -8, me, heard))
    }

    @Test
    fun gridRepliesNeverGiveMoreThanTheSquare() {
        val gps = me.copy(grid = "fn42ab12")
        assertEquals("W2XYZ: K1ABC GRID FN42", AutoReply.directedReply(q("K1ABC: W2XYZ GRID?"), -8, gps, heard))
        assertEquals("N3QRS>K1ABC GRID FN42", AutoReply.relayedReply("GRID?", "N3QRS>K1ABC", -15, gps, heard))
    }

    @Test
    fun sendsNothingItHasNoAnswerFor() {
        val bare = AutoReply.Station("W2XYZ")
        listOf("INFO?", "STATUS?", "GRID?", "AGN?").forEach { cmd ->
            assertNull(cmd, AutoReply.directedReply(q("K1ABC: W2XYZ $cmd"), -8, bare, heard))
        }
        assertNull(AutoReply.directedReply(q("K1ABC: W2XYZ HEARING?"), -8, me, nobody))
        assertNull(AutoReply.directedReply(q("K1ABC: W2XYZ SNR?"), 99, me, heard))
        // Not a query.
        assertNull(AutoReply.directedReply(q("K1ABC: W2XYZ SNR -10"), -8, me, heard))
        assertNull(AutoReply.directedReply(q("K1ABC: W2XYZ GOOD MORNING"), -8, me, heard))
    }

    @Test
    fun onlyAnswersFramesAddressedToMeFromSomeoneElse() {
        assertNull(AutoReply.directedReply(q("K1ABC: N3QRS SNR?"), -8, me, heard))
        assertNull(AutoReply.directedReply(q("K1ABC: @ALLCALL SNR?"), -8, me, heard))
        assertNull(AutoReply.directedReply(q("W2XYZ: W2XYZ SNR?"), -8, me, heard))
        // Case doesn't hide our own callsign.
        assertEquals("W2XYZ: K1ABC SNR -08", AutoReply.directedReply(q("K1ABC: w2xyz SNR?"), -8, me, heard))
    }

    // --- Queries that came through relays

    @Test
    fun answersBackAlongTheRelayPath() {
        assertEquals("N3QRS>K1ABC SNR -15", AutoReply.relayedReply("SNR? *DE* K1ABC", "N3QRS>K1ABC", -15, me, heard))
        assertEquals("N3QRS>K1ABC GRID FN42", AutoReply.relayedReply("  grid? *DE* K1ABC", "N3QRS>K1ABC", -15, me, heard))
        // HEARING leaves out everyone on the path, and ourselves.
        assertEquals("N3QRS>K1ABC HEARING KD9KUA", AutoReply.relayedReply("HEARING?", "N3QRS>K1ABC", -15, me, heard))
        assertEquals("W2XYZ: K1ABC MSG HELLO", AutoReply.relayedReply("AGN?", "N3QRS>K1ABC", -15, me, heard))
    }

    @Test
    fun relayedTextThatIsntAQueryGetsNoAnswer() {
        assertNull(AutoReply.relayedReply("HELLO THERE", "N3QRS>K1ABC", -15, me, heard))
        assertNull(AutoReply.relayedReply("", "N3QRS>K1ABC", -15, me, heard))
        assertNull(AutoReply.relayedReply("SNR?", "", -15, me, heard))
    }

    // --- AGN? replays (audit M3)

    @Test
    fun agnIsReplayedOncePerStationPerWindow() {
        val g = ReplayGuard(windowMs = 600_000)
        assertTrue(g.allow("K1ABC", 0))
        // Asking again, in any case or with a suffix, inside the window: left to the operator.
        assertFalse(g.allow("k1abc", 60_000))
        assertFalse(g.allow("K1ABC/P", 599_999))
        // Another station has its own allowance.
        assertTrue(g.allow("N3QRS", 60_000))
        // After the window, one more automatic replay.
        assertTrue(g.allow("K1ABC", 600_000))
        assertFalse(g.allow("", 0))
    }

    // --- group queries (desktop JS8Call answers these for groups in My Groups)

    private val groups = listOf("@GHOSTNET", "@ARES")

    @Test
    fun answersSnrQueryToJoinedGroup() {
        assertEquals("W2XYZ: K1ABC SNR -06", AutoReply.groupReply(q("K1ABC: @GHOSTNET SNR?"), -6, me, groups, heard))
    }

    @Test
    fun groupMatchIgnoresCase() {
        assertEquals("W2XYZ: K1ABC SNR +04", AutoReply.groupReply(q("K1ABC: @ghostnet SNR?"), 4, me, listOf(" @GhostNet "), heard))
    }

    @Test
    fun answersInfoStatusGridHearingToGroup() {
        assertEquals("W2XYZ: K1ABC INFO FT-891 20W EFHW", AutoReply.groupReply(q("K1ABC: @ARES INFO?"), 0, me, groups, heard))
        assertEquals("W2XYZ: K1ABC STATUS QRV", AutoReply.groupReply(q("K1ABC: @ARES STATUS?"), 0, me, groups, heard))
        assertEquals("W2XYZ: K1ABC GRID FN42", AutoReply.groupReply(q("K1ABC: @ARES GRID?"), 0, me, groups, heard))
        assertEquals("W2XYZ: K1ABC HEARING N3QRS KD9KUA", AutoReply.groupReply(q("K1ABC: @ARES HEARING?"), 0, me, groups, heard))
    }

    @Test
    fun ignoresGroupsNotJoined() {
        assertNull(AutoReply.groupReply(q("K1ABC: @OTHERNET SNR?"), -6, me, groups, heard))
        assertNull(AutoReply.groupReply(q("K1ABC: @GHOSTNET SNR?"), -6, me, emptyList(), heard))
    }

    @Test
    fun neverAnswersAllcallOrHbAsAGroup() {
        val all = listOf("@ALLCALL", "@HB")
        assertNull(AutoReply.groupReply(q("K1ABC: @ALLCALL SNR?"), -6, me, all, heard))
        assertNull(AutoReply.groupReply(q("K1ABC: @HB SNR?"), -6, me, all, heard))
    }

    @Test
    fun doesNotReplayAgnToAGroup() {
        assertNull(AutoReply.groupReply(q("K1ABC: @GHOSTNET AGN?"), -6, me, groups, heard))
    }

    @Test
    fun ignoresOwnAndInvalidSendersToGroup() {
        assertNull(AutoReply.groupReply(q("W2XYZ: @GHOSTNET SNR?"), -6, me, groups, heard))
        assertNull(AutoReply.groupReply(DirectedCommand("R\u0000", "@GHOSTNET", "SNR?", ""), -6, me, groups, heard))
        assertNull(AutoReply.groupReply(DirectedCommand("", "@GHOSTNET", "SNR?", ""), -6, me, groups, heard))
    }

    @Test
    fun groupQueryIsNotADirectedReply() {
        // The direct path still never answers a group: only groupReply does, for joined groups.
        assertNull(AutoReply.directedReply(q("K1ABC: @GHOSTNET SNR?"), -6, me, heard))
    }

    @Test
    fun groupReplyKeySeparatesAskerGroupAndQuery() {
        val guard = ReplayGuard(windowMs = 15 * 60_000L)
        val snr = AutoReply.groupReplyKey(q("K1ABC: @GHOSTNET SNR?"))
        assertTrue(guard.allow(snr, 0))
        assertFalse(guard.allow(snr, 60_000))
        assertFalse(guard.allow(AutoReply.groupReplyKey(q("K1ABC/P: @ghostnet SNR?")), 60_000))
        assertTrue(guard.allow(AutoReply.groupReplyKey(q("K1ABC: @GHOSTNET GRID?")), 60_000))
        assertTrue(guard.allow(AutoReply.groupReplyKey(q("K1ABC: @ARES SNR?")), 60_000))
        assertTrue(guard.allow(AutoReply.groupReplyKey(q("N3QRS: @GHOSTNET SNR?")), 60_000))
        assertTrue(guard.allow(snr, 15 * 60_000L))
    }
}
