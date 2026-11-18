package com.js8call.example.prop

import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Minutes spent listening per band and clock hour. This is what turns "I
 * didn't hear N0DEF" into evidence: it only counts if we were on that band.
 * Kept for 30 days in a small text file; fully offline.
 */
class ListeningLog(private val file: File, private val keepMs: Long = 30 * ObservationStore.DAY_MS) {

    private val minutes = HashMap<Pair<Long, Band>, Double>()
    private var loaded = false
    private var pending = HashMap<Pair<Long, Band>, Double>()

    @Synchronized
    fun add(timeMs: Long, band: Band, mins: Double) {
        load()
        val key = hourStart(timeMs) to band
        minutes[key] = (minutes[key] ?: 0.0) + mins
        pending[key] = (pending[key] ?: 0.0) + mins
    }

    /** Write what was added since the last flush (call every minute or so). */
    @Synchronized
    fun flush() {
        if (pending.isEmpty()) return
        val text = pending.entries.joinToString("") { (k, v) -> "${k.first},${k.second.name},${"%.2f".format(java.util.Locale.US, v)}\n" }
        pending = HashMap()
        runCatching { file.appendText(text) }
    }

    /** (hour start, band) → minutes listened, for hours with at least [minMinutes]. */
    @Synchronized
    fun hours(minMinutes: Double = 20.0): Map<Pair<Long, Band>, Double> {
        load()
        return minutes.filterValues { it >= minMinutes }
    }

    @Synchronized
    fun totalHours(): Double {
        load()
        return minutes.values.sum() / 60.0
    }

    @Synchronized
    fun clear() {
        minutes.clear(); pending.clear()
        runCatching { file.delete() }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val cutoff = System.currentTimeMillis() - keepMs
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        for (line in lines) {
            val p = line.split(',')
            if (p.size != 3) continue
            val t = p[0].toLongOrNull() ?: continue
            val b = runCatching { Band.valueOf(p[1]) }.getOrNull() ?: continue
            val m = p[2].toDoubleOrNull() ?: continue
            if (t < cutoff) continue
            minutes[t to b] = (minutes[t to b] ?: 0.0) + m
        }
        // Compact: one line per hour and band.
        runCatching {
            file.writeText(minutes.entries.joinToString("") { (k, v) -> "${k.first},${k.second.name},${"%.2f".format(java.util.Locale.US, v)}\n" })
        }
    }

    companion object {
        fun hourStart(ms: Long) = ms - Math.floorMod(ms, ObservationStore.HOUR_MS)
    }
}

/**
 * What this station has taught the model: a log-odds correction per band and
 * distance bucket (same buckets as the band chart). Learned with a prior that
 * pulls every cell towards zero, so a few lucky contacts can't swing it.
 * Tested on 158 WSPR receivers: four days of learning improved the next three
 * days' predictions for 145 of them (about 13 % lower log-loss).
 */
class StationCorrection(
    private val offsets: Map<Band, DoubleArray>,
    val pairs: Int,
    val heard: Int,
    val days: Double,
    /** How often stations that were around were actually decoded (log-odds); used by the tune-up, not shown. */
    val global: Double = 0.0,
) {
    /** Log-odds correction, capped so no single cell can flip a prediction wholesale. */
    fun offset(band: Band, km: Double): Double {
        val o = offsets[band] ?: return 0.0
        return o[bucket(km)].coerceIn(-2.5, 2.5)
    }

    fun adjust(score: Double, band: Band, km: Double): Double {
        val off = offset(band, km)
        if (off == 0.0) return score
        val s = score.coerceIn(0.02, 0.98)
        return 1 / (1 + exp(-(ln(s / (1 - s)) + off)))
    }

    /** Offsets for display/debugging: band → five buckets. */
    fun table(): Map<Band, DoubleArray> = offsets

    companion object {
        val NONE = StationCorrection(emptyMap(), 0, 0, 0.0)
        fun bucket(km: Double) = PropagationModel.CHART_EDGES_KM.indexOfFirst { km < it }.coerceAtLeast(0)
    }
}

/** One heard / not-heard test built from the logs. [km] is the distance from this station. */
data class LearningPair(val timeMs: Long, val call: String, val position: LatLon, val band: Band, val heard: Boolean, val km: Double)

object Learner {
    private const val PRIOR_SD = 0.5
    private const val GLOBAL_SD = 2.0

    /**
     * Heard / not-heard tests from the logs. For every hour we listened on a
     * band, each station with a known grid that was on the air around then
     * (we heard it within two hours, or someone reported it within an hour)
     * is a test: did we decode it that hour?
     */
    fun pairs(
        obs: List<Observation>,
        listening: Map<Pair<Long, Band>, Double>,
        gridFor: (String) -> String?,
        me: LatLon,
        sinceMs: Long = 0L,
    ): List<LearningPair> {
        val out = ArrayList<LearningPair>()
        val byBand = obs.groupBy { it.band }
        for ((key, _) in listening) {
            val (hour, band) = key
            if (hour < sinceMs) continue
            val list = byBand[band] ?: continue
            val active = HashSet<String>()
            val heardThisHour = HashSet<String>()
            for (o in list) {
                val dt = o.timeMs - hour
                when (o.kind) {
                    Observation.Kind.HEARD -> {
                        if (dt >= -2 * ObservationStore.HOUR_MS && dt < 3 * ObservationStore.HOUR_MS) active += o.call
                        if (dt >= 0 && dt < ObservationStore.HOUR_MS) heardThisHour += o.call
                    }
                    Observation.Kind.PATH, Observation.Kind.HEARD_ME -> {
                        if (dt >= -ObservationStore.HOUR_MS && dt < 2 * ObservationStore.HOUR_MS) {
                            active += o.call
                            o.by?.let { active += it }
                        }
                    }
                }
            }
            for (call in active) {
                val grid = list.firstOrNull { it.call == call && it.grid != null }?.grid ?: gridFor(call) ?: continue
                val pos = Geo.maidenheadToLatLon(grid) ?: continue
                val km = Geo.distanceKm(me, pos)
                if (km < 5) continue
                out += LearningPair(hour + ObservationStore.HOUR_MS / 2, call, pos, band, call in heardThisHour, km)
            }
        }
        return out
    }

    /**
     * Fit the station correction. [score] gives the model's prediction for a
     * pair (already adjusted for the station profile). A global offset soaks
     * up how often stations happen to be transmitting; only the per-cell
     * offsets are kept.
     */
    fun fit(pairs: List<LearningPair>, score: (LearningPair) -> Double): StationCorrection {
        if (pairs.size < 10) return StationCorrection.NONE
        val nb = PropagationModel.CHART_EDGES_KM.size
        val nCells = Band.entries.size * nb
        val n = pairs.size
        val z0 = DoubleArray(n)
        val cell = IntArray(n)
        val y = DoubleArray(n)
        for ((i, p) in pairs.withIndex()) {
            val s = score(p).coerceIn(0.02, 0.98)
            z0[i] = ln(s / (1 - s))
            cell[i] = p.band.ordinal * nb + StationCorrection.bucket(p.km)
            y[i] = if (p.heard) 1.0 else 0.0
        }
        var g = 0.0
        val off = DoubleArray(nCells)
        val grad = DoubleArray(nCells)
        val hess = DoubleArray(nCells)
        repeat(25) {
            // global offset
            var r = 0.0; var h = 0.0
            for (i in 0 until n) {
                val p = sigmoid(z0[i] + g + off[cell[i]])
                r += y[i] - p; h += p * (1 - p)
            }
            g += (r - g / (GLOBAL_SD * GLOBAL_SD)) / (h + 1 / (GLOBAL_SD * GLOBAL_SD))
            // per-cell offsets
            grad.fill(0.0); hess.fill(0.0)
            for (i in 0 until n) {
                val p = sigmoid(z0[i] + g + off[cell[i]])
                grad[cell[i]] += y[i] - p
                hess[cell[i]] += p * (1 - p)
            }
            for (c in 0 until nCells) {
                off[c] += (grad[c] - off[c] / (PRIOR_SD * PRIOR_SD)) / (hess[c] + 1 / (PRIOR_SD * PRIOR_SD))
            }
        }
        val map = Band.entries.associateWith { b -> DoubleArray(nb) { k -> off[b.ordinal * nb + k] } }
            .filterValues { arr -> arr.any { abs(it) > 1e-3 } }
        val span = (pairs.maxOf { it.timeMs } - pairs.minOf { it.timeMs }) / ObservationStore.DAY_MS.toDouble()
        return StationCorrection(map, n, y.count { it > 0 }, span, g)
    }

    /**
     * Live tune-up: the MUF scale that best explains the last few hours of
     * traffic. [own] are this station's heard / not-heard tests; [netPaths]
     * are paths other stations reported (positives only, so they count less).
     * Held near 1.0 by a prior and limited to 0.8–1.2. On WSPR data, a 25-
     * station local net using the last 3 hours improved the next hour's
     * predictions slightly (~0.7 % log-loss) and never made them worse.
     */
    fun nowcast(
        own: List<LearningPair>,
        netPaths: List<Pair<LatLon, LatLon>>,
        netBands: List<Band>,
        netTimes: List<Long>,
        global: Double,
        /** Predicted score; [from] null means this station (profile and learned correction applied). */
        scoreAt: (from: LatLon?, to: LatLon, band: Band, timeMs: Long, phi: Double) -> Double,
    ): Nowcast {
        val evidence = own.size + netPaths.size
        if (evidence < 15) return Nowcast(1.0, evidence)
        var best = 1.0
        var bestScore = Double.NEGATIVE_INFINITY
        var phi = 0.80
        while (phi <= 1.2001) {
            var ll = 0.0
            for (p in own) {
                val pr = withGlobal(scoreAt(null, p.position, p.band, p.timeMs, phi), global)
                ll += if (p.heard) ln(pr) else ln(1 - pr)
            }
            for (i in netPaths.indices) {
                val pr = withGlobal(scoreAt(netPaths[i].first, netPaths[i].second, netBands[i], netTimes[i], phi), global)
                ll += 0.5 * ln(pr)
            }
            // Prior worth about five observations (validated on WSPR data at this strength).
            val prior = 0.5 * (ln(phi) / 0.08) * (ln(phi) / 0.08) * 5
            val total = ll - prior
            if (total > bestScore) { bestScore = total; best = phi }
            phi += 0.05
        }
        return Nowcast(best, evidence)
    }

    private fun sigmoid(x: Double) = 1 / (1 + exp(-x))

    private fun withGlobal(score: Double, global: Double): Double {
        val s = score.coerceIn(0.02, 0.98)
        return sigmoid(ln(s / (1 - s)) + global).coerceIn(1e-4, 1 - 1e-4)
    }
}

/** Result of the live tune-up: MUF scale and how much traffic it rests on. */
data class Nowcast(val phi: Double, val evidence: Int)
