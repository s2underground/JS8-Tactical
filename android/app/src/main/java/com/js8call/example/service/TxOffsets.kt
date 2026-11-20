package com.js8call.example.service

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlin.random.Random

/**
 * Where on the waterfall (audio offset, Hz) each kind of send goes out.
 * One source of truth for the engine service (which sends) and the
 * waterfall (which must show exactly where the next send will land).
 *
 * A JS8 signal starts at its offset and goes up by the speed's bandwidth.
 *
 * Heartbeats (and replies to them) can go:
 *  - [HB_ZONE]: a spot in the 500–1,000 Hz heartbeat zone, as in JS8Call.
 *    The spot is picked ahead of time and saved, so the waterfall can show it;
 *    a new spot is picked after each heartbeat.
 *  - [HB_TX]: on the TX offset, like any other message.
 *  - [HB_FIXED]: on a fixed offset the user chose.
 * Nothing here limits the choice; outside the zone the UI only reminds.
 */
object TxOffsets {
    const val KEY_TX = "tx_offset_hz"
    const val KEY_HB_MODE = "hb_offset_mode"
    const val KEY_HB_FIXED = "hb_offset_fixed_hz"
    const val KEY_HB_NEXT = "hb_offset_next_hz"

    const val HB_ZONE = "zone"
    const val HB_TX = "tx"
    const val HB_FIXED = "fixed"

    const val DEFAULT_TX_HZ = 1500f
    const val DEFAULT_HB_FIXED_HZ = 750f
    const val ZONE_LOW_HZ = 500f
    const val ZONE_HIGH_HZ = 1000f

    private fun prefs(ctx: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun txOffset(ctx: Context): Float = prefs(ctx).getFloat(KEY_TX, DEFAULT_TX_HZ)

    fun setTxOffset(ctx: Context, hz: Float) {
        prefs(ctx).edit().putFloat(KEY_TX, hz).apply()
    }

    fun hbMode(ctx: Context): String = prefs(ctx).getString(KEY_HB_MODE, HB_ZONE) ?: HB_ZONE
    fun hbFixed(ctx: Context): Float = prefs(ctx).getFloat(KEY_HB_FIXED, DEFAULT_HB_FIXED_HZ)

    fun setHeartbeat(ctx: Context, mode: String, fixedHz: Float? = null) {
        prefs(ctx).edit().apply {
            putString(KEY_HB_MODE, mode)
            fixedHz?.let { putFloat(KEY_HB_FIXED, it) }
        }.apply()
    }

    /** Speed in use for sending (pref [JS8EngineService.PREF_TX_SUBMODE]). */
    fun submode(ctx: Context): Int =
        prefs(ctx).getInt(JS8EngineService.PREF_TX_SUBMODE, JS8EngineService.SUBMODE_NORMAL)

    /** Width of a signal at [submode]. */
    fun bandwidthHz(submode: Int): Float = when (submode) {
        JS8EngineService.SUBMODE_SLOW -> 25f
        JS8EngineService.SUBMODE_FAST -> 80f
        JS8EngineService.SUBMODE_TURBO -> 160f
        else -> 50f
    }

    /** A spot in the heartbeat zone where the whole signal fits. */
    fun pickInZone(bandwidthHz: Float, random: Random = Random.Default): Float {
        val high = (ZONE_HIGH_HZ - bandwidthHz).coerceAtLeast(ZONE_LOW_HZ)
        return (ZONE_LOW_HZ + random.nextDouble() * (high - ZONE_LOW_HZ)).toFloat()
    }

    /** True when a signal at [hz] fits inside the heartbeat zone. */
    fun inZone(hz: Float, bandwidthHz: Float): Boolean = hz >= ZONE_LOW_HZ && hz + bandwidthHz <= ZONE_HIGH_HZ

    /** Where the next heartbeat will go. Does not change anything except picking a first zone spot. */
    fun nextHeartbeat(ctx: Context): Float {
        val p = prefs(ctx)
        return when (hbMode(ctx)) {
            HB_TX -> txOffset(ctx)
            HB_FIXED -> hbFixed(ctx)
            else -> {
                val bw = bandwidthHz(submode(ctx))
                val saved = p.getFloat(KEY_HB_NEXT, -1f)
                if (saved >= 0f && inZone(saved, bw)) saved
                else pickInZone(bw).also { p.edit().putFloat(KEY_HB_NEXT, it).apply() }
            }
        }
    }

    /** The offset for a heartbeat about to be sent at [submode]: the spot the waterfall shows. */
    fun heartbeatFor(ctx: Context, submode: Int): Float {
        if (hbMode(ctx) != HB_ZONE) return nextHeartbeat(ctx)
        val p = prefs(ctx)
        val bw = bandwidthHz(submode)
        val saved = p.getFloat(KEY_HB_NEXT, -1f)
        return if (saved >= 0f && inZone(saved, bw)) saved
        else pickInZone(bw).also { p.edit().putFloat(KEY_HB_NEXT, it).apply() }
    }

    /** A heartbeat went out: in zone mode, pick (and show) the spot for the next one. */
    fun heartbeatSent(ctx: Context, submode: Int) {
        if (hbMode(ctx) == HB_ZONE) prefs(ctx).edit().putFloat(KEY_HB_NEXT, pickInZone(bandwidthHz(submode))).apply()
    }
}
