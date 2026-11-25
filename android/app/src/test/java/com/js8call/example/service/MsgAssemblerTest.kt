package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MsgAssemblerTest {

    private val period = 15_000L
    private val first = 0x0
    private val data = 0x4
    private val dataLast = 0x6
    private val last = 0x2
    private val toMe: (String, String) -> Boolean = { _, to -> to == "KN4CRD" || to == "@RACES" }

    private fun signed(body: String) = "$body ${RelayProtocol.checksum16(body)}"

    /** Splits [text] into 12-character data frames, as JS8 does at arbitrary character boundaries. */
    private fun MsgAssembler.feed(header: String, text: String, freq: Float = 1500f, start: Long = 0L, step: Long = period): ReceivedMsg? {
        assertNull(onFrame(header, -10, freq, first, start, 0, period, toMe))
        val chunks = text.chunked(12)
        var t = start
        chunks.forEachIndexed { i, c ->
            t += step
            val r = onFrame(c, -10, freq, if (i == chunks.lastIndex) dataLast else data, t, 0, period, toMe)
            if (i == chunks.lastIndex) return r
            assertNull(r)
        }
        return null
    }

    @Test
    fun aMessageSplitMidWordComesBackWhole() {
        val msg = MsgAssembler().feed("W1ABC: KN4CRD MSG", signed("MEET AT THE RIVER BRIDGE 1800"))
        assertNotNull(msg)
        assertEquals("MSG", msg!!.command)
        assertEquals("W1ABC", msg.from)
        assertEquals("KN4CRD", msg.to)
        assertEquals("MEET AT THE RIVER BRIDGE 1800", msg.text)
        assertTrue(msg.intact)
        assertEquals(0, msg.gaps)
    }

    @Test
    fun aMessageToAGroupWeFollowIsTaken() {
        val msg = MsgAssembler().feed("W1ABC: @RACES MSG", signed("ROADS CLOSED"))
        assertEquals("@RACES", msg!!.to)
        assertTrue(msg.intact)
    }

    @Test
    fun othersMessagesAreNotCollected() {
        val a = MsgAssembler()
        assertNull(a.onFrame("W1ABC: K2XYZ MSG", -10, 1500f, first, 0L, 0, period, toMe))
        assertEquals(0, a.pendingCount())
        assertNull(a.onFrame("HELLO", -10, 1500f, dataLast, 1L, 0, period, toMe))
    }

    @Test
    fun aGarbledMessageIsMarkedNotIntact() {
        val body = "MEET AT THE RIVER BRIDGE 1800"
        val wrong = "MEET AT THE RIVER BRIDGE 1900 ${RelayProtocol.checksum16(body)}"
        val msg = MsgAssembler().feed("W1ABC: KN4CRD MSG", wrong)
        assertEquals("MEET AT THE RIVER BRIDGE 1900", msg!!.text)
        assertFalse(msg.intact)
    }

    @Test
    fun aChecksumWithPunctuationIsStillRemoved() {
        // Base-41 checksums can hold + - . / ?: find a text whose checksum does.
        val body = (0..2000).map { "TEST $it" }.first { RelayProtocol.checksum16(it).any { c -> c in "+-./?" } }
        val msg = MsgAssembler().feed("W1ABC: KN4CRD MSG", signed(body))
        assertEquals(body, msg!!.text)
        assertTrue(msg.intact)
    }

    @Test
    fun aMissedFrameIsMarkedAndTheMessageIsNotIntact() {
        val a = MsgAssembler()
        a.onFrame("W1ABC: KN4CRD MSG", -10, 1500f, first, 0L, 0, period, toMe)
        a.onFrame("MEET AT THE ", -10, 1500f, data, period, 0, period, toMe)
        // One slot goes by unheard (our own transmission over it).
        val text = "BRIDGE ${RelayProtocol.checksum16("MEET AT THE RIVER BRIDGE")}"
        val msg = a.onFrame(text, -10, 1500f, dataLast, 3 * period, 0, period, toMe)!!
        assertEquals(1, msg.gaps)
        assertTrue(msg.text.contains("[… 1 frame missing …]"))
        assertFalse(msg.intact)
    }

    @Test
    fun aMessageInTheHeaderFrameItselfIsDelivered() {
        val msg = MsgAssembler().onFrame("W1ABC: KN4CRD QUERY ${signed("12")}", -10, 1500f, last, 0L, 0, period, toMe)
        assertEquals("QUERY", msg!!.command)
        assertEquals("12", msg.text)
        assertTrue(msg.intact)
    }

    @Test
    fun aHeaderAloneIsNotAMessage() {
        val a = MsgAssembler()
        assertNull(a.onFrame("W1ABC: KN4CRD MSG", -10, 1500f, last, 0L, 0, period, toMe))
        assertEquals(0, a.pendingCount())
    }

    @Test
    fun theNearestHeaderWithinTheToleranceGetsTheFrame() {
        val a = MsgAssembler()
        a.onFrame("W1ABC: KN4CRD MSG", -10, 1484f, first, 0L, 0, period, toMe)
        a.onFrame("K2XYZ: KN4CRD MSG", -10, 1500f, first, 0L, 0, period, toMe)
        // 1493 is within 10 Hz of both: the nearer header wins.
        assertEquals("K2XYZ", a.onFrame(signed("HI"), -10, 1493f, dataLast, period, 0, period, toMe)!!.from)
        assertEquals("W1ABC", a.onFrame(signed("YO"), -10, 1490f, dataLast, period, 0, period, toMe)!!.from)
        // Outside the tolerance of any header: not part of a message.
        a.onFrame("W1ABC: KN4CRD MSG", -10, 1484f, first, 0L, 0, period, toMe)
        assertNull(a.onFrame(signed("HI"), -10, 1495f, dataLast, period, 0, period, toMe))
        assertEquals(1, a.pendingCount())
    }

    @Test
    fun aQuietMessageIsDroppedAfterFourFramePeriods() {
        val a = MsgAssembler()
        a.onFrame("W1ABC: KN4CRD MSG", -10, 1500f, first, 0L, 0, period, toMe)
        assertNull(a.onFrame(signed("HI"), -10, 1500f, dataLast, 4 * period + 1, 0, period, toMe))
        assertEquals(0, a.pendingCount())
    }

    @Test
    fun aMessageThatNeverEndsCannotGrowForever() {
        val a = MsgAssembler(maxChars = 30)
        a.onFrame("W1ABC: KN4CRD MSG", -10, 1500f, first, 0L, 0, period, toMe)
        var t = 0L
        repeat(5) { t += period; a.onFrame("ABCDEFGHIJKL", -10, 1500f, data, t, 0, period, toMe) }
        assertEquals(0, a.pendingCount())
    }

    @Test
    fun aprsMessagesCarryNoChecksum() {
        val (text, intact) = MsgAssembler.clean("KN4CRD HELLO BAR", checksummed = false)
        assertEquals("KN4CRD HELLO BAR", text)
        assertTrue(intact)
        // The end-of-message marker never reaches the Inbox.
        assertEquals("HELLO" to true, MsgAssembler.clean("${signed("HELLO")} ♢"))
    }

    @Test
    fun impossibleInputIsIgnored() {
        val a = MsgAssembler()
        assertNull(a.onFrame("W1ABC: KN4CRD MSG HI", -10, Float.NaN, last, 0L, 0, period, toMe))
        assertNull(a.onFrame("W1ABC: KN4CRD MSG HI", -10, 1500f, last, 0L, 0, 0L, toMe))
    }

    @Test
    fun aSenderThatIsNotACallsignIsIgnored() {
        val a = MsgAssembler()
        assertNull(a.onFrame("EVIL TEXT: KN4CRD MSG ${signed("HI")}", -10, 1500f, last, 0L, 0, period, toMe))
        assertNull(a.onFrame("A>B: KN4CRD MSG ${signed("HI")}", -10, 1500f, last, 0L, 0, period, toMe))
        assertEquals(0, a.pendingCount())
    }
}
