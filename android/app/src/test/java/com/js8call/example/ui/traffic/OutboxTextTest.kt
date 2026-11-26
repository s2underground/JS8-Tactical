package com.js8call.example.ui.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTextTest {
    @Test
    fun heartbeatsAreLeftOut() {
        assertTrue(OutboxText.isHeartbeat("HB EM96"))
        assertTrue(OutboxText.isHeartbeat("@HB HEARTBEAT EM96"))
        assertTrue(OutboxText.isHeartbeat("K1ABC HEARTBEAT SNR -10"))
        assertTrue(OutboxText.isHeartbeat("W2XYZ: @HB HEARTBEAT EM96"))
        assertTrue(OutboxText.isHeartbeat("  hb em96 "))
    }

    @Test
    fun realMessagesStay() {
        assertFalse(OutboxText.isHeartbeat("K1ABC MSG MEET AT THE HBO SIGN"))
        assertFalse(OutboxText.isHeartbeat("@GHOSTNET CHECKING IN"))
        assertFalse(OutboxText.isHeartbeat("CQ CQ CQ EM96"))
        assertFalse(OutboxText.isHeartbeat("K1ABC>N3QRS>HEARTBEATS ARE FINE"))
    }

    @Test
    fun resendDropsTheStoppedNote() {
        val raw = "K1ABC ROAD BLOCKED AT [STOPPED · FRAMES SENT: 2 OF 5]"
        assertTrue(OutboxText.wasStopped(raw))
        assertEquals("K1ABC ROAD BLOCKED AT", OutboxText.resendText(raw))
    }

    @Test
    fun resendKeepsAddressAndRelayPath() {
        assertEquals("K1ABC>N3QRS>WATER AT CP2", OutboxText.resendText(" K1ABC>N3QRS>WATER AT CP2 "))
        assertEquals("@GHOSTNET QSL [1/2]", OutboxText.resendText("@GHOSTNET QSL [1/2]"))
        assertFalse(OutboxText.wasStopped("@GHOSTNET QSL [1/2]"))
    }
}
