package com.js8call.example.reports

/**
 * Saved reports for the Reports tab: free text or a report form, with an optional
 * automatic send time. Pure Kotlin (storage format and scheduling), unit-tested.
 */
object ReportDrafts {

    const val FREE_TEXT = "TEXT"

    /** A send more than this late (receiver was off) is skipped and marked missed. */
    const val GRACE_MS = 30 * 60_000L

    /** [repeatMin] 0 = once. */
    data class Schedule(val atMs: Long, val repeatMin: Int = 0)

    data class Draft(
        val id: String,
        val title: String = "",
        /** [FREE_TEXT] or a report tag (SITREP, SALUTE…). */
        val kind: String = FREE_TEXT,
        /** Callsign or @GROUP; blank = everyone. */
        val to: String = "",
        val body: String = "",
        val values: Map<String, String> = emptyMap(),
        val createdMs: Long = 0,
        val updatedMs: Long = 0,
        val schedule: Schedule? = null,
        val lastSentMs: Long? = null,
        /** What happened last: "queued", "auto", "missed". */
        val lastResult: String? = null,
        /** JS8 speed (submode) to send at; null = whatever speed is set on Traffic at the time. */
        val speed: Int? = null,
    ) {
        val type: Reports.Type? get() = if (kind == FREE_TEXT) null else Reports.byTag(kind)

        /** The text that goes on air (JS8 has one line, upper case). */
        val onAir: String get() = type?.let { Reports.encode(it, values) } ?: airText(body)

        val isEmpty: Boolean get() = if (type == null) body.isBlank() else values.values.all { it.isBlank() }

        fun displayTitle(): String = title.ifBlank {
            val t = type
            if (t != null) t.title else body.trim().lineSequence().firstOrNull()?.take(40)?.ifBlank { null } ?: "Untitled"
        }
    }

    /** Free text as sent: line breaks and runs of spaces become one space; upper case; printable ASCII only. */
    fun airText(text: String): String =
        text.replace(Regex("[^ -~\\s]"), "").replace(Regex("\\s+"), " ").trim().uppercase()

    // ---------------------------------------------------------------- scheduling

    data class Due(val send: List<Draft>, val missed: List<Draft>, val updated: List<Draft>)

    /**
     * Which scheduled drafts fire at [now]. Sent and missed drafts get their next time
     * (or lose their schedule when it was once). [updated] is the whole list after that.
     */
    fun due(drafts: List<Draft>, now: Long, graceMs: Long = GRACE_MS): Due {
        val send = ArrayList<Draft>()
        val missed = ArrayList<Draft>()
        val updated = drafts.map { d ->
            val s = d.schedule ?: return@map d
            if (s.atMs > now) return@map d
            val late = now - s.atMs > graceMs
            val next = nextSchedule(s, now)
            val after = d.copy(schedule = next, lastSentMs = if (late) d.lastSentMs else now, lastResult = if (late) "missed" else "auto")
            if (late || d.isEmpty) missed += d else send += d
            after
        }
        return Due(send, missed, updated)
    }

    /** The schedule after a firing at or after [s.atMs]: null for a one-off, else the next slot after [now]. */
    fun nextSchedule(s: Schedule, now: Long): Schedule? {
        if (s.repeatMin <= 0) return null
        val step = s.repeatMin * 60_000L
        val skipped = (now - s.atMs) / step + 1
        return s.copy(atMs = s.atMs + skipped * step)
    }

    // ---------------------------------------------------------------- storage

    private const val VERSION = "v1"

    fun encode(drafts: List<Draft>): String = drafts.joinToString("\n") { d ->
        listOf(
            VERSION, d.id, d.title, d.kind, d.to, d.body,
            d.values.entries.joinToString(VALUE_SEP) { (k, v) -> strip(k) + KV_SEP + strip(v) },
            d.createdMs.toString(), d.updatedMs.toString(),
            d.schedule?.atMs?.toString().orEmpty(), d.schedule?.repeatMin?.toString().orEmpty(),
            d.lastSentMs?.toString().orEmpty(), d.lastResult.orEmpty(),
            d.speed?.toString().orEmpty(),
        ).joinToString("\t") { esc(it) }
    }

    fun decode(text: String?): List<Draft> = text.orEmpty().lineSequence().mapNotNull { line ->
        val f = line.split('\t').map { unesc(it) }
        if (f.size < 13 || f[0] != VERSION || f[1].isBlank()) return@mapNotNull null
        val values = if (f[6].isEmpty()) emptyMap() else f[6].split(VALUE_SEP).mapNotNull {
            val kv = it.split(KV_SEP, limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap(LinkedHashMap())
        Draft(
            id = f[1], title = f[2], kind = f[3].ifBlank { FREE_TEXT }, to = f[4], body = f[5], values = values,
            createdMs = f[7].toLongOrNull() ?: 0, updatedMs = f[8].toLongOrNull() ?: 0,
            schedule = f[9].toLongOrNull()?.let { Schedule(it, f[10].toIntOrNull() ?: 0) },
            lastSentMs = f[11].toLongOrNull(), lastResult = f[12].ifBlank { null },
            speed = f.getOrNull(13)?.toIntOrNull(),
        )
    }.toList()

    private const val VALUE_SEP = "\u001E"
    private const val KV_SEP = "\u001F"
    private fun strip(s: String) = s.replace(VALUE_SEP, "").replace(KV_SEP, "")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

    private fun unesc(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
