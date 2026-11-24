package com.js8call.example.interference

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Spots interference on the dial from the receiver's spectrum (the same data the waterfall draws),
 * so an operator can tell the difference between "nobody is on" and "nothing can get through".
 *
 * What it looks for, in the JS8 passband (300–2700 Hz), in 15-second windows on the JS8 slot clock:
 *  - NOISE: the noise floor well above what this dial normally shows at this hour of the day
 *    (learned while things are normal). Because a raised floor can also be a gain change, it
 *    only counts as likely when traffic backs it up: stations that are normally decoded here
 *    have gone silent, or the ones still decoded come in much weaker.
 *  - CARRIER: a steady tone that stays put through slot boundaries for a minute or more. JS8
 *    signals hop between tones and pause between slots, so they never look like this. Tones
 *    that are present here most of the time (a power supply, a local "birdie") are learned and
 *    reported as resident rather than as new interference.
 *    A steady tone sitting exactly on a power-line harmonic (n × 50 or n × 60 Hz, low in the
 *    passband) is reported as probable hum from the station's own setup (a charger, a ground
 *    loop), not as interference.
 *  - SWEEP: a narrow signal sliding across the passband, again and again.
 *  - PULSE: the whole passband switching on and off in a rhythm.
 *
 * It never decides that interference is deliberate: it reports what it measured, and the
 * operator judges whether that is jamming. It never stops a transmission either; the app only
 * warns. Pure Kotlin (no Android), so it is unit tested with synthetic spectra.
 *
 * Not thread-safe: the owner serialises calls.
 */
class InterferenceDetector(
    /** Learned "normal" per dial (kHz). The owner persists it. */
    val baselines: MutableMap<Long, DialBaseline> = HashMap(),
) {
    enum class Kind { NOISE, CARRIER, SWEEP, PULSE }

    enum class Level {
        /** Nothing unusual (or not enough data yet). */
        NONE,
        /** Something is off but nothing confirms it hurts traffic (e.g. floor up, no decodes to compare). */
        POSSIBLE,
        /** Interference that is affecting, or very likely affecting, reception. */
        LIKELY,
    }

    data class Assessment(
        val dialHz: Long,
        val level: Level,
        val kinds: Set<Kind>,
        /** How far the noise floor sits above this dial's normal, when a baseline exists. */
        val noiseAboveDb: Float?,
        /** Audio offsets (Hz) of steady carriers that are new here. */
        val carriersHz: List<Int>,
        /** Steady tones that are normally present on this dial (likely local). */
        val residentCarriersHz: List<Int>,
        /** Steady tones on a power-line harmonic: probably hum from the station's own setup. */
        val humHz: List<Int>,
        /** Stations normally decoded here have gone silent for the last 5 minutes. */
        val decodesQuiet: Boolean,
        /** Decodes still coming in, but this many dB weaker than usual. */
        val snrDropDb: Float?,
        /** When the current finding started (wall clock ms), or null. */
        val sinceMs: Long?,
        /** Enough history on this dial and hour to judge the noise floor. */
        val baselineReady: Boolean,
    ) {
        /** Worth warning about before transmitting: traffic here is affected, not just a stray tone. */
        val affectsTraffic: Boolean
            get() = level == Level.LIKELY && (Kind.NOISE in kinds || Kind.SWEEP in kinds || Kind.PULSE in kinds || decodesQuiet)

        /** A carrier within [hz] ± 60 Hz (on top of a transmit offset). */
        fun carrierNear(hz: Float): Boolean = carriersHz.any { abs(it - hz) <= 60f }

        companion object {
            fun none(dialHz: Long, ready: Boolean = false) =
                Assessment(dialHz, Level.NONE, emptySet(), null, emptyList(), emptyList(), emptyList(), false, null, null, ready)
        }
    }

    /** What "normal" looks like on one dial, by UTC hour. Updated only while nothing is flagged. */
    class DialBaseline {
        /** Noise floor (dB, receiver-relative) per UTC hour; NaN = never seen. */
        val floorDb = FloatArray(24) { Float.NaN }
        /** Windows that went into [floorDb], per hour (capped). */
        val floorN = IntArray(24)
        /** Decodes per 15 s window, per hour (average). */
        val decodesPerWindow = FloatArray(24)
        /** Median SNR of decodes, per hour; NaN = none yet. */
        val snrDb = FloatArray(24) { Float.NaN }
        /** How often each 25 Hz cell carries a steady tone (0..1, slow average). */
        val toneOccupancy = FloatArray(CELLS)

        /** Hour [h] has enough history to judge the noise floor (about 10 minutes of normal windows). */
        fun ready(h: Int) = floorN[h] >= MIN_BASELINE_WINDOWS && !floorDb[h].isNaN()
    }

    // ------------------------------------------------------------------ state

    private class Window(val index: Long) {
        val frameFloors = ArrayList<Float>(160)
        val toneFrames = IntArray(CELLS)
        /** Measured frequency of each cell's tone (sum and count over the frames it was there). */
        val toneHzSum = DoubleArray(CELLS)
        val toneHzN = IntArray(CELLS)
        var frames = 0
        val peaks = ArrayList<Int>(160) // strongest cell per frame, or -1
        var decodes = 0
        val snrs = ArrayList<Int>()
    }

    private class Summary(
        val index: Long,
        val floorDb: Float,
        val toneOcc: FloatArray,
        /** Mean measured frequency of each cell's tone; NaN when it had none. */
        val toneHz: FloatArray,
        val sweeps: Int,
        val pulsed: Boolean,
        val decodes: Int,
        val snrs: List<Int>,
    )

    private var dialHz = 0L
    private var current: Window? = null
    private val recent = ArrayDeque<Summary>()
    private var lastFrameMs = 0L
    private var muteUntilMs = 0L
    private var assessment = Assessment.none(0)
    private var findingSinceMs: Long? = null
    private val cellDb = FloatArray(CELLS)
    private val cellFirstBin = IntArray(CELLS)
    private val cellEndBin = IntArray(CELLS)
    private val sortScratch = FloatArray(CELLS)

    /** The latest judgement (updated every 15 s). */
    fun assessment(): Assessment = assessment

    /** Our own transmission is on the air (and briefly after): the spectrum then isn't the band. */
    fun onTransmit(nowMs: Long, holdMs: Long = 2_000) {
        muteUntilMs = max(muteUntilMs, nowMs + holdMs)
    }

    /** A decoded frame on the current dial. */
    fun onDecode(nowMs: Long, dialHz: Long, snr: Int) {
        if (dialHz != this.dialHz) return
        val w = windowFor(nowMs)
        w.decodes++
        w.snrs += snr
    }

    /**
     * One spectrum frame (about 10 per second): [bins] linear power from 0 Hz upward, [binHz]
     * apart, as JS8Core reports it.
     */
    fun onSpectrum(nowMs: Long, dialHz: Long, bins: FloatArray, binHz: Float) {
        if (dialHz <= 0 || binHz <= 0f) return
        if (dialHz != this.dialHz) switchDial(dialHz)
        // A long gap (receiver stopped, app asleep): earlier windows no longer describe "now".
        if (lastFrameMs != 0L && nowMs - lastFrameMs > 60_000) { recent.clear(); current = null; findingSinceMs = null }
        lastFrameMs = nowMs
        if (nowMs < muteUntilMs) return
        if (!cells(bins, binHz)) return
        val w = windowFor(nowMs)
        // Frame noise floor: the 20th percentile of the cells, so a few signals don't lift it.
        System.arraycopy(cellDb, 0, sortScratch, 0, CELLS)
        sortScratch.sort()
        val floor = sortScratch[CELLS / 5]
        w.frameFloors += floor
        var peakCell = -1
        var peakDb = floor + PEAK_DB
        for (c in 0 until CELLS) {
            if (cellDb[c] >= floor + TONE_DB) {
                w.toneFrames[c]++
                toneHz(bins, binHz, c)?.let { w.toneHzSum[c] += it; w.toneHzN[c]++ }
            }
            if (cellDb[c] >= peakDb) { peakDb = cellDb[c]; peakCell = c }
        }
        w.peaks += peakCell
        w.frames++
    }

    // ------------------------------------------------------------------ internals

    private fun switchDial(hz: Long) {
        dialHz = hz
        recent.clear()
        current = null
        findingSinceMs = null
        assessment = Assessment.none(hz, baselines[khz(hz)]?.ready(hourOf(System.currentTimeMillis())) == true)
    }

    private fun windowFor(nowMs: Long): Window {
        val index = nowMs / WINDOW_MS
        val w = current
        if (w != null && w.index == index) return w
        if (w != null) finish(w, nowMs)
        return Window(index).also { current = it }
    }

    /**
     * Where in cell [c] the tone actually is: the power-weighted centre of the strongest bin and
     * its two neighbours each side, in Hz. Sub-bin precision (bins are under 1 Hz apart).
     */
    private fun toneHz(bins: FloatArray, binHz: Float, c: Int): Double? {
        var peak = cellFirstBin[c]
        for (i in cellFirstBin[c] until cellEndBin[c]) if (bins[i] > bins[peak]) peak = i
        var p = 0.0
        var pf = 0.0
        for (i in max(0, peak - 2)..minOf(bins.size - 1, peak + 2)) { p += bins[i]; pf += bins[i].toDouble() * i }
        return if (p > 0) pf / p * binHz else null
    }

    /** Average the bins into 25 Hz cells across the passband, in dB. False if the spectrum doesn't cover it. */
    private fun cells(bins: FloatArray, binHz: Float): Boolean {
        val first = (LOW_HZ / binHz).toInt()
        val perCell = CELL_HZ / binHz
        if (first + (CELLS * perCell).toInt() >= bins.size) return false
        for (c in 0 until CELLS) {
            val a = first + (c * perCell).toInt()
            val b = max(a + 1, first + ((c + 1) * perCell).toInt())
            cellFirstBin[c] = a
            cellEndBin[c] = b
            var sum = 0.0
            for (i in a until b) sum += bins[i]
            cellDb[c] = (10 * log10(sum / (b - a) + 1e-20)).toFloat()
        }
        return true
    }

    private fun finish(w: Window, nowMs: Long) {
        if (w.frames < MIN_FRAMES) return // mostly our own transmission, or the receiver just started
        val floors = w.frameFloors.sorted()
        val floor = floors[floors.size / 2]
        val occ = FloatArray(CELLS) { w.toneFrames[it].toFloat() / w.frames }
        val toneHz = FloatArray(CELLS) { if (w.toneHzN[it] > 0) (w.toneHzSum[it] / w.toneHzN[it]).toFloat() else Float.NaN }
        // Pulsing: the frame floors jump between two levels at least 8 dB apart, many times.
        val lo = floors[floors.size / 10]
        val hi = floors[floors.size * 9 / 10]
        var transitions = 0
        if (hi - lo >= 8f) {
            val mid = (hi + lo) / 2
            var above = w.frameFloors.first() > mid
            for (f in w.frameFloors) { val a = f > mid; if (a != above) { transitions++; above = a } }
        }
        val summary = Summary(w.index, floor, occ, toneHz, countSweeps(w.peaks), transitions >= 6, w.decodes, w.snrs.toList())
        recent.addLast(summary)
        while (recent.size > HISTORY_WINDOWS) recent.removeFirst()
        assessment = evaluate(nowMs)
        learn(summary, nowMs)
    }

    /** Runs of the strongest cell moving steadily one way across at least 1000 Hz. */
    private fun countSweeps(peaks: List<Int>): Int {
        var sweeps = 0
        var runStart = -1
        var dir = 0
        var prev = -1
        var back = 0
        fun close(end: Int) {
            if (runStart >= 0 && abs(end - runStart) >= SWEEP_CELLS) sweeps++
            runStart = -1; dir = 0; back = 0
        }
        for (p in peaks) {
            if (p < 0) { close(prev); prev = -1; continue }
            if (prev < 0 || runStart < 0) { runStart = p; prev = p; dir = 0; continue }
            val step = p - prev
            when {
                step == 0 -> Unit
                dir == 0 -> dir = if (step > 0) 1 else -1
                (step > 0) == (dir > 0) -> Unit
                back++ < 1 -> Unit // tolerate one wobble
                else -> { close(prev); runStart = p }
            }
            prev = p
        }
        close(prev)
        return sweeps
    }

    private fun evaluate(nowMs: Long): Assessment {
        val base = baselines[khz(dialHz)]
        val hour = hourOf(nowMs)
        val ready = base?.ready(hour) == true
        val last = recent.toList()
        if (last.size < 3) return Assessment.none(dialHz, ready)
        val kinds = LinkedHashSet<Kind>()

        // Noise floor against this dial's normal for the hour.
        var above: Float? = null
        if (ready) {
            val diffs = last.takeLast(3).map { it.floorDb - base!!.floorDb[hour] }
            above = diffs.sorted()[1]
            if (diffs.all { it >= NOISE_DB }) kinds += Kind.NOISE
        }

        // Steady tones through four windows (a minute, across slot boundaries).
        val newCarriers = ArrayList<Int>()
        val resident = ArrayList<Int>()
        val hum = ArrayList<Int>()
        if (last.size >= CARRIER_WINDOWS) {
            val span = last.takeLast(CARRIER_WINDOWS)
            var c = 0
            while (c < CELLS) {
                if (span.all { it.toneOcc[c] >= CARRIER_OCC }) {
                    var end = c
                    while (end + 1 < CELLS && span.all { it.toneOcc[end + 1] >= CARRIER_OCC }) end++
                    // The measured frequency in the run's most steadily occupied cell.
                    val best = (c..end).maxByOrNull { cell -> span.sumOf { it.toneOcc[cell].toDouble() } } ?: c
                    val measured = span.map { it.toneHz[best] }.filter { !it.isNaN() }.takeIf { it.isNotEmpty() }?.average()
                    val hz = measured?.let { Math.round(it).toInt() } ?: (LOW_HZ + ((c + end) / 2 + 0.5f) * CELL_HZ).toInt()
                    val known = (c..end).any { (base?.toneOccupancy?.get(it) ?: 0f) >= RESIDENT_OCC }
                    when {
                        measured != null && isPowerLineHarmonic(measured) -> hum += hz
                        known -> resident += hz
                        else -> newCarriers += hz
                    }
                    c = end + 1
                } else c++
            }
        }
        if (newCarriers.isNotEmpty()) kinds += Kind.CARRIER
        if (last.takeLast(4).count { it.sweeps >= 2 } >= 2) kinds += Kind.SWEEP
        if (last.takeLast(4).count { it.pulsed } >= 2) kinds += Kind.PULSE

        // Traffic: normally decoded stations silent, or decoded much weaker than usual.
        var quiet = false
        var snrDrop: Float? = null
        if (base != null && last.size >= QUIET_WINDOWS) {
            val window = last.takeLast(QUIET_WINDOWS)
            val expected = base.decodesPerWindow[hour] * QUIET_WINDOWS
            quiet = expected >= 3f && window.sumOf { it.decodes } == 0
            val snrs = window.flatMap { it.snrs }
            val usual = base.snrDb[hour]
            if (snrs.size >= 4 && !usual.isNaN()) {
                val drop = usual - snrs.sorted()[snrs.size / 2]
                if (drop >= SNR_DROP_DB) snrDrop = drop
            }
        }

        val confirmed = quiet || snrDrop != null
        val level = when {
            Kind.CARRIER in kinds || Kind.SWEEP in kinds || Kind.PULSE in kinds -> Level.LIKELY
            Kind.NOISE in kinds && confirmed -> Level.LIKELY
            Kind.NOISE in kinds -> Level.POSSIBLE
            else -> Level.NONE
        }
        val since = if (level == Level.NONE) null else (findingSinceMs ?: (last[last.size - 3].index * WINDOW_MS))
        findingSinceMs = since
        return Assessment(dialHz, level, kinds, above, newCarriers, resident, hum, quiet && level != Level.NONE, snrDrop, since, ready)
    }

    /** Fold a normal window into the baseline (never one with a finding, so interference isn't learned as normal). */
    private fun learn(s: Summary, nowMs: Long) {
        val base = baselines.getOrPut(khz(dialHz)) { DialBaseline() }
        val h = hourOf(nowMs)
        // Tones are learned slowly in any case, so a local birdie becomes "resident" after hours.
        for (c in 0 until CELLS) {
            val steady = if (s.toneOcc[c] >= CARRIER_OCC) 1f else 0f
            base.toneOccupancy[c] += (steady - base.toneOccupancy[c]) / TONE_TIME_WINDOWS
        }
        if (assessment.level != Level.NONE) return
        if (base.ready(h) && abs(s.floorDb - base.floorDb[h]) >= NOISE_DB) return
        val n = minOf(base.floorN[h], BASELINE_MEMORY)
        base.floorDb[h] = if (base.floorDb[h].isNaN()) s.floorDb else base.floorDb[h] + (s.floorDb - base.floorDb[h]) / (n + 1)
        base.decodesPerWindow[h] += (s.decodes - base.decodesPerWindow[h]) / (n + 1)
        if (s.snrs.isNotEmpty()) {
            val med = s.snrs.sorted()[s.snrs.size / 2].toFloat()
            base.snrDb[h] = if (base.snrDb[h].isNaN()) med else base.snrDb[h] + (med - base.snrDb[h]) / (minOf(n, 40) + 1)
        }
        if (base.floorN[h] < BASELINE_CAP) base.floorN[h]++
    }

    companion object {
        const val WINDOW_MS = 15_000L
        const val LOW_HZ = 300f
        const val CELL_HZ = 25f
        const val CELLS = 96 // 300–2700 Hz
        /** A cell this far over the frame floor counts as carrying a tone. */
        const val TONE_DB = 12f
        /** The strongest cell must be this far over the floor to count as a peak (sweeps). */
        const val PEAK_DB = 15f
        const val NOISE_DB = 10f
        const val SNR_DROP_DB = 6f
        const val CARRIER_OCC = 0.95f
        const val CARRIER_WINDOWS = 4
        const val RESIDENT_OCC = 0.6f
        const val SWEEP_CELLS = 40 // 1000 Hz
        const val QUIET_WINDOWS = 20 // 5 minutes
        const val HISTORY_WINDOWS = 24
        const val MIN_FRAMES = 50 // 5 s of spectrum in a window
        const val MIN_BASELINE_WINDOWS = 40 // 10 minutes of normal reception at this hour
        const val BASELINE_MEMORY = 200
        const val BASELINE_CAP = 10_000
        /** Tone learning time constant, in windows (1 hour). */
        const val TONE_TIME_WINDOWS = 240f

        /** Highest harmonic of 50/60 Hz mains treated as hum (up to 600/720 Hz). */
        const val HUM_MAX_HARMONIC = 12
        const val HUM_TOL_HZ = 1.2

        /** Within [HUM_TOL_HZ] of n × 50 or n × 60 Hz (n ≤ [HUM_MAX_HARMONIC]). */
        fun isPowerLineHarmonic(hz: Double): Boolean = listOf(50.0, 60.0).any { mains ->
            val n = Math.round(hz / mains)
            n in 1..HUM_MAX_HARMONIC && abs(hz - n * mains) <= HUM_TOL_HZ
        }

        fun khz(dialHz: Long) = dialHz / 1000
        fun hourOf(ms: Long) = ((ms / 3_600_000L) % 24).toInt()
    }
}
