package com.js8call.example.net

import com.js8call.example.reports.Reports
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A net's roster, worked out from traffic heard to one group (@TEAM, @GHOSTNET…).
 *
 * Works with or without a net control station: on a free-for-all net like GhostNet it is
 * simply who checked in and when; with an expected list it also shows who hasn't.
 * Pure Kotlin, unit-tested.
 */
object NetRoster {

    enum class Status { GOOD, NEED, EMERGENCY, NONE }

    /** Leading precedence word, as plain text anyone can read. Highest first. */
    enum class Precedence { FLASH, IMMEDIATE, PRIORITY, ROUTINE }

    data class Msg(val sender: String?, val recipient: String?, val body: String, val timeMs: Long, val complete: Boolean)
    data class Sent(val to: String?, val text: String, val timeMs: Long)
    data class Seen(val grid: String?, val snr: Int?, val lastHeardMs: Long)

    data class Input(
        val group: String,
        val sinceMs: Long,
        val nowMs: Long,
        val myCall: String,
        val messages: List<Msg>,
        val sent: List<Sent>,
        /** Every station heard, with its latest grid and SNR. */
        val stations: Map<String, Seen>,
        /** Callsigns expected on this net (may be empty). */
        val expected: List<String>,
    )

    data class CheckIn(
        val call: String,
        val firstMs: Long,
        val lastMs: Long,
        val messages: Int,
        val status: Status,
        val statusText: String?,
        val lastText: String,
        val grid: String?,
        val snr: Int?,
        /** We sent something to this station after its first check-in. */
        val answered: Boolean,
        val precedence: Precedence?,
        val expected: Boolean,
    )

    /** Expected but not checked in; [lastHeardMs] if heard on the air at all since the net started. */
    data class Missing(val call: String, val lastHeardMs: Long?)

    data class Roster(val group: String, val sinceMs: Long, val nowMs: Long, val checkedIn: List<CheckIn>, val missing: List<Missing>) {
        val answeredCount get() = checkedIn.count { it.answered }

        /** Plain-text net log for copy/share. */
        fun toText(): String = buildString {
            appendLine("NET LOG $group ${stamp(sinceMs)}–${hhmm(nowMs)} UTC")
            appendLine("${checkedIn.size} checked in" + if (missing.isNotEmpty()) ", ${missing.size} not yet" else "")
            checkedIn.forEach { c ->
                append("${hhmm(c.firstMs)}  ${c.call}")
                c.grid?.let { append("  $it") }
                c.snr?.let { append("  $it dB") }
                c.statusText?.let { append("  $it") }
                c.precedence?.takeIf { it != Precedence.ROUTINE }?.let { append("  [$it]") }
                if (c.messages > 1) append("  (${c.messages} msgs)")
                appendLine()
                appendLine("       ${c.lastText.take(100)}")
            }
            if (missing.isNotEmpty()) {
                appendLine("NOT CHECKED IN:")
                missing.forEach { m -> appendLine("  ${m.call}" + (m.lastHeardMs?.let { "  (on the air, last heard ${hhmm(it)})" } ?: "  (not heard)")) }
            }
        }.trimEnd()
    }

    private val precedenceWord = Regex("""^\s*(FLASH|IMMEDIATE|PRIORITY|ROUTINE)\b""")

    fun precedenceOf(body: String): Precedence? =
        precedenceWord.find(body.uppercase())?.groupValues?.get(1)?.let { Precedence.valueOf(it) }

    fun build(input: Input): Roster {
        val group = input.group.uppercase()
        val me = input.myCall.uppercase()
        val toGroup = input.messages.filter {
            it.recipient?.uppercase() == group && it.sender != null && it.sender.uppercase() != me &&
                it.timeMs >= input.sinceMs && it.timeMs <= input.nowMs + 60_000L
        }
        val expected = input.expected.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val checkedIn = toGroup.groupBy { it.sender!!.uppercase() }.map { (call, msgs) ->
            val sorted = msgs.sortedBy { it.timeMs }
            val latest = sorted.last()
            // The latest check-in or sitrep decides the status.
            val report = sorted.mapNotNull { m -> Reports.parse(m.body)?.takeIf { it.type == Reports.CHECKIN || it.type == Reports.SITREP } }.lastOrNull()
            val s = report?.values?.get("S")?.uppercase()
            val status = when {
                s == null -> Status.NONE
                s.startsWith("EMERG") || s.startsWith("RED") -> Status.EMERGENCY
                s.startsWith("NEED") || s.startsWith("AMBER") -> Status.NEED
                s.startsWith("GOOD") || s.startsWith("GREEN") -> Status.GOOD
                else -> Status.NONE
            }
            val seen = input.stations[call]
            CheckIn(
                call = call,
                firstMs = sorted.first().timeMs,
                lastMs = latest.timeMs,
                messages = sorted.size,
                status = status,
                statusText = report?.values?.get("S"),
                lastText = latest.body.trim().replace(Regex("\\s+"), " "),
                grid = seen?.grid ?: report?.type?.locationField?.let { report.values[it.key] },
                snr = seen?.snr,
                answered = input.sent.any { s -> s.timeMs >= sorted.first().timeMs && s.to?.uppercase() == call },
                precedence = sorted.mapNotNull { precedenceOf(it.body) }.minByOrNull { it.ordinal },
                expected = expected.any { base(it) == base(call) },
            )
        }.sortedWith(
            compareBy<CheckIn> { it.precedence?.ordinal ?: Precedence.ROUTINE.ordinal }
                .thenBy { if (it.status == Status.EMERGENCY) 0 else if (it.status == Status.NEED) 1 else 2 }
                .thenBy { it.firstMs },
        )
        val inCalls = checkedIn.map { base(it.call) }.toSet()
        val missing = expected.filter { base(it) !in inCalls }.sorted().map { call ->
            val seen = input.stations[call] ?: input.stations.entries.firstOrNull { base(it.key) == base(call) }?.value
            Missing(call, seen?.lastHeardMs?.takeIf { it >= input.sinceMs })
        }
        return Roster(group, input.sinceMs, input.nowMs, checkedIn, missing)
    }

    private fun base(call: String) = call.trim().uppercase().split('/').maxByOrNull { it.length }.orEmpty()

    /** One message asking everyone missing to check in (one transmission instead of one per station). */
    fun rollCallText(missing: List<Missing>): String =
        missing.joinToString(" ") { it.call } + " PSE CHECK IN"

    fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun stamp(ms: Long): String = SimpleDateFormat("dd MMM HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
