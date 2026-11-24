package com.js8call.example.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.js8call.example.rollup.HeaderParser
import com.js8call.example.rollup.RollupEngine

/**
 * Message rollup: turns the stream of decoded frames (anything anyone transmits) into messages.
 * Feeds it arbitrary frames — odd times, offsets, flags, submodes, text — and reads every
 * derived property the Traffic screen shows. Checks it never crashes and never builds a message
 * longer than its frame limit.
 */
object RollupFuzz {
    private const val MAX_FRAMES = 64

    @JvmStatic
    fun fuzzerTestOneInput(d: FuzzedDataProvider) {
        val engine = RollupEngine(maxFramesPerTransmission = MAX_FRAMES)
        var t = d.consumeLong(0, 4_000_000_000_000L)
        repeat(d.consumeInt(0, 40)) {
            t += d.consumeLong(-5_000, 120_000)
            val frame = RollupEngine.Frame(
                utc = d.consumeInt(-1, 240000),
                frequencyHz = if (d.consumeBoolean()) d.pickValue(floatArrayOf(1000f, 1005f, 1500f, 2500f).toTypedArray()) else d.consumeRegularFloat(-10f, 5000f),
                type = d.consumeInt(0, 7),
                text = d.consumeString(24),
                snr = d.consumeInt(-30, 30),
                mode = d.consumeInt(-1, 5),
                receivedAtMs = t,
            )
            engine.addFrame(frame)
            if (d.consumeBoolean()) engine.tick(t + d.consumeLong(0, 600_000))
        }
        if (d.consumeBoolean()) engine.prune(t - d.consumeLong(0, 3_600_000))

        for (m in engine.messages()) {
            check(m.frames.size <= MAX_FRAMES) { "message with ${m.frames.size} frames" }
            m.sender; m.recipient; m.bodyText; m.segments; m.isComplete; m.missingFrames; m.bestSnr
        }
        engine.live().forEach { it.textSoFar }
        engine.threads()

        HeaderParser.parse(d.consumeRemainingAsString())?.let { h ->
            check(h.headerLength >= 0) { "negative header length" }
        }
    }
}
