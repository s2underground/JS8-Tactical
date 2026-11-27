package com.js8call.example.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGlyphsTest {
    @Test fun plainLabelsUnchanged() {
        assertEquals("KD9ABC", MapGlyphs.safe("KD9ABC"))
        // The middle dot in report labels is U+00B7, inside the shipped range.
        assertEquals("JAM 7078 · W1AW", MapGlyphs.safe("JAM 7078 · W1AW"))
    }

    @Test fun charactersWithoutShippedGlyphsAreReplaced() {
        assertEquals("K1ABC ~", MapGlyphs.safe("K1ABC ≈"))
        assertEquals("-12 dB", MapGlyphs.safe("−12 dB"))
        assertEquals("?", MapGlyphs.safe("☃"))
    }

    @Test fun everyOutputCharacterIsCovered() {
        val all = (0x20..0x2FFF).map { it.toChar() }.joinToString("")
        assertTrue(MapGlyphs.safe(all).all { it.code <= MapGlyphs.MAX_CHAR })
    }

    @Test fun shippedGlyphRangesMatch() {
        // If someone adds a range under assets/map/fonts, MAX_CHAR should grow with it.
        val fonts = listOf("src/main/assets/map/fonts", "app/src/main/assets/map/fonts", "android/app/src/main/assets/map/fonts")
            .map { java.io.File(it) }.firstOrNull { it.isDirectory } ?: return
        fonts.listFiles()!!.filter { it.isDirectory }.forEach { stack ->
            val ends = stack.listFiles()!!.map { it.name.substringBefore(".pbf").substringAfter('-').toInt() }
            assertEquals("${stack.name} glyph ranges", MapGlyphs.MAX_CHAR, ends.max())
        }
    }
}
