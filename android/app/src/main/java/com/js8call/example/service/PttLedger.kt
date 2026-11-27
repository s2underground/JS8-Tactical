package com.js8call.example.service

import java.util.concurrent.atomic.AtomicLong

/**
 * What this app last told the radio's PTT, counted at the transport: every key command and every
 * confirmed release, in order. The radio can only be keyed by this app if a key command went out
 * after the last release the radio confirmed.
 *
 * Kept apart from the PTT state machine on purpose: it answers "could we have keyed it?" from the
 * commands themselves, so a stale flag in the state machine can't raise a false "radio may still
 * be transmitting" alarm when the radio is unplugged long after its last send (field report,
 * build 342).
 */
class PttLedger {
    private val counter = AtomicLong(0)
    @Volatile private var lastKey = 0L
    @Volatile private var lastRelease = 0L

    /** Call just before a key command goes to the radio. */
    fun keySent() {
        lastKey = counter.incrementAndGet()
    }

    /** Call when the radio confirmed a release. */
    fun releaseConfirmed() {
        lastRelease = counter.incrementAndGet()
    }

    /** True when a key command went out after the last confirmed release. */
    fun keyedSinceRelease(): Boolean = lastKey > lastRelease

    override fun toString(): String = "key #$lastKey, release #$lastRelease"
}
