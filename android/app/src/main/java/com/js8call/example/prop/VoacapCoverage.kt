package com.js8call.example.prop

import com.js8call.example.reports.LatLon
import com.js8call.voacap.Voacap
import java.util.Locale

/**
 * The Prop tab's maps and band chart from VOACAP (issue #57, the default model since the
 * comparison in tools/propdata/README.md): VOACAP's area method, every band at once, one UTC hour
 * per run, kept per hour so moving the time slider back over an hour is instant.
 *
 * Each hour is two runs: the whole world on a 3° grid, and 0.5° within 12° of the station, where
 * the skip zone and short paths change quickly. VOACAP is a monthly median for isotropic antennas;
 * it does not learn from this station's log (that is what the Prop tab's own model is for).
 */
class VoacapCoverage {

    /** Everything a map depends on except the hour. */
    data class Inputs(
        val me: LatLon,
        val month: Int,
        val year: Int,
        val ssn: Double,
        val watts: Double,
        val thresholdDb: Double,
        val noise: Voacap.Noise,
    ) {
        val key: String = String.format(
            Locale.US, "%.2f|%.2f|%d|%d|%.0f|%.2f|%.1f|%s", me.lat, me.lon, month, year, ssn, watts, thresholdDb, noise.name,
        )
    }

    /** One hour's maps. Band indexes follow [Band.entries]. */
    class Maps(val world: Voacap.AreaGrid, val near: Voacap.AreaGrid?, private val nearBox: DoubleArray?) {
        fun sample(lat: Double, lon: Double, band: Int): Float {
            val b = nearBox
            if (near != null && b != null && lat >= b[0] && lat <= b[1] && lon >= b[2] && lon <= b[3]) {
                return near.sample(lat, lon, band)
            }
            return world.sample(lat, lon, band)
        }
    }

    private val cache = object : LinkedHashMap<String, Maps>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Maps>?) = size > MAX_HOURS
    }

    @Synchronized fun cached(inputs: Inputs, hourUtc: Int): Maps? = cache["${inputs.key}|$hourUtc"]

    /** The maps for [hourUtc], from the cache or computed now (a second or more: call off the main thread). */
    fun maps(inputs: Inputs, hourUtc: Int): Maps {
        cached(inputs, hourUtc)?.let { return it }
        val world = Voacap.area(request(inputs, hourUtc, WORLD_STEP_DEG, null))
        val box = nearBox(inputs.me)
        val near = runCatching { Voacap.area(request(inputs, hourUtc, NEAR_STEP_DEG, box)) }.getOrNull()
        val m = Maps(world, near, box)
        synchronized(this) { cache["${inputs.key}|$hourUtc"] = m }
        return m
    }

    /** A sharper run for the area on screen. */
    fun detail(inputs: Inputs, hourUtc: Int, south: Double, north: Double, west: Double, east: Double, stepDeg: Double): Voacap.AreaGrid =
        Voacap.area(request(inputs, hourUtc, stepDeg, doubleArrayOf(south, north, west, east)))

    /** Average over 12 bearings per distance bucket: the band chart row, like [Predictor.stoplight]. */
    fun stoplight(m: Maps, me: LatLon, band: Int, bucketsKm: List<Double> = PropagationModel.CHART_BUCKETS_KM): List<Double> =
        bucketsKm.map { km ->
            (0 until 12).map { i ->
                val p = PropagationModel.destination(me, i * 30.0, km)
                m.sample(p.lat, p.lon, band).toDouble()
            }.average()
        }

    fun coverage(m: Maps, band: Int, width: Int, height: Int, north: Double, south: Double, west: Double, east: Double): FloatArray =
        PropagationModel.grid(width, height, north, south, west, east) { p -> m.sample(p.lat, p.lon, band).toDouble() }

    private fun request(inputs: Inputs, hourUtc: Int, step: Double, box: DoubleArray?) = Voacap.AreaRequest(
        txLat = inputs.me.lat, txLon = inputs.me.lon, month = inputs.month, year = inputs.year,
        ssn = inputs.ssn, watts = inputs.watts, bandsMhz = Band.entries.map { it.mhz },
        js8ThresholdDb = inputs.thresholdDb, noise = inputs.noise, hourUtc = hourUtc, stepDeg = step, box = box,
    )

    companion object {
        const val WORLD_STEP_DEG = 3.0
        const val NEAR_STEP_DEG = 0.5
        const val NEAR_SPAN_DEG = 12.0
        /** Hours kept (each about 100 KB). */
        const val MAX_HOURS = 8

        fun nearBox(me: LatLon) = doubleArrayOf(
            (me.lat - NEAR_SPAN_DEG).coerceAtLeast(-89.0), (me.lat + NEAR_SPAN_DEG).coerceAtMost(89.0),
            (me.lon - NEAR_SPAN_DEG).coerceAtLeast(-180.0), (me.lon + NEAR_SPAN_DEG).coerceAtMost(180.0),
        )
    }
}
