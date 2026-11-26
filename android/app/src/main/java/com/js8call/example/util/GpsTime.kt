package com.js8call.example.util

import kotlin.math.abs

/**
 * Clock correction from GPS: how far this device's clock is from the time carried by a GPS fix.
 *
 * Android usually won't set its own clock from GPS (GNSS time detection is off by default and
 * only a device maker can turn it on), so off-grid the clock can be seconds out with a good fix
 * on screen. For the GPS provider, a fix's UTC time is set from the satellite constellation's
 * clock, and the fix also records the elapsed-realtime moment it was taken, so its age can be
 * corrected for exactly. Idea from doodlecomms' GPS UTC Clock (github.com/doodlecomms/gps_utc_clock),
 * suggested in issue #52; this is our own implementation.
 *
 * Plain Kotlin with no Android classes, so it can be tested. The screen is GpsTimeSyncDialog.
 */
object GpsTime {
    /** Fixes taken before deciding (one a second): their median steadies delivery jitter. */
    const val SAMPLES = 3
    /** Fixes whose offsets differ by more than this disagree: something is wrong. */
    const val DISAGREE_MS = 500L
    /** A horizontal accuracy worse than this is a weak fix. */
    const val POOR_ACCURACY_M = 100f
    /** A fix older than this when read is suspect (fresh fixes are asked for). */
    const val STALE_MS = 10_000L
    /** A change of the correction larger than this gets a plain note before it is applied. */
    const val LARGE_CHANGE_MS = 3_000L

    data class Sample(val offsetMs: Long, val fixAgeMs: Long, val accuracyM: Float?, val mock: Boolean)

    data class Result(
        /** Add this to the device clock to get GPS UTC (the app's "time drift"). */
        val offsetMs: Long,
        val spreadMs: Long,
        val samples: Int,
        val bestAccuracyM: Float?,
        val maxFixAgeMs: Long,
        val mock: Boolean,
    )

    enum class Concern { MOCK, POOR_ACCURACY, DISAGREE, STALE, LARGE_CHANGE }

    /**
     * The offset to add to the device clock to read GPS UTC: the fix's UTC moved forward by the
     * fix's age (from the elapsed-realtime clock, which the wall clock can't disturb), minus the
     * device's UTC now.
     */
    fun offsetMs(fixUtcMs: Long, fixElapsedNanos: Long, nowElapsedNanos: Long, nowWallMs: Long): Long {
        val ageMs = (nowElapsedNanos - fixElapsedNanos) / 1_000_000L
        return fixUtcMs + ageMs - nowWallMs
    }

    fun ageMs(fixElapsedNanos: Long, nowElapsedNanos: Long): Long = (nowElapsedNanos - fixElapsedNanos) / 1_000_000L

    /** Combines samples: the median offset, how far they spread, and the worst signs among them. */
    fun combine(samples: List<Sample>): Result? {
        if (samples.isEmpty()) return null
        val offsets = samples.map { it.offsetMs }.sorted()
        val median = if (offsets.size % 2 == 1) offsets[offsets.size / 2]
        else (offsets[offsets.size / 2 - 1] + offsets[offsets.size / 2]) / 2
        return Result(
            offsetMs = median,
            spreadMs = offsets.last() - offsets.first(),
            samples = samples.size,
            bestAccuracyM = samples.mapNotNull { it.accuracyM }.minOrNull(),
            maxFixAgeMs = samples.maxOf { it.fixAgeMs },
            mock = samples.any { it.mock },
        )
    }

    /**
     * What the operator should know before applying. None of these blocks anything: the
     * operator decides (a fake-location app, a weak fix, fixes that disagree, a big jump).
     */
    fun concerns(r: Result, currentDriftMs: Long): List<Concern> = buildList {
        if (r.mock) add(Concern.MOCK)
        if (r.bestAccuracyM == null || r.bestAccuracyM > POOR_ACCURACY_M) add(Concern.POOR_ACCURACY)
        if (r.spreadMs > DISAGREE_MS) add(Concern.DISAGREE)
        if (r.maxFixAgeMs > STALE_MS) add(Concern.STALE)
        if (abs(r.offsetMs - currentDriftMs) > LARGE_CHANGE_MS) add(Concern.LARGE_CHANGE)
    }

    /** Concerns that make the apply button read "Apply anyway" (a big change alone doesn't). */
    fun needsCare(concerns: List<Concern>): Boolean = concerns.any { it != Concern.LARGE_CHANGE }
}
