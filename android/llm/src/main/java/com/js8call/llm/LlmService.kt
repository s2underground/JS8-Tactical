package com.js8call.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * The AI engine (llama.cpp) in its own isolated process (AndroidManifest: isolatedProcess,
 * process ":llm"). That process has no permissions and can't reach the app's files, the network,
 * USB, the radio or PTT: a crash, or a booby-trapped model file, stays in here. The model comes in
 * as an already-verified read-only descriptor; only text goes back, and the app caps it.
 */
class LlmService : Service() {

    /** Handles this process gave out; anything else is refused. */
    private val live = HashSet<Long>()

    /** One engine call at a time: generate, stats and free never overlap on a handle. */
    private val engine = Any()

    private fun checked(handle: Long): Long = synchronized(live) { if (handle in live) handle else 0L }

    private val binder = object : ILlmEngine.Stub() {
        override fun engineStatus(): String = LlamaNative.loadError.orEmpty()

        override fun load(model: ParcelFileDescriptor?, nCtx: Int, nThreads: Int): Long {
            if (model == null || LlamaNative.loadError != null) return 0L
            return try {
                val h = synchronized(engine) { LlamaNative.nativeLoadFd(model.fd, nCtx.coerceIn(256, 32768), nThreads.coerceIn(1, 16)) }
                if (h != 0L) synchronized(live) { live += h }
                h
            } finally {
                runCatching { model.close() } // the engine keeps its own duplicate
            }
        }

        override fun loadMs(handle: Long): Double = synchronized(engine) { checked(handle).let { if (it == 0L) 0.0 else LlamaNative.nativeLoadMs(it) } }

        override fun generate(handle: Long, system: String?, user: String?, maxTokens: Int, temperature: Float, listener: ITokenListener?): String {
            val h = checked(handle)
            if (h == 0L) return ""
            val sink = listener?.let { l ->
                LlamaNative.TokenSink { piece ->
                    // A dead caller (or any error reaching it) stops the generation.
                    runCatching { l.onToken(piece) }.getOrDefault(false)
                }
            }
            return synchronized(engine) {
                if (checked(h) == 0L) "" else LlamaNative.nativeGenerate(h, system.orEmpty(), user.orEmpty(), maxTokens.coerceIn(1, 4096), temperature, sink)
            }
        }

        override fun stats(handle: Long): LongArray = synchronized(engine) { checked(handle).let { if (it == 0L) LongArray(4) else LlamaNative.nativeStats(it) } }

        override fun error(handle: Long): String = synchronized(engine) { checked(handle).let { if (it == 0L) "not loaded" else LlamaNative.nativeError(it) } }

        override fun free(handle: Long) {
            synchronized(engine) {
                val h = synchronized(live) { if (live.remove(handle)) handle else 0L }
                if (h != 0L) LlamaNative.nativeFree(h)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(engine) {
            synchronized(live) { live.toList().also { live.clear() } }.forEach { runCatching { LlamaNative.nativeFree(it) } }
        }
        Log.i("LlmService", "isolated AI engine stopped")
        super.onDestroy()
    }
}
