package com.js8call.sdr

import java.io.Closeable

/**
 * An SDR as a JS8 receiver (issue #54): RTL-SDR ([RtlSdr]) or Airspy HF+ ([AirspyHf]). Opened
 * from Android's USB file descriptor, tuned for a dial frequency (upper sideband), streaming
 * 12 kHz audio. Receive only.
 *
 * Threading: [run] blocks its thread while streaming; [tune] and [cancel] may be called from
 * other threads meanwhile; [close] only once [run] has returned.
 */
interface SdrReceiver : Closeable {

    /** Called on the streaming thread with [count] samples at [SAMPLE_RATE]. The array is reused. */
    fun interface AudioListener {
        fun onAudio(samples: ShortArray, count: Int)
    }

    /** The device as it names itself (model, tuner or firmware). */
    fun describe(): String

    /**
     * Tunes for [dialHz]. [directSampling], [ppm] and [gainTenthsDb] apply to an RTL-SDR (see
     * [RtlSdr.tune]) and are ignored by devices that don't need them. Returns 0, or a negative
     * step code (-1 can't tune there, -2 sampling mode, -3 frequency).
     */
    fun tune(dialHz: Long, directSampling: Int = RtlSdr.DS_AUTO, ppm: Int = 0, gainTenthsDb: Int = -1): Int

    /** Streams audio to [listener] until [cancel] or the device is unplugged. Blocks. */
    fun run(listener: AudioListener): Int

    fun cancel()

    companion object {
        const val SAMPLE_RATE = 12_000
    }
}
