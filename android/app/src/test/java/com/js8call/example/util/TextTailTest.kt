package com.js8call.example.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextTailTest {
    @Test fun shortTextUnchanged() {
        assertEquals("a\nb\n", tailLines("a\nb\n", 10))
    }

    @Test fun dropsThePartialFirstLine() {
        // Report seen: "...appears on every send at 1987 Hz" cut to "very send at 1987 Hz".
        val text = "first line here\nsecond\nthird\n"
        assertEquals("second\nthird\n", tailLines(text, 20))
    }

    @Test fun keepsALineWhenTheCutLandsOnItsStart() {
        val text = "aaaa\nbbbb\ncccc\n"
        assertEquals("bbbb\ncccc\n", tailLines(text, 10))
    }

    @Test fun oneLongLineIsKeptAsCut() {
        assertEquals("6789", tailLines("0123456789", 4))
    }
}
