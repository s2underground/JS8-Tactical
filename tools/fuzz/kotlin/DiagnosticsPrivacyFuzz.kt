package com.js8call.example.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.js8call.example.DiagnosticsPrivacy

/**
 * The scrub that takes personal details out of a diagnostics report before a user shares it.
 * Hides the operator's own callsign (any case), another station's callsign (upper case, as the
 * app logs them), the operator's grid and an IP address among arbitrary text, then checks none
 * of them survives as a whole word, whatever surrounds them.
 */
object DiagnosticsPrivacyFuzz {
    private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun letters(d: FuzzedDataProvider, n: Int) = buildString { repeat(n) { append(LETTERS[d.consumeInt(0, 25)]) } }

    /** A callsign: 1–2 letter prefix, a digit, 1–3 letter suffix (K1ABC, KN4XYZ). */
    private fun callsign(d: FuzzedDataProvider) = letters(d, d.consumeInt(1, 2)) + d.consumeInt(0, 9) + letters(d, d.consumeInt(1, 3))

    /** A 4-character Maidenhead grid (EM73). */
    private fun grid(d: FuzzedDataProvider) =
        "${"ABCDEFGHIJKLMNOPQR"[d.consumeInt(0, 17)]}${"ABCDEFGHIJKLMNOPQR"[d.consumeInt(0, 17)]}${d.consumeInt(0, 9)}${d.consumeInt(0, 9)}"

    private fun anyCase(d: FuzzedDataProvider, s: String) = s.map { if (d.consumeBoolean()) it.lowercaseChar() else it }.joinToString("")

    /** [needle] as a whole word (not inside a longer word) anywhere in [hay]; any case unless [exactCase]. */
    private fun visible(hay: String, needle: String, exactCase: Boolean = false) =
        Regex(
            "(?<![A-Za-z0-9])" + Regex.escape(needle) + "(?![A-Za-z0-9])",
            if (exactCase) emptySet() else setOf(RegexOption.IGNORE_CASE),
        ).containsMatchIn(hay)

    @JvmStatic
    fun fuzzerTestOneInput(d: FuzzedDataProvider) {
        val myCall = callsign(d)
        val otherCall = callsign(d)
        val myGrid = grid(d)
        val ip = List(4) { d.consumeInt(1, 254) }.joinToString(".")
        val sep = { if (d.consumeBoolean()) " " else d.pickValue(listOf("\n", "\t", ", ", "(", ")", "<", ">", "=", "\"", "'", "/", "-", "_", ":", ";", "[", "]")) }
        val report = buildString {
            append(d.consumeString(40)); append(sep())
            append(anyCase(d, myCall)); append(sep())
            append(d.consumeString(40)); append(sep())
            append(otherCall); append(sep())
            append(d.consumeString(40)); append(sep())
            append(anyCase(d, myGrid)); append(sep())
            append(d.consumeString(40)); append(" ")
            append(ip); append(" ")
            append(d.consumeRemainingAsString())
        }
        val out = DiagnosticsPrivacy.scrub(report, myCall, myGrid)
        check(!visible(out, myCall)) { "own callsign $myCall survived in: $out" }
        // Other stations' calls are masked as the app logs them, in upper case. (A lower-case word of
        // that shape is left alone by design: in a log it is far more often an id or a file name.)
        check(!visible(out, otherCall, exactCase = true)) { "callsign $otherCall survived in: $out" }
        check(!visible(out, myGrid)) { "own grid $myGrid survived in: $out" }
        check(!Regex("(?<![A-Za-z0-9.])" + Regex.escape(ip) + "(?![0-9.])").containsMatchIn(out)) { "IP $ip survived in: $out" }
    }
}
