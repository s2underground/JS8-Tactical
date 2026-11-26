package com.js8call.example.service

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.js8call.sdr.RtlSdr
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An RTL-SDR dongle as the receiver (issue #54): finds it on USB, asks for permission, opens it
 * through the app's USB file descriptor, tunes it for the dial, and streams 12 kHz audio to the
 * engine. Receive only. The radio "rig type" is rtl_sdr; the service treats it like the UV-Pro
 * (audio from the session, no microphone capture).
 *
 * [start] and [stop] block; call them off the main thread.
 */
class RtlSdrSession(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** 12 kHz audio; the array is reused after the call returns. */
        fun onRxAudio(samples: ShortArray, count: Int, sampleRateHz: Int)
        /** The dongle stopped on its own (unplugged, USB error). */
        fun onLost(message: String)
    }

    /** Settings > Radio > RTL-SDR. */
    data class Settings(
        val directSampling: Int = RtlSdr.DS_AUTO,
        val ppm: Int = 0,
        /** Tuner gain in tenths of a dB, or -1 for automatic. */
        val gainTenthsDb: Int = -1,
    )

    @Volatile var lastError: String? = null
        private set
    @Volatile var description: String = ""
        private set

    private var connection: UsbDeviceConnection? = null
    private var sdr: RtlSdr? = null
    private var thread: Thread? = null
    @Volatile private var stopping = false
    private val lock = Any()

    /** Finds, opens and tunes the dongle, then starts streaming. False with [lastError] set on failure. */
    fun start(dialHz: Long, settings: Settings): Boolean {
        stopping = false
        lastError = null
        if (!RtlSdr.available) return fail("RTL-SDR support isn't available on this device (CPU type).")
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.firstOrNull { RtlSdr.isKnown(it.vendorId, it.productId) }
            ?: return fail("No RTL-SDR found. Plug it in (with a USB OTG adapter if needed) and try again.")
        if (!usb.hasPermission(device) && !askPermission(device)) {
            return fail("USB permission for the RTL-SDR was not given.")
        }
        val conn = usb.openDevice(device) ?: return fail("Android couldn't open the RTL-SDR.")
        // Take the interface from any kernel driver; librtlsdr then claims it on the same descriptor.
        if (device.interfaceCount > 0) conn.claimInterface(device.getInterface(0), true)
        val opened = RtlSdr.open(conn.fileDescriptor)
        if (opened == null) {
            conn.close()
            return fail("The RTL-SDR didn't respond. Unplug it, plug it back in, and try again.")
        }
        description = opened.describe()
        Log.i(TAG, "RTL-SDR opened: $description${if (opened.hasUpconverter) " (HF upconverter)" else ""}")
        val r = opened.tune(dialHz, settings.directSampling, settings.ppm, settings.gainTenthsDb)
        if (r < 0) {
            opened.close()
            conn.close()
            return fail(tuneError(r, dialHz))
        }
        synchronized(lock) {
            connection = conn
            sdr = opened
            thread = Thread({ stream(opened) }, "rtl-sdr-rx").apply { start() }
        }
        return true
    }

    /** Retunes while streaming. False if the dongle can't be tuned there. */
    fun tune(dialHz: Long, settings: Settings): Boolean {
        val s = synchronized(lock) { sdr } ?: return false
        val r = s.tune(dialHz, settings.directSampling, settings.ppm, settings.gainTenthsDb)
        if (r < 0) Log.w(TAG, tuneError(r, dialHz))
        return r >= 0
    }

    fun isRunning(): Boolean = synchronized(lock) { thread?.isAlive == true }

    fun stop() {
        stopping = true
        val (s, t, c) = synchronized(lock) {
            Triple(sdr, thread, connection).also { sdr = null; thread = null; connection = null }
        }
        s?.cancel()
        t?.join(STOP_WAIT_MS)
        if (t?.isAlive == true) {
            // librtlsdr still inside its USB loop: closing now would free it under that loop.
            Log.w(TAG, "RTL-SDR stream didn't stop in time; leaving it to finish")
            Thread({ t.join(); s?.close(); c?.close() }, "rtl-sdr-close").apply { isDaemon = true; start() }
            return
        }
        s?.close()
        c?.close()
    }

    private fun stream(s: RtlSdr) {
        val r = try {
            s.run { samples, count -> listener.onRxAudio(samples, count, RtlSdr.SAMPLE_RATE) }
        } catch (t: Throwable) {
            Log.e(TAG, "RTL-SDR stream failed", t)
            -1
        }
        Log.i(TAG, "RTL-SDR stream ended ($r)")
        if (!stopping) listener.onLost("The RTL-SDR stopped (unplugged, or a USB error).")
    }

    private fun askPermission(device: UsbDevice): Boolean {
        val latch = CountDownLatch(1)
        var granted = false
        UsbPermissionHelper.requestPermission(context, device) { ok ->
            granted = ok
            latch.countDown()
        }
        return latch.await(PERMISSION_WAIT_S, TimeUnit.SECONDS) && granted
    }

    private fun fail(message: String): Boolean {
        lastError = message
        Log.w(TAG, message)
        return false
    }

    private fun tuneError(code: Int, dialHz: Long) = when (code) {
        -2 -> "The RTL-SDR couldn't switch sampling mode for ${dialHz / 1000} kHz."
        -3 -> "The RTL-SDR couldn't tune to ${dialHz / 1000} kHz. HF needs direct sampling (V3) or an upconverter (V4)."
        else -> "The RTL-SDR can't tune to ${dialHz / 1000} kHz."
    }

    companion object {
        private const val TAG = "RtlSdrSession"
        private const val STOP_WAIT_MS = 3_000L
        private const val PERMISSION_WAIT_S = 60L
        const val AUDIO_NAME = "RTL-SDR"
    }
}
