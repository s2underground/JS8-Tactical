package com.js8call.example.ui.map

/**
 * The map draws its labels from glyphs shipped in the app (assets/map/fonts), which cover
 * U+0000 to U+01FF only so the map works offline. A label with any other character makes
 * MapLibre fail the glyph load, and it then drops every station and label in that map tile, not
 * just the one label: field report (build 342), dots and calls vanished at some zoom levels once
 * stations placed from licence data were labelled "CALL ≈". Link labels had the same problem
 * with their minus sign (U+2212).
 */
object MapGlyphs {
    /** Highest code point the shipped glyph ranges (0-255, 256-511) cover. */
    const val MAX_CHAR = 0x1FF

    fun safe(text: String): String {
        if (text.all { it.code <= MAX_CHAR }) return text
        return buildString(text.length) {
            for (c in text) append(
                when {
                    c.code <= MAX_CHAR -> c
                    c == '−' || c == '–' || c == '—' -> '-'
                    c == '≈' -> '~'
                    else -> '?'
                }
            )
        }
    }
}
