package com.js8call.sdr

/**
 * An Airspy HF+ (Discovery or Dual Port) as a JS8 receiver: libairspyhf (vendor/airspyhf,
 * BSD-3-Clause), opened from Android's USB file descriptor with the library's own
 * airspyhf_open_fd(). 192 kS/s IQ in, the shared SSB receiver, 12 kHz audio out. Receive only.
 * Its HF AGC and IQ correction run in the device and library; no settings are needed.
 */
class AirspyHf private constructor(private var handle: Long) : SdrReceiver {

    override fun describe(): String = if (handle != 0L) nativeDescribe(handle) else ""

    /** Direct sampling, ppm and gain are RTL-SDR settings: the Airspy ignores them. */
    override fun tune(dialHz: Long, directSampling: Int, ppm: Int, gainTenthsDb: Int): Int =
        if (handle != 0L) nativeTune(handle, dialHz) else -1

    override fun run(listener: SdrReceiver.AudioListener): Int = if (handle != 0L) nativeRun(handle, listener) else -1

    override fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeClose(h)
    }

    companion object {
        /** USB vendor/product id of the Airspy HF+ family (libairspyhf). */
        const val VENDOR_ID = 0x03EB
        const val PRODUCT_ID = 0x800C

        val available: Boolean by lazy { runCatching { System.loadLibrary("js8sdr") }.isSuccess }

        fun isKnown(vendorId: Int, productId: Int) = vendorId == VENDOR_ID && productId == PRODUCT_ID

        /** Opens the Airspy behind an open UsbDeviceConnection's file descriptor, or null. */
        fun open(fd: Int): AirspyHf? {
            if (!available || fd < 0) return null
            val h = nativeOpen(fd)
            return if (h != 0L) AirspyHf(h) else null
        }

        @JvmStatic private external fun nativeOpen(fd: Int): Long
        @JvmStatic private external fun nativeDescribe(h: Long): String
        @JvmStatic private external fun nativeTune(h: Long, dialHz: Long): Int
        @JvmStatic private external fun nativeRun(h: Long, listener: SdrReceiver.AudioListener): Int
        @JvmStatic private external fun nativeCancel(h: Long)
        @JvmStatic private external fun nativeClose(h: Long)
    }
}
