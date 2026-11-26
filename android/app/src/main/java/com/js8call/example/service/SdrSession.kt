package com.js8call.example.service

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.js8call.sdr.AirspyHf
import com.js8call.sdr.RtlSdr
import com.js8call.sdr.SdrReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An SDR dongle as the receiver (issue #54): an RTL-SDR (rig type rtl_sdr) or an Airspy HF+
 * (airspy_hf). Finds it on USB, asks for permission, opens it through the app's USB file
 * descriptor, tunes it for the dial, and streams 12 kHz audio to the engine. Receive only; the
 * service treats it like the UV-Pro (audio from the session, no microphone capture).
 *
 * [start] and [stop] block; call them off the main thread.
 */
class SdrSession(private val context: Context, val kind: Kind, private val listener: Listener) {

    enum class Kind(val label: String) { RTL_SDR("RTL-SDR"), AIRSPY_HF("Airspy HF+") }

    interface Listener {
        /** 12 kHz audio; the array is reused after the call returns. */
        fun onRxAudio(samples: ShortArray, count: Int, sampleRateHz: Int)
        /** The dongle stopped on its own (unplugged, USB error). */
        fun onLost(message: String)
    }

    /** Settings > Radio > SDR (the RTL-SDR uses all three; the Airspy HF+ needs none). */
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
    private var sdr: SdrReceiver? = null
    private var thread: Thread? = null
    @Volatile private var stopping = false
    private val lock = Any()

    /** Finds, opens and tunes the dongle, then starts streaming. False with [lastError] set on failure. */
    fun start(dialHz: Long, settings: Settings): Boolean {
        stopping = false
        lastError = null
        val name = kind.label
        val available = if (kind == Kind.AIRSPY_HF) AirspyHf.available else RtlSdr.available
        if (!available) return fail("$name support isn't available on this device (CPU type).")
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.firstOrNull { isOurs(it) }
            ?: return fail("No $name found. Plug it in (with a USB OTG adapter if needed) and try again.")
        if (!usb.hasPermission(device) && !askPermission(device)) {
            return fail("USB permission for the $name was not given.")
        }
        val conn = usb.openDevice(device) ?: return fail("Android couldn't open the $name.")
        // RTL-SDR: take the interface from any kernel driver first (librtlsdr then claims it on
        // the same descriptor). The Airspy library sets the configuration itself, which a claimed
        // interface would block.
        if (kind == Kind.RTL_SDR && device.interfaceCount > 0) conn.claimInterface(device.getInterface(0), true)
        val opened: SdrReceiver? = if (kind == Kind.AIRSPY_HF) AirspyHf.open(conn.fileDescriptor) else RtlSdr.open(conn.fileDescriptor)
        if (opened == null) {
            conn.close()
            return fail("The $name didn't respond. Unplug it, plug it back in, and try again.")
        }
        description = opened.describe()
        Log.i(TAG, "$name opened: $description")
        val r = opened.tune(dialHz, settings.directSampling, settings.ppm, settings.gainTenthsDb)
        if (r < 0) {
            opened.close()
            conn.close()
            return fail(tuneError(r, dialHz))
        }
        synchronized(lock) {
            connection = conn
            sdr = opened
            thread = Thread({ stream(opened) }, "sdr-rx").apply { start() }
        }
        return true
    }

    private fun isOurs(d: UsbDevice) = when (kind) {
        Kind.RTL_SDR -> RtlSdr.isKnown(d.vendorId, d.productId)
        Kind.AIRSPY_HF -> AirspyHf.isKnown(d.vendorId, d.productId)
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
            Log.w(TAG, "${kind.label} stream didn't stop in time; leaving it to finish")
            Thread({ t.join(); s?.close(); c?.close() }, "sdr-close").apply { isDaemon = true; start() }
            return
        }
        s?.close()
        c?.close()
    }

    private fun stream(s: SdrReceiver) {
        val r = try {
            s.run { samples, count -> listener.onRxAudio(samples, count, SdrReceiver.SAMPLE_RATE) }
        } catch (t: Throwable) {
            Log.e(TAG, "${kind.label} stream failed", t)
            -1
        }
        Log.i(TAG, "${kind.label} stream ended ($r)")
        if (!stopping) listener.onLost("The ${kind.label} stopped (unplugged, or a USB error).")
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

    private fun tuneError(code: Int, dialHz: Long) = when {
        code == -2 -> "The ${kind.label} couldn't switch sampling mode for ${dialHz / 1000} kHz."
        code == -3 && kind == Kind.RTL_SDR -> "The RTL-SDR couldn't tune to ${dialHz / 1000} kHz. HF needs direct sampling (V3) or an upconverter (V4)."
        else -> "The ${kind.label} can't tune to ${dialHz / 1000} kHz."
    }

    companion object {
        private const val TAG = "SdrSession"
        private const val STOP_WAIT_MS = 3_000L
        private const val PERMISSION_WAIT_S = 60L
    }
}
