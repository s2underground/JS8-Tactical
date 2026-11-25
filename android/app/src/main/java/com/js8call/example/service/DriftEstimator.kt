package com.js8call.example.service

/**
 * The clock correction automatic time sync applies: the median of the latest drift heard on each
 * audio lane in the last [windowMs]. Each transmitter (lane) counts once however many frames it
 * sends, so one station, whether its clock is simply off or it is trying to drag ours, can't
 * outvote the rest of the band by talking more; with several stations heard, an outlier has no
 * effect on the median. Plain Kotlin, unit-tested (DriftEstimatorTest).
 */
class DriftEstimator(private val windowMs: Long = DEFAULT_WINDOW_MS, private val laneHz: Float = DEFAULT_LANE_HZ) {
    private class Sample(val driftMs: Long, val atMs: Long)

    private val latest = HashMap<Int, Sample>()

    /** A decode at audio offset [freqHz] whose timing suggests [driftMs]. */
    @Synchronized
    fun record(freqHz: Float, driftMs: Long, now: Long) {
        if (!freqHz.isFinite()) return
        latest[Math.round(freqHz / laneHz)] = Sample(driftMs, now)
    }

    /**
     * The correction to apply now, or null with fewer than [minLanes] lanes heard in the window:
     * one transmitter, even one using several lanes close together, shouldn't set our clock on
     * its own (second audit, N11). "Sync once" stays available for a quiet band.
     */
    @Synchronized
    fun estimate(now: Long, minLanes: Int = 1): Long? {
        latest.values.removeAll { now - it.atMs > windowMs || it.atMs > now }
        if (latest.isEmpty() || latest.size < minLanes) return null
        val sorted = latest.values.map { it.driftMs }.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Lanes in the current estimate. */
    @Synchronized
    fun lanes(now: Long): Int { estimate(now); return latest.size }

    @Synchronized
    fun clear() = latest.clear()

    companion object {
        const val DEFAULT_WINDOW_MS = 10 * 60_000L
        /** Wider than a JS8 signal (50 Hz in Normal), narrower than typical spacing. */
        const val DEFAULT_LANE_HZ = 25f
        /** Lanes needed before automatic sync moves the clock. */
        const val MIN_LANES = 3
        /** Largest single move automatic sync makes; a bigger correction is reached in steps. */
        const val MAX_STEP_MS = 1_000L

        /** The next correction to apply, moving from [current] toward [target] by at most [MAX_STEP_MS]. */
        fun step(current: Long, target: Long): Long =
            current + (target - current).coerceIn(-MAX_STEP_MS, MAX_STEP_MS)
    }
}
