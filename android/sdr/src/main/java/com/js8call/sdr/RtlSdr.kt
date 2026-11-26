package com.js8call.sdr

import java.io.Closeable

/**
 * An RTL-SDR dongle as a JS8 receiver (issue #54): opened from the file descriptor Android's
 * UsbManager gives the app, tuned for a dial frequency, streaming 12 kHz upper-sideband audio.
 * Receive only: these dongles have no transmitter.
 *
 * Threading: [run] blocks its thread while streaming; [tune] and [cancel] may be called from
 * other threads meanwhile; [close] only once [run] has returned.
 */
class RtlSdr private constructor(private var handle: Long) : Closeable {

    /** Called on the streaming thread with [count] samples at [SAMPLE_RATE]. The array is reused. */
    fun interface AudioListener {
        fun onAudio(samples: ShortArray, count: Int)
    }

    /** "RTLSDRBlog Blog V4, tuner R828D", from the dongle itself. */
    fun describe(): String = if (handle != 0L) nativeDescribe(handle) else ""

    /** True for an RTL-SDR Blog V4: HF through its built-in upconverter, no direct sampling. */
    val hasUpconverter: Boolean get() = handle != 0L && nativeHasUpconverter(handle)

    /** Tuner gain steps in tenths of a dB (empty in direct sampling or if unknown). */
    fun gains(): IntArray = if (handle != 0L) nativeGains(handle) else IntArray(0)

    /**
     * Tunes for [dialHz] (upper sideband, like the radio's dial). [directSampling]: one of
     * the DS_ constants. [ppm]: frequency correction. [gainTenthsDb]: tuner gain, or -1 for
     * automatic. Returns 0, or a negative step code (-1 can't tune there, -2 sampling mode,
     * -3 frequency).
     */
    fun tune(dialHz: Long, directSampling: Int = DS_AUTO, ppm: Int = 0, gainTenthsDb: Int = -1): Int =
        if (handle != 0L) nativeTune(handle, dialHz, directSampling, ppm, gainTenthsDb) else -1

    /** Streams audio to [listener] until [cancel] or the dongle is unplugged. Blocks. */
    fun run(listener: AudioListener): Int = if (handle != 0L) nativeRun(handle, listener) else -1

    fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeClose(h)
    }

    companion object {
        const val SAMPLE_RATE = 12_000
        const val DS_AUTO = 0
        const val DS_OFF = 1
        const val DS_Q = 2
        const val DS_I = 3

        /** False if the native library isn't in this build for this CPU. */
        val available: Boolean by lazy { runCatching { System.loadLibrary("js8sdr") }.isSuccess }

        /** Opens the dongle behind an open UsbDeviceConnection's file descriptor, or null. */
        fun open(fd: Int): RtlSdr? {
            if (!available || fd < 0) return null
            val h = nativeOpen(fd)
            return if (h != 0L) RtlSdr(h) else null
        }

        /** USB vendor/product ids librtlsdr knows (vendor/rtl-sdr/src/librtlsdr.c, known_devices). */
        val KNOWN_DEVICES: Set<Pair<Int, Int>> = setOf(
            0x0bda to 0x2832, 0x0bda to 0x2838, 0x0413 to 0x6680, 0x0413 to 0x6f0f, 0x0458 to 0x707f,
            0x0ccd to 0x00a9, 0x0ccd to 0x00b3, 0x0ccd to 0x00b4, 0x0ccd to 0x00b5, 0x0ccd to 0x00b7,
            0x0ccd to 0x00b8, 0x0ccd to 0x00b9, 0x0ccd to 0x00c0, 0x0ccd to 0x00c6, 0x0ccd to 0x00d3,
            0x0ccd to 0x00d7, 0x0ccd to 0x00e0, 0x1554 to 0x5020, 0x15f4 to 0x0131, 0x15f4 to 0x0133,
            0x185b to 0x0620, 0x185b to 0x0650, 0x185b to 0x0680, 0x1b80 to 0xd393, 0x1b80 to 0xd394,
            0x1b80 to 0xd395, 0x1b80 to 0xd397, 0x1b80 to 0xd398, 0x1b80 to 0xd39d, 0x1b80 to 0xd3a4,
            0x1b80 to 0xd3a8, 0x1b80 to 0xd3af, 0x1b80 to 0xd3b0, 0x1d19 to 0x1101, 0x1d19 to 0x1102,
            0x1d19 to 0x1103, 0x1d19 to 0x1104, 0x1f4d to 0xa803, 0x1f4d to 0xb803, 0x1f4d to 0xc803,
            0x1f4d to 0xd286, 0x1f4d to 0xd803,
        )

        fun isKnown(vendorId: Int, productId: Int) = (vendorId to productId) in KNOWN_DEVICES

        @JvmStatic private external fun nativeOpen(fd: Int): Long
        @JvmStatic private external fun nativeDescribe(h: Long): String
        @JvmStatic private external fun nativeHasUpconverter(h: Long): Boolean
        @JvmStatic private external fun nativeGains(h: Long): IntArray
        @JvmStatic private external fun nativeTune(h: Long, dialHz: Long, ds: Int, ppm: Int, gainTenthsDb: Int): Int
        @JvmStatic private external fun nativeRun(h: Long, listener: AudioListener): Int
        @JvmStatic private external fun nativeCancel(h: Long)
        @JvmStatic private external fun nativeClose(h: Long)
    }
}
