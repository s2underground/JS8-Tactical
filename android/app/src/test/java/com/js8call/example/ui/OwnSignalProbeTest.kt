package com.js8call.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class OwnSignalProbeTest {
    private val binHz = 6000f / 6827
    private val r = Random(3)

    /** A noisy band (linear power), with an optional extra signal from [fromHz] for [widthHz]. */
    private fun frame(fromHz: Float? = null, widthHz: Float = 50f, gain: Float = 30f): FloatArray =
        FloatArray(6827) { i ->
            val hz = i * binHz
            val noise = 1f + r.nextFloat()
            if (fromHz != null && hz >= fromHz && hz < fromHz + widthHz) noise * gain else noise
        }

    private fun run(probe: OwnSignalProbe, sentHz: Float, seenAtHz: Float?): OwnSignalProbe.Reading? {
        repeat(40) { probe.feed(frame(), binHz, null, 50f) }
        repeat(60) { probe.feed(frame(seenAtHz), binHz, sentHz, 50f) }
        return probe.feed(frame(), binHz, null, 50f)
    }

    @Test
    fun findsOurSignalWhereItWasSent() {
        val reading = run(OwnSignalProbe(), 1791f, 1791f)
        assertNotNull(reading)
        assertEquals(1791f, reading!!.heardHz, 6f)
        assertEquals(1791f, reading.sentHz, 0f)
    }

    @Test
    fun reportsWhereItReallyShowedUp() {
        val reading = run(OwnSignalProbe(), 1791f, 1975f)
        assertEquals(1975f, reading!!.heardHz, 6f)
    }

    @Test
    fun nothingWhenTheReceiverIsMutedWhileSending() {
        assertNull(run(OwnSignalProbe(), 1791f, null))
    }
}
