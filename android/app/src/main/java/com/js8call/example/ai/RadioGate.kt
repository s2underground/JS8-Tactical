package com.js8call.example.ai

/**
 * Lets on-device AI work step aside while the radio is sending. Loading a model and running it
 * keep the CPU and memory busy for seconds at a time; a diagnostics report showed the audio
 * clock falling ~64 ms behind twice while the model loaded and wrote a digest summary with a
 * transmission queued, shifting that transmission ~0.1 s late. Harmless for JS8, but the radio
 * comes first: AI jobs wait here before they start and between generated tokens.
 *
 * Plain Kotlin with no Android classes, so it can be tested.
 */
object RadioGate {
    /** Set by the engine service: a transmission is queued for its slot or on the air. */
    @Volatile
    var sending: Boolean = false

    /** Longest a single wait lasts, so a flag left set by mistake can't stall AI work for good. */
    const val MAX_WAIT_MS = 5 * 60_000L
    private const val POLL_MS = 250L

    /**
     * Blocks the calling (AI worker) thread while [sending] is true, up to [maxWaitMs].
     * Returns true once the radio is quiet, false if it gave up waiting.
     */
    fun awaitQuiet(
        maxWaitMs: Long = MAX_WAIT_MS,
        isSending: () -> Boolean = { sending },
        now: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Boolean {
        if (!isSending()) return true
        val start = now()
        while (isSending()) {
            if (now() - start >= maxWaitMs) return false
            try {
                sleep(POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return !isSending()
            }
        }
        return true
    }
}
