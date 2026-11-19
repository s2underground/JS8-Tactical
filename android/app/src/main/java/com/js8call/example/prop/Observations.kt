package com.js8call.example.prop

import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * One piece of propagation evidence: a station we decoded on a band, or a
 * station that told us how well it heard us (an SNR report). These are what
 * the Propagation tab draws when there is no internet, and what the model is
 * checked against.
 */
data class Observation(
    val timeMs: Long,
    val call: String,
    val grid: String?,
    val band: Band,
    val snr: Int,
    val kind: Kind,
    /** For [Kind.PATH]: the station that heard [call]. */
    val by: String? = null,
) {
    /**
     * HEARD: this station decoded [call]. HEARD_ME: [call] reported hearing
     * this station. PATH: [by] reported hearing [call] (someone else's path,
     * from their signal reports or HEARING replies).
     */
    enum class Kind { HEARD, HEARD_ME, PATH }

    fun encode(): String = listOf(timeMs, call, grid.orEmpty(), band.name, snr, kind.name, by.orEmpty()).joinToString(",")

    companion object {
        fun decode(line: String): Observation? {
            val p = line.split(',')
            if (p.size != 6 && p.size != 7) return null
            return runCatching {
                Observation(
                    p[0].toLong(), p[1], p[2].ifBlank { null }, Band.valueOf(p[3]), p[4].toInt(), Kind.valueOf(p[5]),
                    p.getOrNull(6)?.ifBlank { null },
                )
            }.getOrNull()
        }
    }
}

/** A plotted observation: where it was, and how much it counts for the selected time. */
data class WeightedObservation(val obs: Observation, val position: LatLon, val weight: Double)

/** Someone else's reported path: [from] heard [to]. */
data class NetPath(val from: LatLon, val to: LatLon, val obs: Observation, val weight: Double)

/**
 * Keeps a month of observations in a small text file so the offline heatmap
 * survives restarts. Writes are append-only; the file is compacted on load.
 */
class ObservationStore(private val file: File, private val keepMs: Long = 30 * DAY_MS) {

    private val items = mutableListOf<Observation>()
    private var loaded = false

    @Synchronized
    fun all(): List<Observation> {
        load()
        return items.toList()
    }

    @Synchronized
    fun add(o: Observation, persist: Boolean = true): Boolean {
        load()
        // One entry per station, band, kind and 15-minute window is plenty.
        // Newest last, so scan backwards: a duplicate is almost always recent.
        val dup = items.asReversed().any {
            it.call == o.call && it.by == o.by && it.band == o.band && it.kind == o.kind && abs(it.timeMs - o.timeMs) < 15 * 60_000L
        }
        if (dup) return false
        items += o
        if (persist) runCatching { file.appendText(o.encode() + "\n") }
        return true
    }

    @Synchronized
    fun clear() {
        items.clear()
        runCatching { file.delete() }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val cutoff = System.currentTimeMillis() - keepMs
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val kept = lines.mapNotNull { Observation.decode(it) }.filter { it.timeMs >= cutoff }
        items += kept
        if (kept.size != lines.size) runCatching { file.writeText(kept.joinToString("") { it.encode() + "\n" }) }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L

        /**
         * Observations relevant to [band] at [timeMs]. For "now" that is the
         * last few hours; for any other time it is what was heard at about the
         * same time of day on previous days, newer days counting more. This is
         * how the tab draws a heatmap for a future hour with no internet.
         */
        fun relevant(
            all: List<Observation>,
            band: Band,
            timeMs: Long,
            nowMs: Long,
            gridFor: (String) -> String?,
        ): List<WeightedObservation> = all.mapNotNull { o ->
            if (o.band != band || o.kind == Observation.Kind.PATH) return@mapNotNull null
            val grid = o.grid ?: gridFor(o.call) ?: return@mapNotNull null
            val pos = Geo.maidenheadToLatLon(grid) ?: return@mapNotNull null
            val w = timeWeight(o.timeMs, timeMs, nowMs)
            if (w <= 0.02) null else WeightedObservation(o, pos, w * snrWeight(o.snr))
        }

        /** Paths other stations reported on [band], weighted the same way: (hearer, heard, observation, weight). */
        fun relevantPaths(
            all: List<Observation>,
            band: Band,
            timeMs: Long,
            nowMs: Long,
            gridFor: (String) -> String?,
        ): List<NetPath> = all.mapNotNull { o ->
            if (o.band != band || o.kind != Observation.Kind.PATH) return@mapNotNull null
            val by = o.by?.let(gridFor)?.let { Geo.maidenheadToLatLon(it) } ?: return@mapNotNull null
            val heard = (o.grid ?: gridFor(o.call))?.let { Geo.maidenheadToLatLon(it) } ?: return@mapNotNull null
            val w = timeWeight(o.timeMs, timeMs, nowMs)
            if (w <= 0.02) null else NetPath(by, heard, o, w)
        }

        /**
         * How much an observation from [obsMs] says about [timeMs]: for "now",
         * the last 3 hours; for other hours, the same time of day on earlier
         * days, newer days counting more.
         */
        fun timeWeight(obsMs: Long, timeMs: Long, nowMs: Long): Double {
            if (obsMs > nowMs) return 0.0
            val todHours = abs(timeOfDayHours(obsMs) - timeOfDayHours(timeMs)).let { min(it, 24 - it) }
            val recent = nowMs - obsMs
            return when {
                abs(timeMs - nowMs) < HOUR_MS && recent < 3 * HOUR_MS -> 1.0 - 0.5 * recent / (3.0 * HOUR_MS)
                todHours <= 1.5 -> (1 - todHours / 1.5 * 0.6) * exp(-recent / (3.0 * DAY_MS))
                else -> 0.0
            }
        }

        /** Strong signals say more about an open path than marginal ones. */
        fun snrWeight(snr: Int): Double = ((snr + 26) / 20.0).coerceIn(0.25, 1.0)

        fun timeOfDayHours(ms: Long): Double {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
            return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
        }
    }
}
