package com.js8call.example.library

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Antenna and feedline arithmetic for the Library. Rules of thumb, stated as
 * such in the guides: real antennas are cut a little long and trimmed.
 * Pure Kotlin; tested.
 */
object Calculators {

    const val FT_PER_M = 3.28084

    /** Free-space wavelength in metres. */
    fun wavelengthM(mhz: Double) = 299.792458 / mhz

    /** Half-wave dipole, total length in feet (the long-standing 468/f rule, which includes end effect). */
    fun dipoleTotalFt(mhz: Double) = 468.0 / mhz

    /** Inverted V: legs droop, so it needs to be a few percent longer than a flat dipole. */
    fun invertedVTotalFt(mhz: Double) = dipoleTotalFt(mhz) * 1.04

    /** Quarter-wave vertical or radial (234/f). */
    fun quarterWaveFt(mhz: Double) = 234.0 / mhz

    /**
     * End-fed half-wave radiator for its lowest band (≈ a half wave, 468/f), fed
     * through a 49:1 transformer; counterpoise about 0.05 wavelength.
     */
    fun efhwRadiatorFt(mhz: Double) = 468.0 / mhz
    fun efhwCounterpoiseFt(mhz: Double) = 0.05 * wavelengthM(mhz) * FT_PER_M

    /** NVIS dipole height range, 0.1 to 0.25 wavelength above ground, in feet. */
    fun nvisHeightFt(mhz: Double): ClosedFloatingPointRange<Double> {
        val l = wavelengthM(mhz) * FT_PER_M
        return (0.10 * l)..(0.25 * l)
    }

    /** Fraction of power reflected at an SWR (0..1). */
    fun reflectedFraction(swr: Double): Double {
        if (swr <= 1.0) return 0.0
        val g = (swr - 1) / (swr + 1)
        return g * g
    }

    /** Mismatch loss in dB at an SWR (power that never reaches a matched load). */
    fun mismatchLossDb(swr: Double): Double = -10 * log10(1 - reflectedFraction(swr))

    enum class Coax(val label: String, private val points: List<Pair<Double, Double>>) {
        // Typical published matched loss, dB per 100 ft, at (MHz, dB). Values from common
        // manufacturer/amateur reference tables; real cable varies by brand and age.
        RG58("RG-58", listOf(1.0 to 0.4, 10.0 to 1.4, 30.0 to 2.5)),
        RG8X("RG-8X", listOf(1.0 to 0.5, 10.0 to 1.0, 30.0 to 2.0)),
        RG213("RG-213 / RG-8", listOf(1.0 to 0.2, 10.0 to 0.6, 30.0 to 1.2)),
        LMR400("LMR-400", listOf(1.0 to 0.13, 10.0 to 0.4, 30.0 to 0.7));

        /** dB per 100 ft at [mhz]: log-log interpolation between the table points. */
        fun dbPer100Ft(mhz: Double): Double {
            val p = points
            val f = mhz.coerceIn(0.5, 60.0)
            val (a, b) = when {
                f <= p[1].first -> p[0] to p[1]
                else -> p[1] to p[2]
            }
            val t = (ln(f) - ln(a.first)) / (ln(b.first) - ln(a.first))
            return kotlin.math.exp(ln(a.second) + t * (ln(b.second) - ln(a.second)))
        }
    }

    data class FeedlineResult(val lossDb: Double, val powerOutW: Double, val percentLost: Double)

    fun feedline(coax: Coax, lengthFt: Double, mhz: Double, powerInW: Double): FeedlineResult {
        val db = coax.dbPer100Ft(mhz) * lengthFt / 100.0
        val out = powerInW * 10.0.pow(-db / 10.0)
        return FeedlineResult(db, out, 100.0 * (1 - out / powerInW))
    }

    /** JS8 speeds: name, seconds per frame (one frame per time slot), submode number for the engine. */
    enum class Speed(val label: String, val periodS: Int, val submode: Int, val bandwidthHz: Int, val limitDb: Int) {
        SLOW("Slow", 30, 4, 25, -28), NORMAL("Normal", 15, 0, 50, -24), FAST("Fast", 10, 1, 80, -20), TURBO("Turbo", 6, 2, 160, -18)
    }

    /** On-air time for a message of [frames] frames: each frame takes one whole time slot. */
    fun airtimeSeconds(frames: Int, speed: Speed): Int = frames * speed.periodS

    /** "1 min 15 s", "45 s" */
    fun duration(seconds: Int): String = if (seconds >= 60) "${seconds / 60} min ${seconds % 60} s" else "$seconds s"

    /** Where a JS8 signal actually is on the radio: dial frequency plus audio offset. */
    fun rfMHz(dialMHz: Double, offsetHz: Double) = dialMHz + offsetHz / 1_000_000.0

    /** "33.1 ft (10.1 m)" */
    fun ftAndM(ft: Double): String = String.format(java.util.Locale.US, "%.1f ft (%.2f m)", ft, ft / FT_PER_M)

    /** "13.9–34.7 ft (4.2–10.6 m)" */
    fun rangeFtAndM(r: ClosedFloatingPointRange<Double>): String = String.format(
        java.util.Locale.US, "%.0f–%.0f ft (%.1f–%.1f m)", r.start, r.endInclusive, r.start / FT_PER_M, r.endInclusive / FT_PER_M,
    )
}
