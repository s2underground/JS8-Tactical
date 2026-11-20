package com.js8call.example.ui.map

import java.io.File

/**
 * What the Map remembers between app starts: the grid each station last gave,
 * and which pairs of other stations were heard working each other.
 *
 * Only 4- or 6-character grids that stations sent over the air are kept, never
 * anything more exact. Entries older than [KEEP_MS] are dropped.
 *
 * Not thread-safe: use from one thread (the main thread), and write the text
 * from [serialize] to disk elsewhere.
 */
class MapMemory {

    /** [bands]: band labels ("40m") the station was heard on. */
    data class Station(val call: String, val grid: String, val lastHeardMs: Long, val snr: Int, val bands: Set<String> = emptySet())

    /**
     * Two stations heard working each other. [a] sorts before [b].
     * [snr] is the most recent signal report one of them gave the other, sent by [reporter].
     */
    data class Link(
        val a: String,
        val b: String,
        val lastHeardMs: Long,
        val snr: Int?,
        val reporter: String?,
        val snrMs: Long,
        /** 1: a called b. 2: b called a. 4: one listed the other in a HEARING report. */
        val seen: Int,
        /** Band labels this pair was heard working on. */
        val bands: Set<String> = emptySet(),
    ) {
        /** True when there is evidence a signal got through (not just one station calling another). */
        val confirmed: Boolean get() = snr != null || seen and 3 == 3 || seen and 4 != 0
        fun other(call: String) = if (call == a) b else a
    }

    private val stations = HashMap<String, Station>()
    private val links = HashMap<String, Link>()

    /** Something changed since the last [serialize]. */
    var dirty = false
        private set

    fun stations(): Collection<Station> = stations.values
    fun links(): Collection<Link> = links.values
    fun grid(call: String): String? = stations[call]?.grid

    fun noteStation(call: String, grid: String, ms: Long, snr: Int, band: String? = null) {
        val old = stations[call]
        val g = grid.uppercase()
        val bands = (old?.bands ?: emptySet()) + listOfNotNull(band)
        // Keep the more precise of two grids in the same square.
        val keptGrid = if (old != null && old.grid.length > g.length && old.grid.startsWith(g)) old.grid else g
        if (old != null && ms < old.lastHeardMs) {
            // An older message can still sharpen the grid (EM73 -> EM73TU), never move it.
            val sharper = if (g.length > old.grid.length && g.startsWith(old.grid)) g else old.grid
            val next = old.copy(grid = sharper, bands = bands)
            if (next != old) { stations[call] = next; dirty = true }
            return
        }
        val next = Station(call, keptGrid, ms, snr, bands)
        if (next != old) { stations[call] = next; dirty = true }
    }

    /**
     * [from] sent a directed message to [to]. [snr] is a signal report in it
     * ("SNR -12": [from] heard [to] at -12 dB). [hearing]: [from] listed [to] as heard.
     */
    fun noteLink(from: String, to: String, ms: Long, snr: Int? = null, hearing: Boolean = false, band: String? = null) {
        if (from == to) return
        val a = minOf(from, to)
        val b = maxOf(from, to)
        val key = "$a $b"
        val old = links[key]
        val seen = (old?.seen ?: 0) or (if (hearing) 4 else if (from == a) 1 else 2)
        var next = Link(
            a, b,
            lastHeardMs = maxOf(old?.lastHeardMs ?: 0L, ms),
            snr = old?.snr, reporter = old?.reporter, snrMs = old?.snrMs ?: 0L,
            seen = seen,
            bands = (old?.bands ?: emptySet()) + listOfNotNull(band),
        )
        if (snr != null && ms >= next.snrMs) next = next.copy(snr = snr, reporter = from, snrMs = ms)
        if (next != old) { links[key] = next; dirty = true }
    }

    fun prune(cutoffMs: Long) {
        if (stations.values.removeIf { it.lastHeardMs < cutoffMs }) dirty = true
        if (links.values.removeIf { it.lastHeardMs < cutoffMs }) dirty = true
        trim()
    }

    fun clear() {
        if (stations.isNotEmpty() || links.isNotEmpty()) dirty = true
        stations.clear()
        links.clear()
    }

    /** Add entries read from disk; anything already known and newer wins. */
    fun merge(other: MapMemory) {
        other.stations.values.forEach { st ->
            noteStation(st.call, st.grid, st.lastHeardMs, st.snr)
            stations[st.call]?.let { mine -> if (!mine.bands.containsAll(st.bands)) stations[st.call] = mine.copy(bands = mine.bands + st.bands) }
        }
        other.links.values.forEach { l ->
            val old = links["${l.a} ${l.b}"]
            links["${l.a} ${l.b}"] = when {
                old == null -> l
                else -> {
                    val newerSnr = if (l.snr != null && l.snrMs > old.snrMs) l else old
                    old.copy(
                        lastHeardMs = maxOf(old.lastHeardMs, l.lastHeardMs),
                        seen = old.seen or l.seen,
                        bands = old.bands + l.bands,
                        snr = newerSnr.snr, reporter = newerSnr.reporter, snrMs = newerSnr.snrMs,
                    )
                }
            }
        }
        trim()
    }

    private fun trim() {
        if (stations.size > MAX_STATIONS) {
            stations.values.sortedBy { it.lastHeardMs }.take(stations.size - MAX_STATIONS).forEach { stations.remove(it.call) }
            dirty = true
        }
        if (links.size > MAX_LINKS) {
            links.values.sortedBy { it.lastHeardMs }.take(links.size - MAX_LINKS).forEach { links.remove("${it.a} ${it.b}") }
            dirty = true
        }
    }

    /** Text for the memory file; entries naming a call in [exclude] (demo stations) are left out. */
    fun serialize(exclude: Set<String> = emptySet()): String {
        dirty = false
        val sb = StringBuilder("# JS8-Tactical map memory v1\n")
        stations.values.sortedBy { it.call }.filter { it.call !in exclude }.forEach { s ->
            sb.append("S\t").append(s.call).append('\t').append(s.grid).append('\t')
                .append(s.lastHeardMs).append('\t').append(s.snr).append('\t').append(s.bands.sorted().joinToString(",")).append('\n')
        }
        links.values.sortedWith(compareBy({ it.a }, { it.b })).filter { it.a !in exclude && it.b !in exclude }.forEach { l ->
            sb.append("L\t").append(l.a).append('\t').append(l.b).append('\t').append(l.lastHeardMs).append('\t')
                .append(l.snr?.toString() ?: "-").append('\t').append(l.reporter ?: "-").append('\t')
                .append(l.snrMs).append('\t').append(l.seen).append('\t').append(l.bands.sorted().joinToString(",")).append('\n')
        }
        return sb.toString()
    }

    companion object {
        const val KEEP_MS = 7 * 24 * 3_600_000L
        const val MAX_STATIONS = 600
        const val MAX_LINKS = 400
        private val gridOk = Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$")

        private fun bandsOf(field: String?): Set<String> =
            field?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        /** Read a memory file's text; bad lines are skipped (files from before bands were kept load fine). */
        fun parse(text: String): MapMemory {
            val m = MapMemory()
            text.lineSequence().forEach { line ->
                val p = line.split('\t')
                runCatching {
                    when (p.firstOrNull()) {
                        "S" -> if (p.size >= 5 && gridOk.matches(p[2])) {
                            m.stations[p[1]] = Station(p[1], p[2], p[3].toLong(), p[4].toInt(), bandsOf(p.getOrNull(5)))
                        }
                        "L" -> if (p.size >= 8 && p[1] < p[2]) {
                            m.links["${p[1]} ${p[2]}"] = Link(
                                p[1], p[2], p[3].toLong(),
                                snr = p[4].takeIf { it != "-" }?.toInt(),
                                reporter = p[5].takeIf { it != "-" },
                                snrMs = p[6].toLong(),
                                seen = p[7].toInt(),
                                bands = bandsOf(p.getOrNull(8)),
                            )
                        }
                    }
                }
            }
            return m
        }

        fun load(file: File): MapMemory? =
            runCatching { if (file.exists()) parse(file.readText()) else null }.getOrNull()

        /** Write [text] to [file] all at once (a crash mid-write leaves the old file). */
        fun save(file: File, text: String) {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }
}
