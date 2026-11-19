package com.js8call.example.team

import com.js8call.example.prop.Band
import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.example.reports.Reports

/** Someone on the user's team. Only [call] is required. */
data class TeamMember(
    val call: String,
    val name: String = "",
    val section: String = "",
    /** Grid square typed in by the user; used when we have not heard their grid. */
    val grid: String = "",
) {
    /** Callsign without portable/mobile suffixes, for matching what we hear. */
    val baseCall: String get() = baseOf(call)

    companion object {
        fun baseOf(call: String) = call.trim().uppercase().substringBefore('/')
    }
}

/** The roster, stored as one line per member in a preference (no network, no account). */
object TeamRoster {
    const val KEY = "team_roster"

    fun encode(members: List<TeamMember>): String = members.joinToString("\n") { m ->
        listOf(m.call, m.name, m.section, m.grid).joinToString("|") { it.replace("|", "/").replace("\n", " ").trim() }
    }

    fun decode(text: String?): List<TeamMember> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        val call = p.getOrNull(0)?.trim()?.uppercase().orEmpty()
        if (call.isEmpty()) null
        else TeamMember(call, p.getOrNull(1)?.trim().orEmpty(), p.getOrNull(2)?.trim().orEmpty(), p.getOrNull(3)?.trim()?.uppercase().orEmpty())
    }.distinctBy { it.baseCall }

    /** Add or replace (matched by base callsign, or by [replacing]'s call when editing). */
    fun upsert(members: List<TeamMember>, m: TeamMember, replacing: String? = null): List<TeamMember> {
        val key = TeamMember.baseOf(replacing ?: m.call)
        val i = members.indexOfFirst { it.baseCall == key || it.baseCall == m.baseCall }
        return if (i < 0) members + m else members.toMutableList().also { it[i] = m }.distinctBy { it.baseCall }
    }

    fun remove(members: List<TeamMember>, call: String) = members.filterNot { it.baseCall == TeamMember.baseOf(call) }
}

/**
 * The Team board: one row per member with when they were last heard, their
 * latest status report, and how likely we are to reach them now on each band
 * (the Prop tab's model, from their last known grid). Pure Kotlin; tested.
 */
object TeamBoard {

    enum class Light { GO, CAUTION, POOR, NONE }

    enum class Tone { RED, AMBER, GREEN, NEUTRAL }

    data class Heard(val lastHeardMs: Long, val snr: Int, val grid: String?)

    data class Status(val label: String, val detail: String, val tone: Tone, val timeMs: Long)

    data class Msg(val sender: String?, val body: String, val timeMs: Long)

    data class Row(
        val member: TeamMember,
        val heard: Heard?,
        val status: Status?,
        /** Where reachability is computed from, and why. */
        val position: LatLon?,
        val positionNote: String,
        /** Score per band (0..1, chart thresholds), null when no position or no own position. */
        val reach: Map<Band, Double>,
        val bestBand: Band?,
    )

    /** Bands shown on the board, low to high. */
    val BANDS = listOf(Band.B80, Band.B40, Band.B30, Band.B20, Band.B17, Band.B15, Band.B10)

    fun light(score: Double?): Light = when {
        score == null -> Light.NONE
        score >= 0.66 -> Light.GO
        score >= 0.33 -> Light.CAUTION
        score >= 0.10 -> Light.POOR
        else -> Light.NONE
    }

    /** Each sender's latest CHECKIN or SITREP. */
    fun latestStatuses(messages: List<Msg>): Map<String, Status> {
        val out = HashMap<String, Status>()
        messages.sortedBy { it.timeMs }.forEach { m ->
            val sender = m.sender?.let(TeamMember::baseOf) ?: return@forEach
            val r = Reports.parse(m.body) ?: return@forEach
            if (r.type != Reports.CHECKIN && r.type != Reports.SITREP) return@forEach
            val s = r.values["S"]?.trim()?.uppercase().orEmpty()
            val tone = when {
                s.startsWith("EMERG") || s.startsWith("RED") -> Tone.RED
                s.startsWith("NEED") || s.startsWith("AMBER") -> Tone.AMBER
                s.startsWith("GOOD") || s.startsWith("GREEN") -> Tone.GREEN
                else -> Tone.NEUTRAL
            }
            val detail = listOfNotNull(r.values["R"], r.values["N"]).joinToString("; ")
            out[sender] = Status("${r.type.tag} ${s.ifEmpty { "-" }}", detail, tone, m.timeMs)
        }
        return out
    }

    /**
     * @param heard last heard info by base callsign
     * @param scorer chance to reach a position on a band now (null when our own position is unknown)
     */
    fun build(
        members: List<TeamMember>,
        heard: Map<String, Heard>,
        statuses: Map<String, Status>,
        scorer: ((LatLon, Band) -> Double)?,
    ): List<Row> = members.map { m ->
        val h = heard[m.baseCall]
        val heardPos = h?.grid?.let { Geo.maidenheadToLatLon(it) }
        val typedPos = m.grid.takeIf { it.isNotBlank() }?.let { Geo.parseLocation(it) }
        val (pos, note) = when {
            heardPos != null -> heardPos to "from heard grid ${h.grid}"
            typedPos != null -> typedPos to "from entered grid ${m.grid}"
            else -> null to "no grid known"
        }
        val reach = if (pos != null && scorer != null) BANDS.associateWith { b -> scorer(pos, b) } else emptyMap()
        val best = reach.maxByOrNull { it.value }?.takeIf { it.value >= 0.10 }?.key
        Row(m, h, statuses[m.baseCall], pos, note, reach, best)
    }

    /** Short "heard" line, e.g. "Heard 12 min ago · −14 dB". */
    fun heardText(h: Heard?, nowMs: Long): String {
        if (h == null) return "Not heard yet"
        return "Heard ${ago(h.lastHeardMs, nowMs)} · ${if (h.snr >= 0) "+" else ""}${h.snr} dB"
    }

    fun ago(t: Long, now: Long): String {
        val min = ((now - t) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} d ago"
        }
    }

    /** Plain-text roster with status, for Share. */
    fun toText(rows: List<Row>, nowMs: Long): String = buildString {
        appendLine("TEAM")
        rows.groupBy { it.member.section.ifBlank { "Team" } }.forEach { (section, list) ->
            appendLine()
            appendLine(section.uppercase())
            list.forEach { r ->
                append("  ").append(r.member.call)
                if (r.member.name.isNotBlank()) append(" (").append(r.member.name).append(")")
                append(": ").append(heardText(r.heard, nowMs))
                r.status?.let { append("; ").append(it.label); if (it.detail.isNotBlank()) append(" ").append(it.detail) }
                r.bestBand?.let { append("; best band ").append(it.label) }
                appendLine()
            }
        }
    }.trimEnd()
}
