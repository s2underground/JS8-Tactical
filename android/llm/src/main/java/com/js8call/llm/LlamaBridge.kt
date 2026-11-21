package com.js8call.llm

/**
 * Thin wrapper over llama.cpp (native library "js8llm", arm64 only).
 * One [Session] holds a loaded model; call from a background thread.
 */
object LlamaBridge {

    /** False on devices without the native library (32-bit ARM, emulators without arm64). */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("js8llm") }.isSuccess
    }

    fun interface TokenListener {
        /** A new piece of generated text; return false to stop. */
        fun onToken(piece: String): Boolean
    }

    data class Stats(val promptTokens: Long, val promptMs: Long, val genTokens: Long, val genMs: Long) {
        val promptTokPerSec get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
        val genTokPerSec get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
    }

    class Session internal constructor(private var handle: Long) : AutoCloseable {
        val loadMs: Double get() = nativeLoadMs(handle)

        fun generate(system: String, user: String, maxTokens: Int, temperature: Float = 0f, listener: TokenListener? = null): String {
            check(handle != 0L) { "closed" }
            return nativeGenerate(handle, system, user, maxTokens, temperature, listener)
        }

        fun stats(): Stats = nativeStats(handle).let { Stats(it[0], it[1], it[2], it[3]) }
        fun error(): String = nativeError(handle)

        override fun close() {
            if (handle != 0L) nativeFree(handle)
            handle = 0L
        }
    }

    /** Loads [path] (a GGUF file); null if it can't be loaded. */
    fun load(path: String, contextTokens: Int = 4096, threads: Int = 4): Session? {
        if (!available) return null
        val h = nativeLoad(path, contextTokens, threads)
        return if (h == 0L) null else Session(h)
    }

    @JvmStatic private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic private external fun nativeLoadMs(handle: Long): Double
    @JvmStatic private external fun nativeGenerate(handle: Long, system: String, user: String, maxTokens: Int, temperature: Float, listener: TokenListener?): String
    @JvmStatic private external fun nativeStats(handle: Long): LongArray
    @JvmStatic private external fun nativeError(handle: Long): String
    @JvmStatic private external fun nativeFree(handle: Long)
}
