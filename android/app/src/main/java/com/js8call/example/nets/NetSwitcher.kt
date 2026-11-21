package com.js8call.example.nets

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import java.io.File

/**
 * When the Net Switcher changes nets. Pure: the service supplies what's happening on the radio.
 * It never cuts off a transmission, waits a little for a message that is coming in, and steps
 * back when the operator changes frequency by hand (until the next scheduled change).
 */
object NetSwitcher {

    sealed class Decision {
        object Idle : Decision()
        data class Wait(val reason: String) : Decision()
        data class Switch(val target: NetSchedule.Target) : Decision()
    }

    /** How long a due switch waits for a message that is still coming in. */
    const val RECEIVE_GRACE_MS = 2 * 60_000L

    fun decide(
        nets: List<NetSchedule.Net>,
        home: NetSchedule.Home,
        enabled: Boolean,
        appliedKey: String?,
        overrideUntilMs: Long,
        now: Long,
        transmitting: Boolean,
        receiving: Boolean,
    ): Decision {
        if (!enabled) return Decision.Idle
        if (now < overrideUntilMs) return Decision.Idle
        val t = NetSchedule.target(nets, home, now)
        if (t.key == appliedKey) return Decision.Idle
        if (transmitting) return Decision.Wait("finishing a transmission")
        if (receiving && now - t.sinceMs < RECEIVE_GRACE_MS) return Decision.Wait("a message is coming in")
        return Decision.Switch(t)
    }

    /** Until when a hand-made frequency change pauses the switcher: the next scheduled change. */
    fun overrideUntil(nets: List<NetSchedule.Net>, home: NetSchedule.Home, now: Long): Long =
        NetSchedule.nextChange(nets, home, now)?.first ?: (now + 24 * 3_600_000L)

    /** Within 1 kHz counts as the same dial (rigs round). */
    fun sameDial(a: Long?, b: Long?): Boolean = a != null && b != null && kotlin.math.abs(a - b) < 1_000L
}

/**
 * The nets, home settings, switcher state and recent switches, shared by the Nets page and the
 * receiver service. Nets live in a file; state in preferences.
 */
class NetStore private constructor(private val ctx: Context) {

    private val file = File(ctx.filesDir, "nets.txt")
    private val logFile = File(ctx.filesDir, "net_switch_log.txt")
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(ctx)

    @Volatile private var cache: Pair<List<NetSchedule.Net>, NetSchedule.Home>? = null

    private val _changes = MutableLiveData(0)
    /** Bumped on any change (nets, state, log), for screens to redraw. */
    val changes: LiveData<Int> = _changes

    private fun bump() = _changes.postValue((_changes.value ?: 0) + 1)

    @Synchronized
    private fun load(): Pair<List<NetSchedule.Net>, NetSchedule.Home> =
        cache ?: NetSchedule.decode(runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()).also { cache = it }

    fun nets(): List<NetSchedule.Net> = load().first
    fun home(): NetSchedule.Home = load().second

    @Synchronized
    fun save(nets: List<NetSchedule.Net>, home: NetSchedule.Home = home()) {
        cache = nets to home
        runCatching { file.writeText(NetSchedule.encode(nets, home)) }
        // A changed schedule is applied fresh (and ends a hand-made pause).
        prefs.edit().remove(KEY_APPLIED).putLong(KEY_OVERRIDE, 0L).apply()
        bump()
    }

    fun put(net: NetSchedule.Net) {
        val list = nets().toMutableList()
        val i = list.indexOfFirst { it.id == net.id }
        if (i >= 0) list[i] = net else list += net
        save(list)
    }

    fun delete(id: String) = save(nets().filterNot { it.id == id })
    fun setHome(home: NetSchedule.Home) = save(nets(), home)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).remove(KEY_APPLIED).putLong(KEY_OVERRIDE, 0L).apply(); bump() }

    var appliedKey: String?
        get() = prefs.getString(KEY_APPLIED, null)
        set(v) { prefs.edit().putString(KEY_APPLIED, v).apply(); bump() }

    var overrideUntilMs: Long
        get() = prefs.getLong(KEY_OVERRIDE, 0L)
        set(v) { prefs.edit().putLong(KEY_OVERRIDE, v).apply(); bump() }

    /** The dial the switcher last set (to tell its own changes from the operator's). */
    var lastSetDialHz: Long
        get() = prefs.getLong(KEY_LAST_DIAL, 0L)
        set(v) { prefs.edit().putLong(KEY_LAST_DIAL, v).apply() }

    /** Hold heartbeats and auto-replies this many minutes after a band change (0 = don't). */
    var holdAfterBandChangeMin: Int
        get() = prefs.getString(KEY_HOLD, "0")?.toIntOrNull() ?: 0
        set(v) { prefs.edit().putString(KEY_HOLD, v.toString()).apply(); bump() }

    /** What the switcher is waiting on right now, if anything (shown on the Nets page). */
    @Volatile var waiting: String? = null
        set(v) { if (field != v) { field = v; bump() } }

    data class LogLine(val ms: Long, val text: String)

    @Synchronized
    fun log(ms: Long, text: String) {
        val lines = (readLog() + LogLine(ms, text)).takeLast(60)
        runCatching { logFile.writeText(lines.joinToString("\n") { "${it.ms}\t${it.text.replace('\n', ' ')}" }) }
        bump()
    }

    @Synchronized
    fun readLog(): List<LogLine> = runCatching {
        logFile.takeIf { it.exists() }?.readLines()?.mapNotNull { l ->
            val i = l.indexOf('\t')
            if (i > 0) l.substring(0, i).toLongOrNull()?.let { LogLine(it, l.substring(i + 1)) } else null
        }
    }.getOrNull().orEmpty()

    companion object {
        const val KEY_ENABLED = "net_switcher_enabled"
        const val KEY_APPLIED = "net_switcher_applied"
        const val KEY_OVERRIDE = "net_switcher_override_until"
        const val KEY_LAST_DIAL = "net_switcher_last_dial"
        const val KEY_HOLD = "net_switcher_hold_min"

        @Volatile private var instance: NetStore? = null
        fun of(ctx: Context): NetStore =
            instance ?: synchronized(this) { instance ?: NetStore(ctx.applicationContext).also { instance = it } }
    }
}
