package com.js8call.example.ui

import kotlin.math.log10
import kotlin.math.max

/**
 * Finds our own transmission in the receive spectrum, without needing to decode it.
 *
 * Some radios feed part of the transmit audio back to the sound card while keyed;
 * then the waterfall shows our signal. This compares the spectrum while we send
 * with the spectrum just before, and reports the [bandwidthHz]-wide stretch that
 * got louder the most, if it clearly stands out. That is where the receiver "saw"
 * our signal, to set against where we sent it.
 *
 * Feed it every spectrum frame with the offset currently on the air (null when not
 * sending). A [Reading] comes back once per transmission, after it ends.
 */
class OwnSignalProbe(
    private val maxHz: Float = 3_500f,
    /** How much louder (dB) the best stretch must get than the typical bin to count. */
    private val minRiseDb: Float = 8f,
) {
    data class Reading(val heardHz: Float, val sentHz: Float, val riseDb: Float, val riseAtSentDb: Float)

    private var baseline: FloatArray? = null
    private var txSum: FloatArray? = null
    private var txFrames = 0
    private var skipped = 0
    private var sentHz: Float? = null
    private var bandwidthHz = 50f
    private var binHz = 0f

    fun feed(bins: FloatArray, binHz: Float, onAirHz: Float?, bandwidthHz: Float): Reading? {
        if (binHz <= 0f || bins.isEmpty()) return null
        val n = minOf(bins.size, (maxHz / binHz).toInt() + 1)
        if (this.binHz != binHz || baseline?.size != n) {
            this.binHz = binHz
            baseline = null
            txSum = null
            txFrames = 0
        }
        val db = FloatArray(n) { 10f * log10(max(bins[it], 1e-12f)) }

        val finished = if (onAirHz == null || (sentHz != null && onAirHz != sentHz)) finish() else null
        if (onAirHz == null) {
            // Slow running average of the band while we listen.
            val b = baseline ?: db.copyOf().also { baseline = it }
            for (i in 0 until n) b[i] += 0.1f * (db[i] - b[i])
        } else {
            if (sentHz == null) {
                sentHz = onAirHz
                this.bandwidthHz = bandwidthHz
                skipped = 0
            }
            // Skip the first second or so: PTT and audio take a moment to come up.
            if (skipped < SKIP_FRAMES) { skipped++; return finished }
            val sum = txSum ?: FloatArray(n).also { txSum = it }
            for (i in 0 until n) sum[i] += db[i]
            txFrames++
        }
        return finished
    }

    private fun finish(): Reading? {
        val sent = sentHz
        val base = baseline
        val sum = txSum
        val frames = txFrames
        sentHz = null
        txSum = null
        txFrames = 0
        if (sent == null || base == null || sum == null || frames < MIN_FRAMES) return null
        val n = base.size
        val rise = FloatArray(n) { sum[it] / frames - base[it] }
        val w = max(1, (bandwidthHz / binHz).toInt())
        if (n <= w) return null
        // Mean rise over each bandwidth-wide stretch.
        val window = FloatArray(n - w)
        var acc = 0f
        for (i in 0 until w) acc += rise[i]
        for (i in 0 until n - w) {
            window[i] = acc / w
            acc += rise[i + w] - rise[i]
        }
        val typical = window.sortedArray()[window.size / 2]
        var best = 0
        for (i in window.indices) if (window[i] > window[best]) best = i
        val bestRise = window[best] - typical
        if (bestRise < minRiseDb) return null
        val atSent = (sent / binHz).toInt().coerceIn(0, window.size - 1)
        return Reading(best * binHz, sent, bestRise, window[atSent] - typical)
    }

    companion object {
        private const val SKIP_FRAMES = 6
        private const val MIN_FRAMES = 10
    }
}
