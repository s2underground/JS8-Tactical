package com.js8call.example.service

import com.js8call.example.util.CallsignValidator
import com.js8call.example.util.TxMessageClassifier
import java.util.Locale

/**
 * Pure text rules for what this station puts on the air, and for the spots it reports about what
 * it hears. Moved out of [JS8EngineService] so they can be unit-tested and fuzzed.
 *
 * Location rule: the only grid this app ever hands the radio is the 4-character square (EM73),
 * the precision JS8 frames carry anyway. A GPS-filled 8-character locator stays on the device.
 * (Text the operator types is sent as typed.)
 */
object OnAirText {
    private val SQUARE = Regex("[A-R]{2}[0-9]{2}")

    /** Any Maidenhead locator, 4, 6 or 8 characters, as a whole word. */
    private val ANY_GRID = Regex("(?<![A-Za-z0-9])[A-Ra-r]{2}[0-9]{2}(?:[A-Xa-x]{2}(?:[0-9]{2})?)?(?![A-Za-z0-9])")

    /** A heard station's locator in received text: 4 or 6 characters, as JS8Call reports it. */
    private val SPOT_GRID = Regex("(?<![A-Za-z0-9])[A-Ra-r]{2}[0-9]{2}(?:[A-Xa-x]{2})?(?![A-Za-z0-9])")

    private val HEARD_EXCLUDE_TOKENS = setOf("CQ", "HB", "HEARTBEAT", "ALLCALL", "@ALLCALL")

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** The 4-character square of a locator (em73tu45 → EM73), or "" when it doesn't start with one. */
    fun grid4(grid: String?): String {
        val g = grid?.trim().orEmpty()
        if (g.length < 4) return ""
        val square = g.substring(0, 4).uppercase(Locale.ROOT)
        return if (SQUARE.matches(square)) square else ""
    }

    /**
     * A CQ or heartbeat gets the station's 4-character square appended, unless the text already
     * carries a locator. [grid] may be any length; only its square is ever added.
     */
    fun withHeartbeatGrid(text: String, grid: String): String {
        val trimmed = text.trim()
        val square = grid4(grid)
        if (square.isEmpty()) return trimmed
        if (!TxMessageClassifier.shouldAppendGrid(trimmed)) return trimmed
        if (ANY_GRID.containsMatchIn(trimmed)) return trimmed
        return "$trimmed $square"
    }

    /** The message as the engine frames it: the selected station in front, unless it's already there. */
    fun withDirected(text: String, directedCall: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val selected = directedCall.trim().uppercase(Locale.ROOT)
        if (selected.isEmpty()) return trimmed
        if (trimmed.startsWith("`")) return trimmed
        if (TxMessageClassifier.isBaseMessage(trimmed)) return trimmed
        if (trimmed.startsWith(selected, ignoreCase = true)) return trimmed
        return "$selected $trimmed"
    }

    /** The sending station of a received line ("W1ABC: …", "W1ABC>RELAY …"), if it's a callsign. */
    fun heardCallsign(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        var token = trimmed.split(Regex("\\s+"), limit = 2)[0].trimEnd(':').uppercase(Locale.ROOT)
        if (token.contains(">")) token = token.substringBefore(">").trimEnd(':')
        if (token.startsWith("@")) return null
        if (token in HEARD_EXCLUDE_TOKENS) return null
        return token.takeIf(CallsignValidator::isAmateurCallsign)
    }

    /** The first 4- or 6-character locator in received text, upper case. */
    fun spotGrid(text: String): String? = SPOT_GRID.find(text)?.value?.uppercase(Locale.ROOT)

    /**
     * A decode's HHMMSS (UTC) as epoch seconds: the moment within 12 hours of [nowMs] (so a decode
     * just before midnight lands on the right day). A time that isn't a valid HHMMSS reads as now.
     */
    fun decodeTimeToEpochSeconds(utc: Int, nowMs: Long): Long {
        val h = utc / 10000
        val m = (utc / 100) % 100
        val s = utc % 100
        if (utc < 0 || h > 23 || m > 59 || s > 59) return Math.floorDiv(nowMs, 1000L)
        var candidate = Math.floorDiv(nowMs, DAY_MS) * DAY_MS + (h * 3600L + m * 60L + s) * 1000L
        val diff = candidate - nowMs
        if (diff > DAY_MS / 2) candidate -= DAY_MS else if (diff < -DAY_MS / 2) candidate += DAY_MS
        return Math.floorDiv(candidate, 1000L)
    }

    data class Spot(val call: String, val grid: String, val rfHz: Long, val timestampS: Long)

    /**
     * What we'd report to PSK Reporter for a received line: a station other than us that sent its
     * locator, on a known dial frequency. Null when there's nothing to report.
     */
    fun pskSpot(text: String, myCall: String, dialHz: Long, offsetHz: Float, utc: Int, nowMs: Long): Spot? {
        if (dialHz <= 0) return null
        val call = heardCallsign(text) ?: return null
        if (myCall.isNotBlank() && CallsignValidator.matches(myCall, call)) return null
        val grid = spotGrid(text) ?: return null
        if (offsetHz.isNaN() || offsetHz < 0f || offsetHz > 10_000f) return null
        val rfHz = dialHz + offsetHz.toLong()
        if (rfHz <= 0) return null
        return Spot(call, grid, rfHz, decodeTimeToEpochSeconds(utc, nowMs))
    }
}
