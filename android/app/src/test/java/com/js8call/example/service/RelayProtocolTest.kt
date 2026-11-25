package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RelayProtocolTest {

    // --- Checksums: must match the native core (core/tools/crc_test.cpp) and JS8Call.

    @Test
    fun crcMatchesTheNativeTestVectors() {
        assertEquals(0x0000, RelayProtocol.crc16Kermit(ByteArray(0)))
        assertEquals(0x2189, RelayProtocol.crc16Kermit("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun packsInBase41LikeTheNativeCore() {
        assertEquals("000", RelayProtocol.pack16Bits(0))
        assertEquals("001", RelayProtocol.pack16Bits(1))
        assertEquals("010", RelayProtocol.pack16Bits(41))
        assertEquals("100", RelayProtocol.pack16Bits(41 * 41))
        // 0x2189 = 8585 = 5*1681 + 4*41 + 16 → "5", "4", "G"
        assertEquals("54G", RelayProtocol.pack16Bits(0x2189))
        // The largest 16-bit value still fits in three characters.
        assertEquals(3, RelayProtocol.pack16Bits(0xFFFF).length)
    }

    @Test
    fun aChecksummedPayloadValidatesAndLosesItsChecksum() {
        val body = "W1AW> HELLO FROM THE FIELD"
        val (ok, back) = RelayProtocol.validateChecksum("$body ${RelayProtocol.checksum16(body)}")
        assertTrue(ok)
        assertEquals(body, back)
    }

    @Test
    fun aCorruptedPayloadFailsTheChecksum() {
        val body = "HELLO FROM THE FIELD"
        val chk = RelayProtocol.checksum16(body)
        assertFalse(RelayProtocol.validateChecksum("HELLO FROM THE FIELF $chk").first)
        assertFalse(RelayProtocol.validateChecksum("abc").first)
        assertFalse(RelayProtocol.validateChecksum("").first)
    }

    @Test
    fun anOptionalChecksumIsOnlyStrippedWhenItLooksLikeOne() {
        assertEquals("HELLO THERE", RelayProtocol.stripOptionalChecksum("HELLO THERE K3J"))
        assertEquals("HELLO THERE", RelayProtocol.stripOptionalChecksum("HELLO THERE"))
        assertEquals("HELLO TH#RE", RelayProtocol.stripOptionalChecksum("HELLO TH#RE"))
        assertEquals("HI A#B", RelayProtocol.stripOptionalChecksum("HI A#B"))
        assertEquals("ABC", RelayProtocol.stripOptionalChecksum("ABC"))
    }

    @Test
    fun theEndOfMessageMarkerIsFoundAndRemoved() {
        val eom = RelayProtocol.EOM_MARKER
        assertEquals("HELLO" to true, RelayProtocol.normalizePayload("HELLO $eom  "))
        assertEquals("HELLO" to false, RelayProtocol.normalizePayload("HELLO  "))
        assertEquals("" to true, RelayProtocol.normalizePayload(eom))
        assertEquals("HELLO", RelayProtocol.stripEom("HELLO$eom"))
    }

    // --- Directed frames.

    @Test
    fun readsADirectedQuery() {
        val d = RelayProtocol.parseDirectedCommand("K1ABC: W2XYZ SNR?")!!
        assertEquals(DirectedCommand("K1ABC", "W2XYZ", "SNR?", ""), d)
    }

    @Test
    fun readsMultiWordCommands() {
        assertEquals("HEARTBEAT SNR", RelayProtocol.parseDirectedCommand("K1ABC: W2XYZ HEARTBEAT SNR -12")!!.command)
        assertEquals("-12", RelayProtocol.parseDirectedCommand("K1ABC: W2XYZ HEARTBEAT SNR -12")!!.payload)
        assertEquals("QUERY MSGS", RelayProtocol.parseDirectedCommand("K1ABC: W2XYZ QUERY MSGS")!!.command)
        assertEquals("MSG", RelayProtocol.parseDirectedCommand("K1ABC: W2XYZ MSG HELLO THERE")!!.command)
        assertEquals("HELLO THERE", RelayProtocol.parseDirectedCommand("K1ABC: W2XYZ MSG HELLO THERE")!!.payload)
    }

    @Test
    fun freeTextKeepsTheFirstWordAsTheCommand() {
        val d = RelayProtocol.parseDirectedCommand("K1ABC: W2XYZ GOOD MORNING")!!
        assertEquals("GOOD", d.command)
        assertEquals("MORNING", d.payload)
    }

    @Test
    fun readsARelayWithAndWithoutASender() {
        assertEquals(
            DirectedCommand("K1ABC", "W2XYZ", ">", "N3QRS> HELLO"),
            RelayProtocol.parseDirectedCommand("K1ABC: W2XYZ> N3QRS> HELLO")
        )
        assertEquals(
            DirectedCommand("", "W2XYZ", ">", "HELLO"),
            RelayProtocol.parseDirectedCommand("W2XYZ> HELLO")
        )
        assertEquals(">", RelayProtocol.parseDirectedCommand("K1ABC: W2XYZ>")?.command)
    }

    @Test
    fun rejectsFramesThatArentDirected() {
        assertNull(RelayProtocol.parseDirectedCommand(""))
        assertNull(RelayProtocol.parseDirectedCommand("   "))
        assertNull(RelayProtocol.parseDirectedCommand("HELLO"))
        assertNull(RelayProtocol.parseDirectedCommand("K1ABC:"))
        assertNull(RelayProtocol.parseDirectedCommand("K1ABC: W2XYZ"))
        // Without a sender, only a relay counts.
        assertNull(RelayProtocol.parseDirectedCommand("W2XYZ SNR?"))
        assertNull(RelayProtocol.parseDirectedCommand(": > X"))
    }

    @Test
    fun findsTheHeartbeatSender() {
        assertEquals("K1ABC", RelayProtocol.heartbeatSender("K1ABC: @HB HEARTBEAT FN42"))
        // Returned as heard; callers compare case-insensitively.
        assertEquals("k1abc", RelayProtocol.heartbeatSender("  k1abc: @hb heartbeat"))
        assertNull(RelayProtocol.heartbeatSender("K1ABC: @HB HEARTBEATS"))
        assertNull(RelayProtocol.heartbeatSender("K1ABC: W2XYZ HEARTBEAT SNR -10"))
        assertNull(RelayProtocol.heartbeatSender(""))
    }

    // --- Forwarding and reply paths.

    @Test
    fun forwardsOnlyToASingleStation() {
        assertEquals("N3QRS>HELLO", RelayProtocol.forwardPayload("N3QRS HELLO"))
        assertEquals("N3QRS> HELLO", RelayProtocol.forwardPayload("  N3QRS> HELLO"))
        assertNull(RelayProtocol.forwardPayload("@ALLCALL HELLO"))
        assertNull(RelayProtocol.forwardPayload("HELLO THERE"))
        assertNull(RelayProtocol.forwardPayload("SNR? *DE* K1ABC"))
        assertNull(RelayProtocol.forwardPayload("N3QRS"))
        assertNull(RelayProtocol.forwardPayload(""))
    }

    @Test
    fun theReplyPathRunsBackThroughEveryHop() {
        // Heard from W2XYZ, which had it from N3QRS, which had it from K1ABC.
        assertEquals(
            listOf("W2XYZ", "N3QRS", "K1ABC"),
            RelayProtocol.pathCallsigns("w2xyz", "SNR? *DE* K1ABC *DE* N3QRS")
        )
        assertEquals(listOf("W2XYZ", "K1ABC"), RelayProtocol.pathCallsigns("W2XYZ", "INFO? via k1abc"))
        assertEquals(listOf("W2XYZ"), RelayProtocol.pathCallsigns("W2XYZ", "SNR?"))
        assertEquals(emptyList<String>(), RelayProtocol.pathCallsigns("", "SNR?"))
    }

    // Second audit (N7): the dictionary's Latin-1 characters are checksummed as the bytes sent.
    @Test
    fun checksumCoversLatin1CharactersAsTheirBytes() {
        val latin1 = RelayProtocol.pack16Bits(RelayProtocol.crc16Kermit(byteArrayOf(0xC0.toByte())))
        assertEquals(latin1, RelayProtocol.checksum16("\u00C0"))
        assertNotEquals(RelayProtocol.checksum16("?"), RelayProtocol.checksum16("\u00C0"))
        assertTrue(RelayProtocol.checksum16Valid(RelayProtocol.checksum16("SE\u00D1OR"), "SE\u00D1OR"))
    }
}
