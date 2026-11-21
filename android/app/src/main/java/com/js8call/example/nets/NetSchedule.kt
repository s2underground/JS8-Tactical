package com.js8call.example.nets

import java.util.Calendar
import java.util.TimeZone

/**
 * Net Switcher schedule: named nets with a dial frequency, UTC days and times, and optional
 * speed and heartbeat settings, plus what to do between nets ("home"). Pure Kotlin: which net is
 * on at a given time, when the next change comes, and the storage format. Unit-tested.
 */
object NetSchedule {

    /** Day bits, Monday first (UTC days). */
    const val MON = 1; const val TUE = 2; const val WED = 4; const val THU = 8
    const val FRI = 16; const val SAT = 32; const val SUN = 64
    const val EVERY_DAY = 127
    val DAY_BITS = listOf(MON, TUE, WED, THU, FRI, SAT, SUN)
    val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    data class Net(
        val id: String,
        val name: String,
        val dialHz: Long,
        /** UTC days the net starts on ([EVERY_DAY] etc.). */
        val days: Int = EVERY_DAY,
        /** Minutes after 00:00 UTC. An end at or before the start runs past midnight. */
        val startMin: Int,
        val endMin: Int,
        /** JS8 speed (submode) while on this net; null = leave as it is. */
        val speed: Int? = null,
        /** Heartbeat interval in minutes while on this net (0 = off); null = leave as it is. */
        val heartbeatMin: Int? = null,
        val enabled: Boolean = true,
        val notes: String = "",
    ) {
        /** Length in minutes (24 h when start == end). */
        val lengthMin: Int get() = (((endMin - startMin) % 1440 + 1440) % 1440).let { if (it == 0) 1440 else it }
        fun runsOn(dayBit: Int) = days and dayBit != 0
    }

    /** Between nets. Any field null = leave that setting alone. */
    data class Home(val dialHz: Long? = null, val speed: Int? = null, val heartbeatMin: Int? = null)

    data class Occurrence(val net: Net, val startMs: Long, val endMs: Long)

    /** What the radio should be doing at a moment: a net, or home between nets. */
    data class Target(
        val key: String,
        val net: Net?,
        val dialHz: Long?,
        val speed: Int?,
        val heartbeatMin: Int?,
        /** When this segment began (for a net, its start). */
        val sinceMs: Long,
    )

    private const val DAY_MS = 86_400_000L
    private const val MIN_MS = 60_000L

    private fun utcMidnight(ms: Long): Long = Math.floorDiv(ms, DAY_MS) * DAY_MS

    /** Monday = 0 … Sunday = 6 for the UTC day containing [ms]. */
    fun dayIndex(ms: Long): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    /** Every time [net] is on that overlaps [fromMs, toMs). */
    fun occurrences(net: Net, fromMs: Long, toMs: Long): List<Occurrence> {
        if (!net.enabled || net.days and EVERY_DAY == 0) return emptyList()
        val out = ArrayList<Occurrence>()
        var day = utcMidnight(fromMs) - DAY_MS // a net that started yesterday may still be on
        while (day < toMs) {
            if (net.runsOn(DAY_BITS[dayIndex(day)])) {
                val start = day + net.startMin * MIN_MS
                val end = start + net.lengthMin * MIN_MS
                if (end > fromMs && start < toMs) out += Occurrence(net, start, end)
            }
            day += DAY_MS
        }
        return out
    }

    /** The net on at [ms]: of overlapping nets, the one that started last wins (ties: first in the list). */
    fun activeAt(nets: List<Net>, ms: Long): Occurrence? =
        nets.flatMap { occurrences(it, ms, ms + 1) }
            .filter { ms >= it.startMs && ms < it.endMs }
            .maxWithOrNull(compareBy<Occurrence> { it.startMs }.thenByDescending { nets.indexOf(it.net) })

    fun target(nets: List<Net>, home: Home, ms: Long): Target {
        val on = activeAt(nets, ms)
        if (on != null) return Target("net:${on.net.id}:${on.startMs}", on.net, on.net.dialHz, on.net.speed, on.net.heartbeatMin, on.startMs)
        // Home since the last net ended (or a week back if none did).
        val lastEnd = nets.flatMap { occurrences(it, ms - 8 * DAY_MS, ms) }.map { it.endMs }.filter { it <= ms }.maxOrNull() ?: 0L
        return Target("home:$lastEnd", null, home.dialHz, home.speed, home.heartbeatMin, lastEnd)
    }

    /** The next moment after [ms] when the target changes, and what it changes to; null if nothing within [horizonMs]. */
    fun nextChange(nets: List<Net>, home: Home, ms: Long, horizonMs: Long = 8 * DAY_MS): Pair<Long, Target>? {
        val now = target(nets, home, ms)
        val edges = nets.flatMap { occurrences(it, ms, ms + horizonMs) }
            .flatMap { listOf(it.startMs, it.endMs) }
            .filter { it > ms }
            .distinct().sorted()
        for (t in edges) {
            val next = target(nets, home, t)
            if (next.key != now.key) return t to next
        }
        return null
    }

    /** Upcoming changes after [ms], in order, up to [max]. */
    fun upcoming(nets: List<Net>, home: Home, ms: Long, max: Int = 12, horizonMs: Long = 8 * DAY_MS): List<Pair<Long, Target>> {
        val out = ArrayList<Pair<Long, Target>>()
        var t = ms
        while (out.size < max) {
            val n = nextChange(nets, home, t, horizonMs - (t - ms)) ?: break
            out += n
            t = n.first
        }
        return out
    }

    /** Pairs of nets whose times overlap somewhere in the coming week. */
    fun overlaps(nets: List<Net>, fromMs: Long): List<Pair<Net, Net>> {
        val occ = nets.flatMap { occurrences(it, fromMs, fromMs + 7 * DAY_MS) }
        val out = LinkedHashSet<Pair<Net, Net>>()
        for (i in occ.indices) for (j in i + 1 until occ.size) {
            val a = occ[i]; val b = occ[j]
            if (a.net.id != b.net.id && a.startMs < b.endMs && b.startMs < a.endMs) {
                out += if (nets.indexOf(a.net) <= nets.indexOf(b.net)) a.net to b.net else b.net to a.net
            }
        }
        return out.toList()
    }

    // ------------------------------------------------------------------ text

    fun hhmm(min: Int): String = "%02d%02d".format(min / 60 % 24, min % 60)

    fun daysText(days: Int): String = when (days and EVERY_DAY) {
        EVERY_DAY -> "Every day"
        MON or TUE or WED or THU or FRI -> "Weekdays"
        SAT or SUN -> "Weekends"
        0 -> "No days"
        else -> DAY_BITS.indices.filter { days and DAY_BITS[it] != 0 }.joinToString(" ") { DAY_NAMES[it] }
    }

    fun mhz(hz: Long): String {
        val s = "%.3f".format(java.util.Locale.US, hz / 1_000_000.0)
        val exact = "%.6f".format(java.util.Locale.US, hz / 1_000_000.0).trimEnd('0').trimEnd('.')
        return if (exact.length > s.length) exact else s
    }

    // ------------------------------------------------------------------ storage

    private const val VERSION = "n1"

    fun encode(nets: List<Net>, home: Home): String = buildString {
        appendLine(listOf("home", home.dialHz ?: "", home.speed ?: "", home.heartbeatMin ?: "").joinToString("\t"))
        nets.forEach { n ->
            appendLine(listOf(VERSION, n.id, esc(n.name), n.dialHz, n.days, n.startMin, n.endMin,
                n.speed ?: "", n.heartbeatMin ?: "", if (n.enabled) 1 else 0, esc(n.notes)).joinToString("\t"))
        }
    }

    fun decode(text: String?): Pair<List<Net>, Home> {
        var home = Home()
        val nets = ArrayList<Net>()
        text.orEmpty().lineSequence().forEach { line ->
            val f = line.split('\t')
            when {
                f[0] == "home" -> home = Home(f.getOrNull(1)?.toLongOrNull(), f.getOrNull(2)?.toIntOrNull(), f.getOrNull(3)?.toIntOrNull())
                f[0] == VERSION && f.size >= 10 -> runCatching {
                    nets += Net(
                        id = f[1], name = unesc(f[2]), dialHz = f[3].toLong(), days = f[4].toInt(),
                        startMin = f[5].toInt(), endMin = f[6].toInt(),
                        speed = f[7].toIntOrNull(), heartbeatMin = f[8].toIntOrNull(),
                        enabled = f[9] != "0", notes = unesc(f.getOrNull(10).orEmpty()),
                    )
                }
            }
        }
        return nets to home
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> out.append('\t'); 'n' -> out.append('\n'); else -> out.append(s[i + 1]) }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
