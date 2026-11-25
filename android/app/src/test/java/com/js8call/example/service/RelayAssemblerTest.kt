package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayAssemblerTest {

    private val me = "KN4CRD"
    private val first = 0x0
    private val data = 0x4
    private val dataLast = 0x6
    private val last = 0x2

    private fun signed(body: String) = "$body ${RelayProtocol.checksum16(body)}"

    @Test
    fun aRelayInOneFrameIsDeliveredBackToItsSender() {
        val a = RelayAssembler()
        val relay = a.onFrame("W1ABC: KN4CRD> ${signed("HELLO THERE")}", -12, 1500f, last, 0L, me, 0)
        assertNotNull(relay)
        assertEquals("W1ABC", relay!!.from)
        assertEquals(-12, relay.snr)
        assertEquals(RelayAction.Deliver("HELLO THERE", "W1ABC"), RelayAssembler.decide(relay, me))
        assertEquals(0, a.pendingCount())
    }

    @Test
    fun dataFramesOnTheSameOffsetFillTheRelayIn() {
        val a = RelayAssembler()
        assertNull(a.onFrame("W1ABC: KN4CRD>", -10, 1500f, first, 0L, me, 0))
        assertNull(a.onFrame("HELLO ", -10, 1504f, data, 15_000L, me, 0))
        // Another station's data on a different offset doesn't join it.
        assertNull(a.onFrame("NOISE ", -3, 1800f, data, 20_000L, me, 0))
        val relay = a.onFrame("THERE ${RelayProtocol.checksum16("HELLO THERE")}", -11, 1496f, dataLast, 30_000L, me, 0)
        assertNotNull(relay)
        assertEquals("HELLO THERE ${RelayProtocol.checksum16("HELLO THERE")}", relay!!.text)
        assertEquals(RelayAction.Deliver("HELLO THERE", "W1ABC"), RelayAssembler.decide(relay, me))
    }

    @Test
    fun relaysForOthersOrForGroupsAreNotCollected() {
        val a = RelayAssembler()
        assertNull(a.onFrame("W1ABC: K2XYZ> HI", -10, 1500f, last, 0L, me, 0))
        assertNull(a.onFrame("W1ABC: @RACES> HI", -10, 1500f, last, 0L, me, 0))
        assertNull(a.onFrame("W1ABC: K2XYZ>", -10, 1500f, first, 0L, me, 0))
        assertEquals(0, a.pendingCount())
        // A data frame with nothing started on its offset is not a relay.
        assertNull(a.onFrame("HELLO", -10, 1500f, dataLast, 0L, me, 0))
    }

    @Test
    fun aPortableSuffixStillMeansUs() {
        val relay = RelayAssembler().onFrame("W1ABC: KN4CRD/P> ${signed("HI")}", -10, 900f, last, 0L, me, 0)
        assertNotNull(relay)
    }

    @Test
    fun aQuietRelayIsDropped() {
        val a = RelayAssembler()
        a.onFrame("W1ABC: KN4CRD>", -10, 1500f, first, 0L, me, 0)
        assertEquals(1, a.pendingCount())
        assertNull(a.onFrame(signed("LATE"), -10, 1500f, dataLast, RelayAssembler.DEFAULT_TIMEOUT_MS + 1, me, 0))
        assertEquals(0, a.pendingCount())
    }

    @Test
    fun aRelayThatNeverEndsCannotGrowForever() {
        val a = RelayAssembler(maxChars = 20)
        a.onFrame("W1ABC: KN4CRD>", -10, 1500f, first, 0L, me, 0)
        var t = 0L
        repeat(5) { t += 6_000; a.onFrame("ABCDEFGHIJ", -10, 1500f, data, t, me, 0) }
        assertEquals(0, a.pendingCount())
        assertNull(a.onFrame("END", -10, 1500f, dataLast, t + 6_000, me, 0))
    }

    @Test
    fun aRelayAddressedOnwardIsForwardedWithItsSender() {
        val relay = RelayAssembler().onFrame("W1ABC: KN4CRD> ${signed("K2XYZ> MEET AT 1800")}", -10, 1500f, last, 0L, me, 0)!!
        assertEquals(RelayAction.Forward("K2XYZ> MEET AT 1800 *DE* W1ABC"), RelayAssembler.decide(relay, me))
    }

    @Test
    fun theWayBackFollowsEveryHop() {
        val relay = RelayAssembler().onFrame("W1ABC: KN4CRD> ${signed("SNR? *DE* K3AAA")}", -10, 1500f, last, 0L, me, 0)!!
        assertEquals(RelayAction.Deliver("SNR? *DE* K3AAA", "W1ABC>K3AAA"), RelayAssembler.decide(relay, me))
    }

    @Test
    fun checksumsAreRequiredOnlyForTextInDataFrames() {
        val inline = ReceivedRelay("W1ABC", -10, 0, inlinePayload = true, text = "HELLO THERE")
        assertEquals(RelayAction.Deliver("HELLO THERE", "W1ABC"), RelayAssembler.decide(inline, me))
        val framed = inline.copy(inlinePayload = false)
        assertNull(RelayAssembler.decide(framed, me))
    }

    @Test
    fun acksGroupsAndBlanksAskNothing() {
        fun inline(text: String) = ReceivedRelay("W1ABC", -10, 0, inlinePayload = true, text = text)
        assertNull(RelayAssembler.decide(inline(signed("ACK")), me))
        assertNull(RelayAssembler.decide(inline("@RACES HELLO"), me))
        assertNull(RelayAssembler.decide(inline("   "), me))
        assertNull(RelayAssembler.decide(ReceivedRelay("", -10, 0, true, "HELLO"), me))
    }

    @Test
    fun anImpossibleOffsetIsIgnored() {
        assertNull(RelayAssembler().onFrame("W1ABC: KN4CRD> HI", -10, Float.NaN, last, 0L, me, 0))
    }

    @Test
    fun aSenderThatIsNotACallsignCannotSteerOurReply() {
        val a = RelayAssembler()
        // Free text shaped like a relay: the "sender" would become the path we transmit to.
        assertNull(a.onFrame("EVIL>K2XYZ: KN4CRD> ${signed("HI")}", -10, 1500f, last, 0L, me, 0))
        assertNull(a.onFrame("R\u0000>: KN4CRD> ${signed("HI")}", -10, 1500f, last, 0L, me, 0))
        assertEquals(0, a.pendingCount())
    }

    @Test
    fun onlyCallsignsGoIntoTheWayBack() {
        val relay = ReceivedRelay("W1ABC", -10, 0, true, signed("SNR? *DE* 12345 *DE* K3AAA *DE* KN4CRD"))
        assertEquals(RelayAction.Deliver("SNR? *DE* 12345 *DE* K3AAA *DE* KN4CRD", "W1ABC>K3AAA"), RelayAssembler.decide(relay, me))
    }

    @Test
    fun aRelayNeverLoopsBackToUsOrAStationItPassedThrough() {
        fun relay(payload: String) = ReceivedRelay("W1ABC", -10, 0, true, signed(payload))
        assertNull(RelayAssembler.decide(relay("KN4CRD> HELLO"), me))
        assertNull(RelayAssembler.decide(relay("W1ABC> KN4CRD> W1ABC> HELLO"), me))
        assertNull(RelayAssembler.decide(relay("K3AAA> HELLO *DE* K3AAA"), me))
        assertEquals(RelayAction.Forward("K2XYZ> HELLO *DE* K3AAA *DE* W1ABC"), RelayAssembler.decide(relay("K2XYZ> HELLO *DE* K3AAA"), me))
    }
}
