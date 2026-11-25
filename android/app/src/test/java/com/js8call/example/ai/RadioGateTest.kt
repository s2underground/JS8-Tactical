package com.js8call.example.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioGateTest {
    @Test
    fun returnsAtOnceWhenQuiet() {
        var slept = 0
        assertTrue(RadioGate.awaitQuiet(isSending = { false }, sleep = { slept++ }))
        assertEquals(0, slept)
    }

    @Test
    fun waitsUntilTheRadioStopsSending() {
        var clock = 0L
        var polls = 0
        val ok = RadioGate.awaitQuiet(isSending = { polls < 4 }, now = { clock }, sleep = { clock += it; polls++ })
        assertTrue(ok)
        assertEquals(4, polls)
    }

    @Test
    fun givesUpAfterTheLongestWait() {
        var clock = 0L
        val ok = RadioGate.awaitQuiet(maxWaitMs = 10_000, isSending = { true }, now = { clock }, sleep = { clock += it })
        assertFalse(ok)
        assertTrue(clock in 10_000..10_250)
    }
}
