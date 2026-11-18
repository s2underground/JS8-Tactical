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
) {
    enum class Kind { HEARD, HEARD_ME }

    fun encode(): String = listOf(timeMs, call, grid.orEmpty(), band.name, snr, kind.name).joinToString(",")

    companion object {
        fun decode(line: String): Observation? {
            val p = line.split(',')
            if (p.size != 6) return null
            return runCatching {
                Observation(
                    p[0].toLong(), p[1], p[2].ifBlank { null }, Band.valueOf(p[3]), p[4].toInt(), Kind.valueOf(p[5]),
                )
            }.getOrNull()
        }
    }
}

/** A plotted observation: where it was, and how much it counts for the selected time. */
data class WeightedObservation(val obs: Observation, val position: LatLon, val weight: Double)

/**
 * Keeps a week of observations in a small text file so the offline heatmap
 * survives restarts. Writes are append-only; the file is compacted on load.
 */
class ObservationStore(private val file: File, private val keepMs: Long = 7 * DAY_MS) {

    private val items = mutableListOf<Observation>()
    private var loaded = false

    @Synchronized
    fun all(): List<Observation> {
        load()
        return items.toList()
    }

    @Synchronized
    fun add(o: Observation, persist: Boolean = true) {
        load()
        // One entry per station, band, kind and 15-minute window is plenty.
        val dup = items.any {
            it.call == o.call && it.band == o.band && it.kind == o.kind && abs(it.timeMs - o.timeMs) < 15 * 60_000L
        }
        if (dup) return
        items += o
        if (persist) runCatching { file.appendText(o.encode() + "\n") }
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
            if (o.band != band || o.timeMs > nowMs) return@mapNotNull null
            val grid = o.grid ?: gridFor(o.call) ?: return@mapNotNull null
            val pos = Geo.maidenheadToLatLon(grid) ?: return@mapNotNull null
            val todHours = abs(timeOfDayHours(o.timeMs) - timeOfDayHours(timeMs)).let { min(it, 24 - it) }
            val recent = nowMs - o.timeMs
            val w = when {
                // Live picture: anything from the last 3 hours when looking at about now.
                abs(timeMs - nowMs) < HOUR_MS && recent < 3 * HOUR_MS -> 1.0 - 0.5 * recent / (3.0 * HOUR_MS)
                // Same time of day on earlier days.
                todHours <= 1.5 -> (1 - todHours / 1.5 * 0.6) * exp(-recent / (3.0 * DAY_MS))
                else -> 0.0
            }
            if (w <= 0.02) null else WeightedObservation(o, pos, w * snrWeight(o.snr))
        }

        /** Strong signals say more about an open path than marginal ones. */
        fun snrWeight(snr: Int): Double = ((snr + 26) / 20.0).coerceIn(0.25, 1.0)

        fun timeOfDayHours(ms: Long): Double {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
            return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
        }

        /**
         * The solar flux that best explains what was actually heard, for when
         * NOAA can't be reached. Positive evidence only, so it is held near the
         * last known value unless the traffic clearly says otherwise.
         */
        fun fitSfi(me: LatLon, evidence: List<Pair<Observation, LatLon>>, prior: SpaceWeather): Double? {
            if (evidence.size < 3) return null
            var best = prior.sfi
            var bestScore = Double.NEGATIVE_INFINITY
            var sfi = 65.0
            while (sfi <= 300.0) {
                val wx = prior.copy(sfi = sfi)
                val fit = evidence.sumOf { (o, pos) ->
                    PropagationModel.reliability(PropagationModel.path(me, pos, o.timeMs, wx), o.band.mhz)
                } / evidence.size
                val score = fit - 0.0008 * abs(sfi - prior.sfi)
                if (score > bestScore) { bestScore = score; best = sfi }
                sfi += 5.0
            }
            return best
        }
    }
}
