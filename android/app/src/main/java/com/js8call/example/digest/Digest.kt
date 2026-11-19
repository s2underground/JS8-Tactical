package com.js8call.example.digest

import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.example.reports.Reports
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The daily digest: what happened on the net, worked out on the device from
 * what this station heard. No network, no AI. The structured result
 * ([Digest]) is also the input a future optional summariser would read.
 *
 * Pure Kotlin, so it is unit-tested.
 */
object DigestBuilder {

    /** Replies that close an exchange: answers to GRID?, SNR?, INFO?, STATUS?, HEARING?, and ACK/73. Questions don't match. */
    private val answerOnly = Regex("""^(?:HEARTBEAT\s+SNR|SNR|GRID|HEARING|INFO|STATUS|ACK|NACK|RR|QSL|73|SK|FB)\b(?!\?)""")


    data class Msg(
        val id: Long,
        val sender: String?,
        val recipient: String?,
        val body: String,
        val timeMs: Long,
        val complete: Boolean,
        val missingFrames: Int,
        val snr: Int?,
    )

    data class Sent(val to: String?, val text: String, val timeMs: Long)

    data class Station(val call: String, val grid: String?, val firstHeardMs: Long, val lastHeardMs: Long, val snr: Int)

    data class Input(
        val nowMs: Long,
        val sinceMs: Long,
        val myCall: String,
        val myGrid: String?,
        val myGroups: List<String>,
        val messages: List<Msg>,
        val sent: List<Sent>,
        val stations: List<Station>,
        val listeningHours: Double?,
        /** e.g. "SFI 142, K 2 (NOAA, 3 h old)"; null if unknown. */
        val solar: String?,
        /** When the app started collecting, if later than [sinceMs]. */
        val dataStartMs: Long?,
    )

    enum class Tone { RED, AMBER, GREEN, NEUTRAL }

    /** One line of the digest. [call] makes it tappable (opens that station's traffic). */
    data class Line(val text: String, val detail: String = "", val tone: Tone = Tone.NEUTRAL, val call: String? = null)

    data class Section(val title: String, val lines: List<Line>, val tone: Tone = Tone.NEUTRAL, val empty: String = "Nothing")

    data class Digest(val headline: String, val tone: Tone, val sections: List<Section>, val windowText: String) {
        /** Plain text for copy/share (and for a future summariser). */
        fun toText(): String = buildString {
            appendLine("JS8 DIGEST: $windowText")
            appendLine(headline)
            sections.forEach { s ->
                appendLine()
                appendLine(s.title.uppercase())
                if (s.lines.isEmpty()) appendLine("  ${s.empty}")
                s.lines.forEach { l -> appendLine("  - ${l.text}${if (l.detail.isNotEmpty()) " (${l.detail})" else ""}") }
            }
        }.trimEnd()
    }

    private val emergencyWords = Regex("""\b(MAYDAY|SOS|EMERGENCY|EMERG|INJURED|CASUALTY|MEDEVAC)\b""")

    fun build(input: Input): Digest {
        val inWindow = input.messages.filter { it.timeMs >= input.sinceMs && it.timeMs <= input.nowMs + 60_000 }
        val me = input.myCall.trim().uppercase()
        val mine = (listOf(me) + input.myGroups.map { it.uppercase() }).filter { it.isNotEmpty() }.toSet()

        // ---- Needs and emergencies: each sender's latest status report decides.
        data class StatusReport(val msg: Msg, val report: Reports.Report, val level: Tone)
        val statusReports = inWindow.mapNotNull { m ->
            val r = Reports.parse(m.body) ?: return@mapNotNull null
            if (r.type != Reports.CHECKIN && r.type != Reports.SITREP) return@mapNotNull null
            val s = r.values["S"]?.uppercase().orEmpty()
            val level = when {
                s.startsWith("EMERG") || s.startsWith("RED") -> Tone.RED
                s.startsWith("NEED") || s.startsWith("AMBER") -> Tone.AMBER
                s.startsWith("GOOD") || s.startsWith("GREEN") -> Tone.GREEN
                else -> Tone.NEUTRAL
            }
            StatusReport(m, r, level)
        }
        val latestBySender = statusReports.filter { it.msg.sender != null }
            .groupBy { it.msg.sender!! }.mapValues { (_, v) -> v.maxByOrNull { it.msg.timeMs }!! }
        val open = latestBySender.values.filter { it.level == Tone.RED || it.level == Tone.AMBER }
            .sortedWith(compareBy<StatusReport> { if (it.level == Tone.RED) 0 else 1 }.thenByDescending { it.msg.timeMs })
        val resolved = latestBySender.filter { (call, latest) ->
            latest.level == Tone.GREEN && statusReports.any { it.msg.sender == call && (it.level == Tone.RED || it.level == Tone.AMBER) }
        }.keys
        val alertLines = open.map { sr ->
            val status = sr.report.values["S"].orEmpty()
            val what = listOfNotNull(sr.report.values["R"], sr.report.values["N"]).joinToString("; ").ifEmpty { "no details" }
            val where = sr.report.type.locationField?.let { sr.report.values[it.key] }
            Line("${sr.msg.sender} $status: $what", listOfNotNull(where, ago(sr.msg.timeMs, input.nowMs)).joinToString(" · "), sr.level, sr.msg.sender)
        }.toMutableList()
        // Free-text distress words from stations without an open status report.
        inWindow.filter { m ->
            Reports.parse(m.body) == null && emergencyWords.containsMatchIn(m.body.uppercase()) && m.sender !in latestBySender.keys
        }.groupBy { it.sender }.forEach { (sender, msgs) ->
            val m = msgs.maxByOrNull { it.timeMs }!!
            alertLines += Line("${sender ?: "Unknown"}: ${m.body.trim().take(80)}", "keyword · ${ago(m.timeMs, input.nowMs)}", Tone.AMBER, sender)
        }
        if (resolved.isNotEmpty()) alertLines += Line("Now reporting OK: ${resolved.sorted().joinToString(", ")}", tone = Tone.GREEN)

        // ---- For you: addressed to me or my groups; unanswered = nothing sent back to them since.
        val toMe = inWindow.filter {
            it.recipient?.uppercase() in mine && it.sender != null && it.sender.uppercase() != me &&
                // Answers to our own queries (GRID EN90, SNR -12, ACK…) need no reply.
                !answerOnly.containsMatchIn(it.body.trim().uppercase())
        }
        val unanswered = toMe.filter { m -> input.sent.none { s -> s.timeMs > m.timeMs && s.to?.uppercase() in setOf(m.sender!!.uppercase(), m.recipient?.uppercase()) } }
        val forYou = unanswered.sortedByDescending { it.timeMs }.take(8).map { m ->
            val partial = if (!m.complete) " [incomplete]" else ""
            Line("${m.sender} → ${m.recipient}: ${m.body.trim().take(70)}$partial", "unanswered · ${ago(m.timeMs, input.nowMs)}",
                if (m.recipient?.uppercase() == me) Tone.AMBER else Tone.NEUTRAL, m.sender)
        }.toMutableList()
        val incompleteToMe = toMe.count { !it.complete }
        if (incompleteToMe > 0) forYou += Line(
            "$incompleteToMe message${s(incompleteToMe)} to you arrived with gaps",
            "ask the sender for a repeat (AGN?)", Tone.AMBER,
        )

        // ---- Reports.
        val reports = inWindow.mapNotNull { m -> Reports.parse(m.body)?.let { m to it } }
        val reportLines = mutableListOf<Line>()
        if (reports.isNotEmpty()) {
            val counts = reports.groupBy { it.second.type.tag }.map { (t, l) -> "${l.size} $t" }.sorted()
            reportLines += Line(counts.joinToString(", "))
        }
        reports.filter { it.second.type == Reports.SPOT || it.second.type == Reports.SALUTE }
            .sortedByDescending { it.first.timeMs }.take(5).forEach { (m, r) ->
                val loc = r.type.locationField?.let { r.values[it.key] }
                Line("${r.type.tag} from ${m.sender ?: "unknown"}: ${r.summary.take(70)}",
                    listOfNotNull(loc, ago(m.timeMs, input.nowMs)).joinToString(" · "), Tone.NEUTRAL, m.sender).let(reportLines::add)
            }

        // ---- Stations.
        val heardInWindow = input.stations.filter { it.lastHeardMs >= input.sinceMs }
        val newStations = heardInWindow.filter { it.firstHeardMs >= input.sinceMs }
        val myPos: LatLon? = input.myGrid?.let { Geo.maidenheadToLatLon(it.trim()) }
        val stationLines = mutableListOf<Line>()
        if (heardInWindow.isNotEmpty()) {
            stationLines += Line("${heardInWindow.size} station${s(heardInWindow.size)} heard, ${newStations.size} new")
            val byCount = inWindow.mapNotNull { it.sender }.groupingBy { it }.eachCount()
            heardInWindow.sortedWith(compareByDescending<Station> { byCount[it.call] ?: 0 }.thenByDescending { it.lastHeardMs })
                .take(5).forEach { st ->
                    val n = byCount[st.call] ?: 0
                    val dist = if (myPos != null && st.grid != null) Geo.maidenheadToLatLon(st.grid)?.let { " · ${Geo.distanceKm(myPos, it).toInt()} km" }.orEmpty() else ""
                    stationLines += Line(st.call, "$n msg${s(n)} · ${st.snr} dB$dist · last ${ago(st.lastHeardMs, input.nowMs)}", call = st.call)
                }
            if (myPos != null) {
                heardInWindow.mapNotNull { st -> st.grid?.let { Geo.maidenheadToLatLon(it) }?.let { st to Geo.distanceKm(myPos, it) } }
                    .maxByOrNull { it.second }?.let { (st, km) -> stationLines += Line("Farthest: ${st.call}", "${km.toInt()} km, grid ${st.grid}", call = st.call) }
            }
        }

        // ---- Your station.
        val sentInWindow = input.sent.filter { it.timeMs >= input.sinceMs }
        val stationStatus = mutableListOf<Line>()
        stationStatus += Line("${inWindow.size} message${s(inWindow.size)} heard, ${sentInWindow.size} sent")
        input.listeningHours?.let { stationStatus += Line("Listened about ${"%.1f".format(Locale.US, it)} h") }
        input.solar?.let { stationStatus += Line("Solar: $it") }

        val tone = when {
            open.any { it.level == Tone.RED } -> Tone.RED
            open.isNotEmpty() || alertLines.any { it.tone == Tone.AMBER } -> Tone.AMBER
            else -> Tone.GREEN
        }
        val headline = when {
            open.isNotEmpty() -> "${open.size} open need${s(open.size)} or emergenc${if (open.size == 1) "y" else "ies"}" +
                if (unanswered.isNotEmpty()) "; ${unanswered.size} unanswered for you" else ""
            alertLines.any { it.tone == Tone.AMBER } -> "Possible distress traffic heard; check below"
            unanswered.isNotEmpty() -> "No open needs; ${unanswered.size} unanswered for you"
            inWindow.isEmpty() -> "Nothing heard in this window"
            else -> "No open needs or emergencies"
        }
        val window = buildString {
            append("${hhmm(input.sinceMs)}–${hhmm(input.nowMs)} UTC")
            input.dataStartMs?.takeIf { it > input.sinceMs }?.let { append(" (data since ${hhmm(it)}, when the app started)") }
        }
        return Digest(
            headline = headline,
            tone = tone,
            windowText = window,
            sections = listOf(
                Section("Needs and emergencies", alertLines, if (open.any { it.level == Tone.RED }) Tone.RED else if (alertLines.any { it.tone == Tone.AMBER }) Tone.AMBER else Tone.NEUTRAL, "None reported"),
                Section("For you", forYou, if (forYou.isNotEmpty()) Tone.AMBER else Tone.NEUTRAL, "Nothing waiting for a reply"),
                Section("Reports", reportLines, empty = "No reports"),
                Section("Stations", stationLines, empty = "No stations heard"),
                Section("Your station", stationStatus),
            ),
        )
    }

    private fun s(n: Int) = if (n == 1) "" else "s"

    fun ago(t: Long, now: Long): String {
        val min = ((now - t) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "${min} min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} d ago"
        }
    }

    private fun hhmm(ms: Long): String = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
