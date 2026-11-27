package com.js8call.voacap

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.log10

/**
 * VOACAP point-to-point predictions (issue #57), from the hfcast Rust port of VOACAP compiled into
 * this library with its coefficients, so it works with no network and no data files.
 *
 * VOACAP is the Voice of America Coverage Analysis Program, built by NTIA/ITS on IONCAP and
 * maintained by Greg Hand; the Linux port voacapl is by J.A. Watson; hfcast, the Rust port used
 * here, is by Jonathan McSweet (Apache-2.0). No warranty is made, and no endorsement by NTIA/ITS
 * or the US Government is implied.
 *
 * One [predict] call covers a whole day (24 UTC hours) for up to [MAX_BANDS] frequencies and
 * takes milliseconds. Call it off the main thread anyway.
 */
object Voacap {

    /** The engine's limit on frequencies per run. */
    const val MAX_BANDS = 11

    /**
     * VOACAP's signal-to-noise is in a 1 Hz bandwidth; JS8 (like WSJT-X) reports it in 2500 Hz.
     * Subtract this from a VOACAP SNR to compare it with a JS8 SNR.
     */
    val JS8_BANDWIDTH_DB: Double = 10.0 * log10(2500.0)

    /** Man-made noise at 3 MHz, dBW below 1 W in 1 Hz (VOACAP's standard environments). */
    enum class Noise(val dbw: Double) {
        INDUSTRIAL(140.0), RESIDENTIAL(145.0), RURAL(150.0), REMOTE(163.6)
    }

    data class Request(
        val txLat: Double,
        val txLon: Double,
        val rxLat: Double,
        val rxLon: Double,
        /** 1 to 12. */
        val month: Int,
        val year: Int,
        /** Smoothed sunspot number for the month. */
        val ssn: Double,
        val watts: Double,
        val bandsMhz: List<Double>,
        /** The weakest JS8 SNR (2500 Hz) the mode decodes, e.g. -24 for Normal. */
        val js8ThresholdDb: Double,
        val noise: Noise = Noise.RESIDENTIAL,
    ) {
        init {
            require(txLat in -90.0..90.0 && rxLat in -90.0..90.0) { "latitude out of range" }
            require(txLon in -180.0..180.0 && rxLon in -180.0..180.0) { "longitude out of range" }
            require(month in 1..12) { "month must be 1 to 12" }
            require(ssn in 0.0..400.0) { "sunspot number out of range" }
            require(watts > 0.0 && watts <= 10_000_000.0) { "power out of range" }
            require(bandsMhz.isNotEmpty() && bandsMhz.size <= MAX_BANDS) { "1 to $MAX_BANDS frequencies" }
            require(bandsMhz.all { it in 1.0..60.0 }) { "frequencies must be 1 to 60 MHz" }
        }

        /** The request as the engine reads it (hfcast's service format). ASCII only. */
        fun toJson(): String {
            fun n(v: Double) = String.format(Locale.US, "%.4f", v)
            val bands = bandsMhz.joinToString(",") { n(it) }
            return "{\"fromLat\":${n(txLat)},\"fromLon\":${n(txLon)},\"toLat\":${n(rxLat)},\"toLon\":${n(rxLon)}," +
                "\"month\":$month,\"year\":$year,\"ssn\":${n(ssn)},\"watts\":${n(watts)},\"bands\":[$bands]," +
                "\"requiredSnrDb\":${n(js8ThresholdDb + JS8_BANDWIDTH_DB)},\"noiseDbw\":${n(noise.dbw)}}"
        }
    }

    /** One UTC hour on one frequency. */
    data class Cell(
        val hourUtc: Int,
        val freqMhz: Double,
        /** Chance (0..1) that the SNR reaches the mode's threshold on a given day of the month. */
        val reliability: Double,
        /** Median SNR in JS8 terms (2500 Hz bandwidth). */
        val js8Snr: Double,
        /** Take-off angle of the predicted mode, degrees (null if not given). */
        val takeoffDeg: Double?,
    )

    data class Prediction(
        val cells: List<Cell>,
        /** Per UTC hour 0..23, MHz; negative or zero where the engine gives none. */
        val mufMhz: DoubleArray,
        val fotMhz: DoubleArray,
        val lufMhz: DoubleArray,
        /** Which model stands behind the numbers ("voacap"). */
        val engine: String,
    ) {
        fun cell(hourUtc: Int, freqMhz: Double): Cell? =
            cells.firstOrNull { it.hourUtc == hourUtc && kotlin.math.abs(it.freqMhz - freqMhz) < 0.005 }
    }

    @Volatile private var loadError: Throwable? = null
    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("js8voacap"); true
        } catch (t: Throwable) {
            loadError = t; false
        }
    }

    /** False when the native library couldn't be loaded on this device. */
    fun available(): Boolean = loaded

    /** Runs one prediction. Throws [IllegalArgumentException] for a request the engine refuses. */
    fun predict(request: Request): Prediction {
        check(loaded) { "VOACAP engine not available: ${loadError?.message}" }
        return parse(nativeRun(request.toJson()))
    }

    internal fun parse(json: String): Prediction {
        val o = JSONObject(json)
        val cells = o.getJSONArray("cells").let { a ->
            (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                Cell(
                    hourUtc = c.getInt("hour"),
                    freqMhz = c.getDouble("freqMhz"),
                    reliability = c.getDouble("reliability").coerceIn(0.0, 1.0),
                    js8Snr = c.getDouble("snr") - JS8_BANDWIDTH_DB,
                    takeoffDeg = c.optDouble("takeoffAngleDeg").takeUnless { it.isNaN() },
                )
            }
        }
        fun hours(key: String): DoubleArray {
            val a: JSONArray = o.optJSONArray(key) ?: return DoubleArray(24) { -1.0 }
            return DoubleArray(24) { h -> if (h < a.length()) a.optDouble(h, -1.0) else -1.0 }
        }
        return Prediction(cells, hours("mufByHour"), hours("fotByHour"), hours("lufByHour"), o.optString("engine", "voacap"))
    }

    @JvmStatic private external fun nativeRun(request: String): String
}
