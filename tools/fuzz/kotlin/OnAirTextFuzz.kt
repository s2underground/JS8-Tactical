package com.js8call.example.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.js8call.example.service.OnAirText
import com.js8call.example.util.CallsignValidator

/**
 * What the station transmits and what it reports to PSK Reporter about what it hears. Received
 * text comes from anyone on the band; the station's grid may be a GPS-filled 8-character locator.
 *
 * Checks: nothing throws; a heartbeat or CQ only ever gains the 4-character square (never the
 * finer characters of the station's locator); a directed message only ever gains the selected
 * station in front; a heard callsign is a valid amateur callsign; a spot is never of ourselves,
 * carries a 4- or 6-character locator found in the text, a frequency above the dial, and a time
 * within 12 hours of now.
 */
object OnAirTextFuzz {
    private val LETTERS = "ABCDEFGHIJKLMNOPQR"
    private val SUB = "abcdefghijklmnopqrstuvwx"

    @JvmStatic
    fun fuzzerTestOneInput(d: FuzzedDataProvider) {
        // The station's own locator, 8 characters as GPS fills it, in any case.
        val grid8 = buildString {
            append(LETTERS[d.consumeInt(0, 17)]); append(LETTERS[d.consumeInt(0, 17)])
            append(d.consumeInt(0, 9)); append(d.consumeInt(0, 9))
            append(SUB[d.consumeInt(0, 23)]); append(SUB[d.consumeInt(0, 23)])
            append(d.consumeInt(0, 9)); append(d.consumeInt(0, 9))
        }.let { if (d.consumeBoolean()) it.uppercase() else it }
        val gps = d.consumeBoolean()
        val myGrid = if (gps) grid8 else d.consumeString(10)
        val myCall = d.consumeString(10)
        val directed = d.consumeString(10)
        val utc = d.consumeInt()
        val nowMs = d.consumeLong(0L, 4_102_444_800_000L) // to 2100
        val dial = d.consumeLong(-1L, 500_000_000L)
        val offset = d.consumeFloat()
        val text = d.consumeRemainingAsString()

        val square = OnAirText.grid4(myGrid)
        check(square.isEmpty() || square.length == 4) { "square '$square' from '$myGrid'" }

        val hb = OnAirText.withHeartbeatGrid(text, myGrid)
        val t = text.trim()
        check(hb == t || (square.isNotEmpty() && hb == "$t $square")) { "heartbeat '$t' became '$hb' (grid '$myGrid')" }
        // The 6- and 8-character forms never appear unless the operator typed them.
        val six = if (gps) myGrid.substring(0, 6) else ""
        if (gps && !t.contains(six, ignoreCase = true)) {
            check(!hb.contains(six, ignoreCase = true)) { "fine grid characters went out: '$hb'" }
        }

        val dm = OnAirText.withDirected(text, directed)
        val sel = directed.trim().uppercase()
        check(dm.isEmpty() == t.isEmpty()) { "directed text appeared or vanished: '$t' → '$dm'" }
        check(dm == t || dm == "$sel $t") { "directed '$t' became '$dm'" }

        OnAirText.heardCallsign(text)?.let { c ->
            check(CallsignValidator.isAmateurCallsign(c)) { "heard '$c' is not a callsign" }
        }

        OnAirText.spotGrid(text)?.let { g ->
            check((g.length == 4 || g.length == 6) && text.contains(g, ignoreCase = true)) { "spot grid '$g'" }
        }

        val ts = OnAirText.decodeTimeToEpochSeconds(utc, nowMs)
        check(kotlin.math.abs(ts * 1000 - nowMs) <= 12 * 3_600_000L + 1000) { "time $utc at $nowMs → $ts" }

        OnAirText.pskSpot(text, myCall, dial, offset, utc, nowMs)?.let { s ->
            check(myCall.isBlank() || !CallsignValidator.matches(myCall, s.call)) { "spotted ourselves: $s" }
            check(CallsignValidator.isAmateurCallsign(s.call)) { "bad call: $s" }
            check(s.grid.length == 4 || s.grid.length == 6) { "bad grid: $s" }
            check(s.rfHz >= dial && dial > 0) { "frequency below the dial: $s (dial $dial)" }
        }
    }
}
