package com.js8call.example.interference

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import java.io.File
import java.util.concurrent.Executors

/**
 * The app's interference watch: feeds the receiver's spectrum and decodes to an
 * [InterferenceDetector], publishes its judgement for the screens, and keeps the learned
 * baselines in `interference_baseline.txt`. Receive-side only; nothing is ever sent.
 */
object Interference {

    const val PREF_ENABLED = "interference_watch"
    const val PREF_WARN_TX = "interference_warn_tx"
    private const val FILE = "interference_baseline.txt"
    private const val SAVE_EVERY_MS = 5 * 60_000L

    private val lock = Any()
    private var detector: InterferenceDetector? = null
    /** The app's files directory (a File, not a Context, so nothing leaks). */
    private var dir: File? = null
    private var lastPublished: InterferenceDetector.Assessment? = null
    private var lastSaveMs = 0L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "interference-io").apply { isDaemon = true } }

    private val _state = MutableLiveData<InterferenceDetector.Assessment?>(null)
    /** The current judgement for the dial in use; null when the watch is off or the receiver isn't running. */
    val state: LiveData<InterferenceDetector.Assessment?> = _state

    fun enabled(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, true)
    fun warnBeforeTx(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_WARN_TX, true)

    /** The latest judgement, if there is one worth showing. */
    fun current(): InterferenceDetector.Assessment? = synchronized(lock) { lastPublished }

    private fun detector(ctx: Context): InterferenceDetector {
        detector?.let { return it }
        val filesDir = ctx.applicationContext.filesDir
        dir = filesDir
        val loaded = runCatching { File(filesDir, FILE).takeIf { it.isFile }?.readText()?.let { BaselineText.read(it) } }.getOrNull()
        return InterferenceDetector(loaded ?: HashMap()).also { detector = it }
    }

    /** One spectrum frame from the engine (its own thread, about 10 a second). */
    fun onSpectrum(ctx: Context, nowMs: Long, dialHz: Long, bins: FloatArray, binHz: Float) {
        synchronized(lock) {
            val d = detector(ctx)
            d.onSpectrum(nowMs, dialHz, bins, binHz)
            publish(d.assessment())
            if (nowMs - lastSaveMs > SAVE_EVERY_MS) { lastSaveMs = nowMs; saveLocked(d) }
        }
    }

    fun onDecode(ctx: Context, nowMs: Long, dialHz: Long, snr: Int) = synchronized(lock) {
        detector(ctx).onDecode(nowMs, dialHz, snr)
    }

    fun onTransmit(ctx: Context, nowMs: Long) = synchronized(lock) { detector(ctx).onTransmit(nowMs) }

    /** The receiver stopped (or the watch was switched off): nothing to show, keep what was learned. */
    fun stop() = synchronized(lock) {
        detector?.let { saveLocked(it) }
        lastPublished = null
        _state.postValue(null)
    }

    private fun publish(a: InterferenceDetector.Assessment) {
        val prev = lastPublished
        if (prev == a) return
        lastPublished = a
        _state.postValue(a)
    }

    private fun saveLocked(d: InterferenceDetector) {
        val filesDir = dir ?: return
        val text = BaselineText.write(d.baselines)
        io.execute {
            runCatching {
                val tmp = File(filesDir, "$FILE.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(filesDir, FILE))
            }
        }
    }

    /** Plain-text status for the diagnostics report (no callsigns involved). */
    fun diagnostics(): String = synchronized(lock) {
        val a = lastPublished ?: return@synchronized "watch idle\n"
        "dial ${a.dialHz / 1000} kHz · ${a.level} ${a.kinds} · floor ${a.noiseAboveDb?.let { "%+.1f dB".format(java.util.Locale.US, it) } ?: "no baseline"}" +
            " · carriers ${a.carriersHz} · resident ${a.residentCarriersHz} · quiet ${a.decodesQuiet} · snr drop ${a.snrDropDb}\n" +
            "baselines for ${detector?.baselines?.size ?: 0} dials\n"
    }
}
