package com.js8call.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's side of the on-device language model. The model itself (llama.cpp) runs in
 * [LlmService]'s isolated process, reached over Binder: this process never loads the native
 * engine, and the engine never sees the app's files, network, USB, radio or PTT.
 *
 * One [Session] holds a loaded model. Every call blocks: use a background thread.
 */
object LlamaBridge {

    private const val TAG = "LlamaBridge"
    private const val BIND_TIMEOUT_S = 15L
    /** Longest text accepted back from the engine (a summary is a few hundred characters). */
    const val MAX_TEXT = 32 * 1024
    /** Longest single piece accepted from the engine while it writes. */
    const val MAX_PIECE = 4 * 1024

    /** The engine is built for 64-bit ARM only. Cheap; safe on the main thread. */
    val available: Boolean
        get() = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    /** Why the engine didn't start or load, for diagnostics; null if nothing went wrong yet. */
    @Volatile var loadError: String? = null
        private set

    fun interface TokenListener {
        /** A new piece of generated text; return false to stop. */
        fun onToken(piece: String): Boolean
    }

    data class Stats(val promptTokens: Long, val promptMs: Long, val genTokens: Long, val genMs: Long) {
        val promptTokPerSec get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
        val genTokPerSec get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
    }

    class Session internal constructor(
        private val app: Context,
        private val conn: ServiceConnection,
        private val engine: ILlmEngine,
        private var handle: Long,
    ) : AutoCloseable {
        /** Set when the engine's process died; later calls give empty results. */
        @Volatile private var dead: String? = null

        private fun <T> call(fallback: T, block: () -> T): T {
            if (dead != null || handle == 0L) return fallback
            return try {
                block()
            } catch (e: Exception) {
                // DeadObjectException and friends: the isolated engine stopped (crash, low memory).
                dead = "AI engine stopped (${e.javaClass.simpleName})"
                Log.w(TAG, dead!!)
                fallback
            }
        }

        val loadMs: Double get() = call(0.0) { engine.loadMs(handle) }

        fun generate(system: String, user: String, maxTokens: Int, temperature: Float = 0f, listener: TokenListener? = null): String {
            check(handle != 0L) { "closed" }
            val remote = listener?.let { l ->
                object : ITokenListener.Stub() {
                    // Runs on a Binder thread of this process; the listener may block (RadioGate).
                    override fun onToken(piece: String?): Boolean =
                        l.onToken(clean(piece.orEmpty(), MAX_PIECE))
                }
            }
            return clean(call("") { engine.generate(handle, system, user, maxTokens, temperature, remote) }, MAX_TEXT)
        }

        fun stats(): Stats = call(LongArray(4)) { engine.stats(handle) }
            .let { if (it.size >= 4) Stats(it[0], it[1], it[2], it[3]) else Stats(0, 0, 0, 0) }

        fun error(): String = dead ?: clean(call("") { engine.error(handle) }, 1024)

        override fun close() {
            if (handle != 0L) call(Unit) { engine.free(handle) }
            handle = 0L
            runCatching { app.unbindService(conn) }
        }
    }

    /**
     * Starts the isolated engine and loads [model] (a GGUF file, already SHA-256 checked) through a
     * read-only descriptor. Null if it can't be loaded ([loadError] says why). Never on the main thread.
     */
    fun load(context: Context, model: File, contextTokens: Int = 4096, threads: Int = 4): Session? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "LlamaBridge.load blocks: call it off the main thread" }
        if (!available) { loadError = "needs a 64-bit ARM device"; return null }
        val app = context.applicationContext
        val ready = CountDownLatch(1)
        var binder: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
            override fun onBindingDied(name: ComponentName?) { ready.countDown() }
            override fun onNullBinding(name: ComponentName?) { ready.countDown() }
        }
        val bound = runCatching { app.bindService(Intent(app, LlmService::class.java), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) { loadError = "could not start the isolated AI process"; return null }
        if (!ready.await(BIND_TIMEOUT_S, TimeUnit.SECONDS) || binder == null) {
            loadError = "the isolated AI process did not answer"
            runCatching { app.unbindService(conn) }
            return null
        }
        val engine = ILlmEngine.Stub.asInterface(binder)
        return try {
            val status = engine.engineStatus()
            if (status.isNotEmpty()) {
                loadError = clean(status, 512)
                app.unbindService(conn)
                return null
            }
            val handle = ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                engine.load(pfd, contextTokens, threads)
            }
            if (handle == 0L) {
                loadError = "model could not be loaded"
                app.unbindService(conn)
                null
            } else {
                loadError = null
                Session(app, conn, engine, handle)
            }
        } catch (e: Exception) {
            loadError = "${e.javaClass.simpleName}: ${e.message}"
            runCatching { app.unbindService(conn) }
            null
        }
    }

    /** Text from the engine, capped, without control characters other than newline and tab. */
    internal fun clean(text: String, max: Int): String {
        val capped = if (text.length > max) text.substring(0, max) else text
        return if (capped.none { it.isISOControl() && it != '\n' && it != '\t' }) capped
        else capped.filter { !it.isISOControl() || it == '\n' || it == '\t' }
    }
}
