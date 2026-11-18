package com.js8call.example.prop

import android.content.Context
import androidx.preference.PreferenceManager
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** JS8 speeds and the weakest signal each decodes (dB in 2500 Hz, JS8Call documentation). */
enum class Js8Speed(val label: String, val thresholdDb: Double, val submode: Int) {
    SLOW("Slow", -28.0, 4), NORMAL("Normal", -24.0, 0), FAST("Fast", -20.0, 1), TURBO("Turbo", -18.0, 2);

    companion object {
        fun forSubmode(submode: Int) = entries.firstOrNull { it.submode == submode } ?: NORMAL
    }
}

/**
 * Common antennas, as gain in dB relative to an average station's antenna at
 * each elevation angle, plus an efficiency loss per band for small radiators.
 * Rough textbook shapes: they mostly shift which distances a station favours
 * (low dipole: close in; vertical: far out). What the station actually does
 * is learned from its own log on top of this.
 */
enum class Antenna(val label: String, val detail: String, private val pattern: DoubleArray, private val lossDb: (Band) -> Double = { 0.0 }) {
    AVERAGE("Not set", "No adjustment for antenna", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    NVIS_DIPOLE("Low dipole (NVIS)", "Wire 3–6 m up: best within ~500 km", doubleArrayOf(-15.0, -10.0, -5.0, -2.0, 1.0, 2.5, 3.0)),
    HIGH_DIPOLE("Dipole, 10 m+ high", "Good all-rounder, regional to DX", doubleArrayOf(-6.0, -2.0, 2.0, 3.0, 2.0, 0.0, -3.0)),
    END_FED("End-fed / inverted L", "EFHW or similar wire", doubleArrayOf(-10.0, -6.0, -2.0, 0.0, 0.5, 0.5, 0.0), { -1.0 }),
    VERTICAL("Vertical with radials", "Weak close in, good long range", doubleArrayOf(-2.0, 0.0, 1.0, 0.0, -2.0, -6.0, -15.0)),
    MOBILE_WHIP("Mobile whip", "Vehicle or manpack whip", doubleArrayOf(-2.0, 0.0, 1.0, 0.0, -2.0, -6.0, -12.0), { b -> -whipLoss(b) }),
    MAG_LOOP("Small magnetic loop", "Compact loop, lossy on low bands", doubleArrayOf(-3.0, -1.0, 0.0, 0.0, 0.0, -1.0, -3.0), { b -> -loopLoss(b) }),
    RANDOM_WIRE("Random wire / compromise", "Short or improvised wire", doubleArrayOf(-13.0, -9.0, -6.0, -4.0, -3.0, -3.0, -3.0)),
    BEAM("Beam (pointed at them)", "Yagi or similar, aimed", doubleArrayOf(3.0, 5.0, 6.0, 5.0, 3.0, -2.0, -5.0));

    /** Gain (dB) at [elevationDeg] on [band], relative to an average station. */
    fun gainDb(elevationDeg: Double, band: Band): Double {
        val e = elevationDeg.coerceIn(ELEVATIONS.first(), ELEVATIONS.last())
        var i = 1
        while (i < ELEVATIONS.size - 1 && e > ELEVATIONS[i]) i++
        val f = (e - ELEVATIONS[i - 1]) / (ELEVATIONS[i] - ELEVATIONS[i - 1])
        return pattern[i - 1] + (pattern[i] - pattern[i - 1]) * f + lossDb(band)
    }

    companion object {
        /** Elevation angles (degrees) the patterns are given at. */
        val ELEVATIONS = doubleArrayOf(5.0, 10.0, 20.0, 30.0, 45.0, 60.0, 90.0)
    }
}

/** Efficiency loss (dB) of a short mobile whip. */
private fun whipLoss(b: Band) = when (b) {
    Band.B160 -> 25.0; Band.B80 -> 18.0; Band.B60 -> 14.0; Band.B40 -> 10.0; Band.B30 -> 8.0
    Band.B20 -> 5.0; Band.B17 -> 4.0; Band.B15 -> 3.0; Band.B12 -> 3.0; Band.B10 -> 2.0
}

private fun loopLoss(b: Band) = when (b) {
    Band.B160 -> 18.0; Band.B80 -> 12.0; Band.B60 -> 9.0; Band.B40 -> 6.0; Band.B30 -> 4.0
    Band.B20 -> 2.0; else -> 1.0
}

/** What this station brings to a path. */
data class StationProfile(
    val powerW: Double = 20.0,
    /** null = follow the transmit speed set on the Radio/Traffic screens. */
    val speedOverride: Js8Speed? = null,
    val antenna: Antenna = Antenna.AVERAGE,
    val adjust: Boolean = true,
    val learn: Boolean = true,
    /** Transmit speed currently set, used when there is no override. */
    val txSpeed: Js8Speed = Js8Speed.NORMAL,
) {
    val speed: Js8Speed get() = speedOverride ?: txSpeed

    companion object {
        private const val KEY_POWER = "station_power_w"
        private const val KEY_SPEED = "station_speed"
        private const val KEY_ANTENNA = "station_antenna"
        private const val KEY_ADJUST = "station_adjust"
        private const val KEY_LEARN = "station_learn"
        private const val KEY_TX_SUBMODE = "tx_submode"

        fun load(ctx: Context): StationProfile {
            val p = PreferenceManager.getDefaultSharedPreferences(ctx)
            return StationProfile(
                powerW = p.getFloat(KEY_POWER, 20f).toDouble(),
                speedOverride = p.getString(KEY_SPEED, null)?.let { runCatching { Js8Speed.valueOf(it) }.getOrNull() },
                antenna = p.getString(KEY_ANTENNA, null)?.let { runCatching { Antenna.valueOf(it) }.getOrNull() } ?: Antenna.AVERAGE,
                adjust = p.getBoolean(KEY_ADJUST, true),
                learn = p.getBoolean(KEY_LEARN, true),
                txSpeed = Js8Speed.forSubmode(p.getInt(KEY_TX_SUBMODE, 0)),
            )
        }

        fun save(ctx: Context, s: StationProfile) {
            PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putFloat(KEY_POWER, s.powerW.toFloat())
                .putString(KEY_SPEED, s.speedOverride?.name)
                .putString(KEY_ANTENNA, s.antenna.name)
                .putBoolean(KEY_ADJUST, s.adjust)
                .putBoolean(KEY_LEARN, s.learn)
                .apply()
        }
    }
}

/**
 * Turns a station profile into a change in the predicted score.
 *
 * The model's scores describe an average WSPR station (about 0.5 W, decoding
 * down to -28 dB). From ~950,000 WSPR spots: raising the decode threshold
 * keeps the share of paths in [RETAINED]; below the WSPR floor, paths are
 * spread at about 4.4 % (of those above it) per dB. A station with more
 * margin than that reference (more power, a slower mode, more antenna gain at
 * the right angle) hears and is heard on proportionally more paths.
 */
object StationModel {
    const val REF_DBM = 27.0
    const val WSPR_FLOOR_DB = -28.0
    private val THRESHOLDS = doubleArrayOf(-28.0, -26.0, -24.0, -22.0, -20.0, -18.0, -16.0, -14.0, -10.0, -5.0, 0.0)
    private val RETAINED = doubleArrayOf(1.0, 0.953, 0.878, 0.79, 0.702, 0.615, 0.532, 0.454, 0.316, 0.18, 0.09)
    private const val BELOW_FLOOR_PER_DB = 0.044
    private const val R = 6371.0
    private const val LAYER_KM = 300.0

    /** Share of the reference station's paths kept with a decode threshold of [thresholdDb]. */
    fun retained(thresholdDb: Double): Double {
        if (thresholdDb <= WSPR_FLOOR_DB) return 1 + BELOW_FLOOR_PER_DB * (WSPR_FLOOR_DB - thresholdDb)
        for (i in 1 until THRESHOLDS.size) {
            if (thresholdDb <= THRESHOLDS[i]) {
                val f = (thresholdDb - THRESHOLDS[i - 1]) / (THRESHOLDS[i] - THRESHOLDS[i - 1])
                return RETAINED[i - 1] + (RETAINED[i] - RETAINED[i - 1]) * f
            }
        }
        return RETAINED.last()
    }

    /** Take-off angle (degrees) for a hop of [hopKm] off a layer at ~300 km. */
    fun elevationDeg(hopKm: Double): Double {
        if (hopKm < 1) return 90.0
        val theta = hopKm / (2 * R)
        val el = atan((cos(theta) - R / (R + LAYER_KM)) / sin(theta))
        return Math.toDegrees(el).coerceIn(0.0, 90.0)
    }

    /** Signal margin (dB) of this station over the reference on [path] and [band]. */
    fun marginDb(s: StationProfile, path: PropagationModel.Path, band: Band): Double {
        val powerDbm = 10 * log10(max(0.001, s.powerW) * 1000)
        val el = elevationDeg(path.distanceKm / path.hops)
        return (powerDbm - REF_DBM) + s.antenna.gainDb(el, band) - (s.speed.thresholdDb - WSPR_FLOOR_DB)
    }

    /** Factor applied to the predicted score for a margin of [marginDb]. */
    fun multiplier(marginDb: Double): Double = retained(WSPR_FLOOR_DB - marginDb).coerceIn(0.05, 3.0)

    fun adjust(score: Double, s: StationProfile, path: PropagationModel.Path, band: Band): Double =
        min(1.0, score * multiplier(marginDb(s, path, band)))
}
