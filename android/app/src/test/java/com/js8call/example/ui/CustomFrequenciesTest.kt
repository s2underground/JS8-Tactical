package com.js8call.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomFrequenciesTest {
    @Test fun roundTripKeepsOrderAndNames() {
        val list = listOf(
            CustomFrequencies.Entry(7_107_000, "GhostNet"),
            CustomFrequencies.Entry(7_095_000, "Local Net"),
            CustomFrequencies.Entry(14_078_500, ""),
        )
        assertEquals(list, CustomFrequencies.parse(CustomFrequencies.encode(list)))
    }

    @Test fun labelsReadLikeThePresets() {
        // Presets read "40m - 7.078 MHz"; RadioControls.shortLabel turns " - " into " · ".
        assertEquals("GhostNet - 7.107 MHz", CustomFrequencies.bandLabel(CustomFrequencies.Entry(7_107_000, "GhostNet")))
        assertEquals("Custom - 14.0785 MHz", CustomFrequencies.bandLabel(CustomFrequencies.Entry(14_078_500, " ")))
        assertEquals("GhostNet · 7.107", RadioControls.shortLabel("GhostNet - 7.107 MHz"))
    }

    @Test fun anyPositiveFrequencyIsAccepted() {
        assertEquals(7_095_000L, CustomFrequencies.parseMhz("7.095"))
        assertEquals(7_095_000L, CustomFrequencies.parseMhz(" 7,095 "))
        assertEquals(146_520_000L, CustomFrequencies.parseMhz("146.52"))
        assertNull(CustomFrequencies.parseMhz("0"))
        assertNull(CustomFrequencies.parseMhz("abc"))
        assertNull(CustomFrequencies.parseMhz("NaN"))
    }

    @Test fun junkLinesAndDuplicatesAreDropped() {
        val raw = "7107000\tGhostNet\nnot a number\tx\n-5\tneg\n7107000\tagain\n3575000"
        assertEquals(
            listOf(CustomFrequencies.Entry(7_107_000, "GhostNet"), CustomFrequencies.Entry(3_575_000, "")),
            CustomFrequencies.parse(raw),
        )
    }

    @Test fun labelsAreOneShortLine() {
        assertEquals("Net A", CustomFrequencies.cleanLabel("  Net\t\nA "))
        assertEquals(CustomFrequencies.MAX_LABEL, CustomFrequencies.cleanLabel("x".repeat(100)).length)
    }
}
