package com.js8call.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WaterfallRangeTest {
    @Test
    fun viewIsAlwaysZeroToThreePointFiveKilohertz() {
        // 6 kHz of spectrum in 2.93 Hz bins (12 kHz audio, 4096-point FFT).
        val binHz = 6000f / 2048
        val end = WaterfallRenderer.displayEndBin(2048, binHz)
        assertEquals(3500f, end * binHz, binHz)
    }

    @Test
    fun narrowCardShowsEverything() {
        assertEquals(500, WaterfallRenderer.displayEndBin(500, 6f))
        assertEquals(800, WaterfallRenderer.displayEndBin(800, 0f))
    }
}
