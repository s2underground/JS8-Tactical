package com.js8call.example.interference

import com.js8call.example.interference.InterferenceDetector.Kind
import com.js8call.example.interference.InterferenceDetector.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln

/**
 * Synthetic spectra shaped like JS8Core's (6827 linear-power bins from 0 to 6 kHz, 10 per
 * second): band noise, JS8-like signals, and the four kinds of interference.
 */
class InterferenceDetectorTest {

    private val dial = 7_078_000L
    private val binHz = 6000f / 6827f
    private val rnd = Random(42)
    private var now = 1_795_521_600_000L // 2026-11-24 12:00 UTC, on a 15 s boundary

    /** Noise with mean [level] (linear) in every bin; [extra] adds signals at time [t] (ms into the run). */
    private fun frame(level: Float, extra: (bins: FloatArray, t: Long) -> Unit = { _, _ -> }): FloatArray {
        // Only the part the detector reads (to 2.8 kHz) is filled, to keep the tests quick.
        val b = FloatArray(6827)
        for (i in 0 until 3200) b[i] = (-ln(1.0 - rnd.nextDouble())).toFloat() * level
        extra(b, now)
        return b
    }

    private fun addTone(b: FloatArray, hz: Float, power: Float) {
        val i = (hz / binHz).toInt()
        for (k in i - 2..i + 2) b[k] += power
    }

    /** [seconds] of spectrum at 10 frames a second; decodes at [snr] every [decodeEvery] s (0 = none). */
    private fun InterferenceDetector.run(
        seconds: Int, level: Float = 1f, decodeEvery: Int = 0, snr: Int = -12,
        extra: (FloatArray, Long) -> Unit = { _, _ -> },
    ) {
        repeat(seconds * 10) { i ->
            onSpectrum(now, dial, frame(level, extra), binHz)
            if (decodeEvery > 0 && i % (decodeEvery * 10) == 5) onDecode(now, dial, snr)
            now += 100
        }
    }

    /** A JS8-like signal: 8-FSK, 6.25 Hz tones from [baseHz], 12.64 s on in every 15 s slot. */
    private fun js8(baseHz: Float, power: Float) = { b: FloatArray, t: Long ->
        val inSlot = t % 15_000
        if (inSlot < 12_640) {
            val symbol = ((inSlot / 160) * 2654435761L % 8).toInt()
            addTone(b, baseHz + symbol * 6.25f, power)
        }
    }

    /** 20 minutes of a normal band: noise, a JS8 station decoded every 15 s. */
    private fun InterferenceDetector.learnNormal() = run(20 * 60, decodeEvery = 15, extra = js8(1200f, 60f))

    @Test
    fun normalBandFlagsNothingAndLearnsABaseline() {
        val d = InterferenceDetector()
        d.learnNormal()
        val a = d.assessment()
        assertEquals(Level.NONE, a.level)
        assertTrue(a.baselineReady)
        assertTrue(a.kinds.isEmpty())
    }

    @Test
    fun raisedFloorWithStationsGoneSilentIsLikelyNoise() {
        val d = InterferenceDetector()
        d.learnNormal()
        d.run(6 * 60, level = 25f) // ~14 dB up; nothing decodes
        val a = d.assessment()
        assertTrue(a.kinds.toString(), Kind.NOISE in a.kinds)
        assertEquals(Level.LIKELY, a.level)
        assertTrue(a.decodesQuiet)
        assertTrue(a.affectsTraffic)
        assertTrue("${a.noiseAboveDb}", (a.noiseAboveDb ?: 0f) in 11f..17f)
    }

    @Test
    fun raisedFloorWhileStationsStillDecodeNormallyIsOnlyPossible() {
        // What a turned-up audio gain looks like: everything louder, the same stations at the same SNR.
        val d = InterferenceDetector()
        d.learnNormal()
        d.run(6 * 60, level = 25f, decodeEvery = 15, extra = js8(1200f, 1500f))
        val a = d.assessment()
        assertEquals(Level.POSSIBLE, a.level)
        assertFalse(a.affectsTraffic)
    }

    @Test
    fun steadyCarrierIsFoundAtItsOffset() {
        val d = InterferenceDetector()
        d.learnNormal()
        d.run(2 * 60, decodeEvery = 15) { b, _ -> addTone(b, 1612f, 200f) }
        val a = d.assessment()
        assertTrue(a.kinds.toString(), Kind.CARRIER in a.kinds)
        assertEquals(Level.LIKELY, a.level)
        assertTrue(a.carriersHz.toString(), a.carriersHz.any { abs(it - 1612) <= 25 })
        assertTrue(a.carrierNear(1600f))
        assertFalse(a.carrierNear(1000f))
        assertFalse("a tone alone leaves the rest of the passband usable", a.affectsTraffic)
    }

    @Test
    fun busyJs8TrafficIsNotACarrier() {
        val d = InterferenceDetector()
        d.run(10 * 60, decodeEvery = 15) { b, t -> js8(900f, 400f)(b, t); js8(1500f, 400f)(b, t); js8(2100f, 80f)(b, t) }
        assertEquals(Level.NONE, d.assessment().level)
    }

    @Test
    fun aToneThatIsAlwaysThereBecomesResident() {
        val d = InterferenceDetector()
        d.run(100 * 60, decodeEvery = 15) { b, _ -> addTone(b, 2200f, 200f) }
        val a = d.assessment()
        assertTrue(a.carriersHz.toString(), a.carriersHz.isEmpty())
        assertTrue(a.residentCarriersHz.toString(), a.residentCarriersHz.any { abs(it - 2200) <= 25 })
        assertEquals(Level.NONE, a.level)
    }

    @Test
    fun sweepIsFound() {
        val d = InterferenceDetector()
        d.learnNormal()
        // A tone sliding 400 → 2600 Hz every 2 seconds.
        d.run(2 * 60) { b, t -> addTone(b, 400f + 2200f * ((t % 2000) / 2000f), 300f) }
        val a = d.assessment()
        assertTrue(a.kinds.toString(), Kind.SWEEP in a.kinds)
        assertTrue(a.affectsTraffic)
    }

    @Test
    fun pulsedNoiseIsFound() {
        val d = InterferenceDetector()
        d.learnNormal()
        // The whole band 15 dB up for half a second, off for half a second.
        d.run(2 * 60) { b, t -> if ((t / 500) % 2 == 0L) for (i in 0 until 3200) b[i] *= 30f }
        val a = d.assessment()
        assertTrue(a.kinds.toString(), Kind.PULSE in a.kinds)
    }

    @Test
    fun ownTransmissionIsIgnored() {
        val d = InterferenceDetector()
        d.learnNormal()
        // A minute of our own loud signal: muted, so nothing is flagged.
        repeat(60 * 10) {
            d.onTransmit(now)
            d.onSpectrum(now, dial, frame(1f) { b, _ -> for (i in 0 until 3200) b[i] *= 1000f }, binHz)
            now += 100
        }
        d.run(30, decodeEvery = 15, extra = js8(1200f, 60f))
        assertEquals(Level.NONE, d.assessment().level)
    }

    @Test
    fun changingDialStartsOver() {
        val d = InterferenceDetector()
        d.learnNormal()
        d.run(2 * 60) { b, _ -> addTone(b, 1612f, 200f) }
        assertEquals(Level.LIKELY, d.assessment().level)
        d.onSpectrum(now, 14_078_000L, frame(1f), binHz)
        assertEquals(Level.NONE, d.assessment().level)
        assertFalse("no baseline for the new dial yet", d.assessment().baselineReady)
    }

    @Test
    fun noBaselineMeansNoNoiseJudgement() {
        val d = InterferenceDetector()
        d.run(3 * 60, level = 25f)
        val a = d.assessment()
        assertFalse(a.baselineReady)
        assertFalse(Kind.NOISE in a.kinds)
    }

    @Test
    fun baselineSurvivesARoundTrip() {
        val d = InterferenceDetector()
        d.learnNormal()
        val text = BaselineText.write(d.baselines)
        val back = BaselineText.read(text)
        val a = d.baselines[7078]!!
        val b = back[7078]!!
        val h = InterferenceDetector.hourOf(now)
        assertEquals(a.floorDb[h], b.floorDb[h], 0.01f)
        assertEquals(a.floorN[h], b.floorN[h])
        assertEquals(a.decodesPerWindow[h], b.decodesPerWindow[h], 0.01f)
        assertTrue(b.ready(h))
        assertTrue(BaselineText.read("garbage\nmore garbage").isEmpty())
    }
}
