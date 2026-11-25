package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxProgressTest {

    private val frames = listOf("W1ABC MSG ", "ROADS CLOSE", "D NORTH ", "OF TOWN E9W")

    @Test
    fun beforeTheFirstFrameEverythingIsQueued() {
        val p = TxProgress(frames, started = 0, onAir = false)
        assertEquals(0, p.done)
        assertEquals(0, p.onAirNumber)
        assertEquals("", p.sentText)
        assertEquals("", p.onAirText)
        assertEquals(frames.joinToString(""), p.queuedText)
        assertFalse(p.complete)
    }

    @Test
    fun theFrameOnAirIsNeitherSentNorQueued() {
        val p = TxProgress(frames, started = 2, onAir = true)
        assertEquals(1, p.done)
        assertEquals(2, p.onAirNumber)
        assertEquals("W1ABC MSG ", p.sentText)
        assertEquals("ROADS CLOSE", p.onAirText)
        assertEquals("D NORTH OF TOWN E9W", p.queuedText)
        // Nothing is lost or repeated between the three parts.
        assertEquals(frames.joinToString(""), p.sentText + p.onAirText + p.queuedText)
    }

    @Test
    fun betweenFramesTheLastOneCountsAsSent() {
        val p = TxProgress(frames, started = 2, onAir = false)
        assertEquals(2, p.done)
        assertEquals(0, p.onAirNumber)
        assertEquals("W1ABC MSG ROADS CLOSE", p.sentText)
        assertEquals("D NORTH OF TOWN E9W", p.queuedText)
    }

    @Test
    fun afterTheLastFrameTheSendIsComplete() {
        val p = TxProgress(frames, started = 4, onAir = false)
        assertTrue(p.complete)
        assertEquals(frames.joinToString(""), p.sentText)
        assertEquals("", p.queuedText)
    }

    @Test
    fun aStopMidFrameCountsOnlyWholeFrames() {
        // Halted while frame 3 was on air: receivers can't copy a cut frame.
        val p = TxProgress(frames, started = 3, onAir = true)
        assertEquals(2, p.done)
        assertEquals("W1ABC MSG ROADS CLOSE", p.sentText)
    }

    @Test
    fun aMissedTransitionNeverRunsPastTheMessage() {
        val p = TxProgress(frames, started = 9, onAir = true)
        assertEquals(4, p.done)
        assertEquals(0, p.onAirNumber)
        assertEquals("", p.queuedText)
    }

    @Test
    fun displayDropsOurOwnCallButKeepsFrameBoundaries() {
        val shown = TxProgress.forDisplay(listOf("KN4CRD: HELL", "O FROM THE F", "IELD"), "kn4crd")
        assertEquals(listOf("HELL", "O FROM THE F", "IELD"), shown)
    }

    @Test
    fun displayDropsALongCompoundCallAcrossFrames() {
        val shown = TxProgress.forDisplay(listOf("EA8/K1ABC: ", "HI", " ALL"), "EA8/K1ABC")
        assertEquals(listOf("", "HI", " ALL"), shown)
        val split = TxProgress.forDisplay(listOf("EA8/K1A", "BC: HI ALL"), "EA8/K1ABC")
        assertEquals(listOf("", "HI ALL"), split)
    }

    @Test
    fun displayLeavesOtherSendsAlone() {
        val directed = listOf("W1ABC SNR?")
        assertEquals(directed, TxProgress.forDisplay(directed, "KN4CRD"))
        // Another station's call that merely starts like ours is not ours.
        val other = listOf("KN4CRDX: HI")
        assertEquals(other, TxProgress.forDisplay(other, "KN4CRD"))
        assertEquals(directed, TxProgress.forDisplay(directed, ""))
    }
}
