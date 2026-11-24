package com.js8call.example.reports

import kotlin.math.roundToInt

/** Helpers for #JAM (interference / jamming) reports on the map. Pure, so unit tested. */
object JamReports {

    /** Icon opacity for a report [ageMs] old: full for the first hour, fading to 0.35 at 12 hours. */
    fun opacity(ageMs: Long): Float {
        val hours = ageMs / 3_600_000f
        return when {
            hours <= 1f -> 1f
            hours >= 12f -> 0.35f
            else -> 1f - 0.65f * (hours - 1f) / 11f
        }
    }

    /**
     * A report's frequency as whole kHz, for matching reports of the same frequency: "7078",
     * "7078.5" and "7.078" (MHz) all give 7078 or 7079. Null if there's no number.
     */
    fun khz(value: String?): Int? {
        val n = value?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() } ?: return null
        val khz = if (n < 1000 && value.contains('.')) n * 1000 else n
        return khz.roundToInt().takeIf { it > 0 }
    }

    /** Same frequency, allowing for one side writing the dial and the other the signal (within 3 kHz). */
    fun sameFrequency(a: String?, b: String?): Boolean {
        val x = khz(a) ?: return false
        val y = khz(b) ?: return false
        return kotlin.math.abs(x - y) <= 3
    }
}
