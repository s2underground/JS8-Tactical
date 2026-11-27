package com.js8call.llm

/**
 * The llama.cpp JNI layer (native library "js8llm"). Used only inside [LlmService]'s isolated
 * process: the app's own process never loads it.
 */
internal object LlamaNative {

    /** Null if the library loaded, else why not. */
    val loadError: String? by lazy {
        runCatching { System.loadLibrary("js8llm") }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
    }

    @JvmStatic external fun nativeLoadFd(fd: Int, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun nativeLoadMs(handle: Long): Double
    @JvmStatic external fun nativeGenerate(handle: Long, system: String, user: String, maxTokens: Int, temperature: Float, listener: TokenSink?): String
    @JvmStatic external fun nativeStats(handle: Long): LongArray
    @JvmStatic external fun nativeError(handle: Long): String
    @JvmStatic external fun nativeFree(handle: Long)

    /** What the native generator calls with each piece (looked up by name: onToken). */
    fun interface TokenSink {
        fun onToken(piece: String): Boolean
    }
}
