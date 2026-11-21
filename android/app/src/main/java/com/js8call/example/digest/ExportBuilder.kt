package com.js8call.example.digest

import com.js8call.example.ai.AiPrompt
import com.js8call.example.reports.Reports
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Plain-text exports from the Digest, for the operator to keep:
 * - the complete raw log: every transmission heard or sent in the period, as received;
 * - the roll-up: only the longer reports, heard in full, each once (relayed copies merged),
 *   with short traffic (heartbeats, SNR requests, acks) and garbled or incomplete messages left out.
 * Times are UTC. Nothing is summarised or reworded: this is the record.
 */
object ExportBuilder {

    data class Entry(
        val timeMs: Long,
        /** true = sent by this station. */
        val tx: Boolean,
        val sender: String?,
        val recipient: String?,
        val body: String,
        val snr: Int? = null,
        val laneHz: Float? = null,
        val complete: Boolean = true,
        val missingFrames: Int = 0,
        val copies: Int = 1,
    )

    data class Header(val myCall: String, val sinceMs: Long, val nowMs: Long, val periodLabel: String)

    /** Below this a complete message is short traffic, not a report (same line as the Digest's bulletins). */
    const val REPORT_CHARS = 80
    private const val WRAP = 76

    private fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun full(ms: Long) = utc("yyyy-MM-dd HH:mm:ss'Z'").format(Date(ms))
    private fun short(ms: Long) = utc("dd MMM yyyy HH:mm'Z'").format(Date(ms))
    private fun hhmm(ms: Long) = utc("dd MMM HH:mm'Z'").format(Date(ms))

    fun fileName(rollup: Boolean, nowMs: Long, myCall: String): String =
        listOf("js8tactical", myCall.ifBlank { null }, if (rollup) "rollup" else "raw-log", utc("yyyyMMdd-HHmm'Z'").format(Date(nowMs)))
            .filterNotNull().joinToString("-") { it.replace(Regex("[^A-Za-z0-9-]"), "") } + ".txt"

    private fun StringBuilder.title(name: String, h: Header) {
        appendLine("JS8-Tactical $name")
        if (h.myCall.isNotBlank()) appendLine("Station:   ${h.myCall}")
        appendLine("Period:    ${short(h.sinceMs)} to ${short(h.nowMs)} (${h.periodLabel})")
        appendLine("Exported:  ${full(h.nowMs)}")
    }

    // ------------------------------------------------------------------ raw log

    fun rawLog(h: Header, entries: List<Entry>): String = buildString {
        val list = entries.filter { it.timeMs in h.sinceMs..h.nowMs }.sortedBy { it.timeMs }
        title("raw log", h)
        appendLine("Contents:  every transmission heard or sent, oldest first, exactly as decoded.")
        appendLine("           [...] marks frames that were not heard.")
        appendLine("Count:     ${list.count { !it.tx }} received, ${list.count { it.tx }} sent")
        appendLine()
        list.forEach { e ->
            val parts = mutableListOf(full(e.timeMs), if (e.tx) "TX" else "RX", "${e.sender ?: if (e.tx) h.myCall.ifBlank { "me" } else "?"} > ${e.recipient ?: "(none)"}")
            e.snr?.let { parts += "SNR ${if (it > 0) "+" else ""}$it" }
            e.laneHz?.let { parts += "${it.toInt()} Hz" }
            if (!e.tx) parts += if (e.complete) "complete" else "INCOMPLETE (${e.missingFrames} frame${if (e.missingFrames == 1) "" else "s"} missing)"
            if (e.copies > 1) parts += "${e.copies} copies"
            appendLine(parts.joinToString("  "))
            appendLine("    ${e.body.trim().replace(Regex("\\s*\\n\\s*"), " ")}")
        }
        if (list.isEmpty()) appendLine("(Nothing heard or sent in this period.)")
    }

    // ------------------------------------------------------------------ roll-up

    /** A longer report heard in full: what goes into the roll-up. */
    fun isReport(e: Entry): Boolean =
        !e.tx && e.complete && e.missingFrames == 0 && e.sender != null && !e.body.contains("[…]") &&
            (e.body.trim().length >= REPORT_CHARS || Reports.parse(e.body) != null)

    private data class Item(val first: Entry, val all: List<Entry>)

    fun rollup(h: Header, entries: List<Entry>): String = buildString {
        val heard = entries.filter { !it.tx && it.timeMs in h.sinceMs..h.nowMs }
        val reports = heard.filter { isReport(it) }
        // Identical text from different stations is one report passed on by relays.
        val items = reports.groupBy { AiPrompt.key(AiPrompt.bulletinBody(it.body)) }.values
            .map { g -> g.sortedBy { it.timeMs }.let { Item(it.first(), it) } }
            .sortedBy { it.first.timeMs }
        val garbled = heard.count { !(it.complete && it.missingFrames == 0 && it.sender != null && !it.body.contains("[…]")) }
        val short = heard.size - garbled - reports.size
        val merged = reports.size - items.size

        title("roll-up", h)
        appendLine("Contents:  longer reports heard in full, oldest first, as received.")
        appendLine("           ${items.size} report${s(items.size)}" +
            (if (merged > 0) " ($merged relayed cop${if (merged == 1) "y" else "ies"} merged)" else "") + ".")
        appendLine("Left out:  $short short message${s(short)} (heartbeats, SNR requests, acks, short calls),")
        appendLine("           $garbled incomplete or garbled message${s(garbled)}. The raw log export has everything.")
        appendLine()
        if (items.isEmpty()) appendLine("(No longer reports heard in full in this period.)")
        items.forEachIndexed { i, it ->
            val e = it.first
            appendLine("=".repeat(WRAP))
            appendLine("[${i + 1}]  ${short(e.timeMs)}   ${e.sender} > ${e.recipient ?: "(none)"}" +
                (e.snr?.let { s -> "   SNR ${if (s > 0) "+" else ""}$s" } ?: ""))
            val others = it.all.drop(1)
            val relays = others.filter { o -> o.sender != e.sender }
            val repeats = others.size - relays.size + it.all.sumOf { o -> o.copies - 1 }
            if (relays.isNotEmpty()) appendLine("     Also heard from: " +
                relays.distinctBy { o -> o.sender }.joinToString(", ") { o -> "${o.sender} (${hhmm(o.timeMs)})" })
            if (repeats > 0) appendLine("     Heard $repeats more time${s(repeats)} from ${e.sender}")
            appendLine()
            wrap(e.body.trim()).forEach { line -> appendLine(line) }
            appendLine()
        }
    }

    private fun s(n: Int) = if (n == 1) "" else "s"

    /** Word-wrap for reading in any text editor; very long words are left whole. */
    fun wrap(text: String, width: Int = WRAP): List<String> =
        text.split('\n').flatMap { para ->
            val out = ArrayList<String>()
            var line = StringBuilder()
            para.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { w ->
                if (line.isNotEmpty() && line.length + 1 + w.length > width) { out += line.toString(); line = StringBuilder() }
                if (line.isNotEmpty()) line.append(' ')
                line.append(w)
            }
            out += line.toString()
            out
        }
}
