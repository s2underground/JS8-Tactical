package com.js8call.example.storeforward

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Messages this station holds for others (relayed and posted), and messages other stations
 * have said they hold for us. Kept in one small JSON file; the lists are short.
 */
class HeldStore private constructor(private val ctx: Context) {

    private val file = File(ctx.filesDir, "store_forward.json")
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(ctx)

    /** A message another station says it has for us (from YES MSG ID / HEARTBEAT … MSG ID / NEXT MSG ID). */
    data class Waiting(val station: String, val id: Int, val seenMs: Long, val askedMs: Long? = null)

    private var held: List<Held> = emptyList()
    private var waiting: List<Waiting> = emptyList()
    private var nextId = 1
    private var loaded = false

    private val _changes = MutableLiveData(0)
    /** Bumped on any change, for screens to redraw. */
    val changes: LiveData<Int> = _changes
    private fun bump() = _changes.postValue((_changes.value ?: 0) + 1)

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        nextId = o.optInt("nextId", 1)
        held = o.optJSONArray("held")?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { decode(a.getJSONObject(i)) }.getOrNull() } }.orEmpty()
        waiting = o.optJSONArray("waiting")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val w = a.getJSONObject(i)
                    Waiting(w.getString("station"), w.getInt("id"), w.getLong("seen"), if (w.has("asked")) w.getLong("asked") else null)
                }.getOrNull()
            }
        }.orEmpty()
        nextId = maxOf(nextId, (held.maxOfOrNull { it.id } ?: 0) + 1)
    }

    /** Writes happen off the caller's thread (often the main thread), in order, one at a time. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "held-store").apply { isDaemon = true } }

    @Synchronized private fun save() {
        val o = JSONObject()
        o.put("nextId", nextId)
        o.put("held", JSONArray().apply { held.forEach { put(encode(it)) } })
        o.put("waiting", JSONArray().apply {
            waiting.forEach { w -> put(JSONObject().put("station", w.station).put("id", w.id).put("seen", w.seenMs).apply { w.askedMs?.let { put("asked", it) } }) }
        })
        val text = o.toString()
        io.execute {
            // Whole file or nothing: a crash mid-write leaves the previous version.
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
            }
        }
        bump()
    }

    @Synchronized fun all(): List<Held> { load(); return held }
    @Synchronized fun waiting(): List<Waiting> { load(); return waiting }

    /** Holds a new message and returns it with its id. */
    @Synchronized fun add(h: Held): Held {
        load()
        val withId = h.copy(id = nextId++)
        if (h.kind == Held.Kind.RELAY) {
            val now = System.currentTimeMillis()
            held = Held.makeRoomForRelay(held.filter { Held.kept(it, now) })
        }
        held = held + withId
        save()
        return withId
    }

    @Synchronized fun update(h: Held) { load(); held = held.map { if (it.id == h.id) h else it }; save() }
    @Synchronized fun delete(id: Int) { load(); held = held.filterNot { it.id == id }; save() }

    /** Records that [h] went out to [who]. */
    @Synchronized fun markSent(id: Int, who: String, now: Long) {
        load()
        held = held.map { if (it.id == id) it.copy(deliveredTo = it.deliveredTo + Held.base(who), sends = it.sends + 1, lastSentMs = now) else it }
        save()
    }

    /** The posted copy of a report, if it is posted. */
    @Synchronized fun postedFor(reportId: String): Held? { load(); return held.lastOrNull { it.kind == Held.Kind.POSTED && it.reportId == reportId } }

    /** Same text from the same sender to the same place already held (a repeated MSG TO:). */
    @Synchronized fun duplicate(to: String, from: String, text: String): Boolean {
        load()
        return held.any { it.kind == Held.Kind.RELAY && it.to.equals(to, true) && it.from.equals(from, true) && it.text == text }
    }

    /**
     * Drops expired posts (a day past expiry, for the list), relayed group messages a day past
     * their window, and relayed messages for one station after 30 days (7 once delivered).
     */
    @Synchronized fun prune(now: Long) {
        load()
        val keep = held.filter { Held.kept(it, now) }
        val w = waiting.filter { now - it.seenMs < 3 * DAY_MS }
        if (keep.size != held.size || w.size != waiting.size) { held = keep; waiting = w; save() }
    }

    @Synchronized fun addWaiting(station: String, id: Int, now: Long) {
        load()
        val s = Held.base(station)
        if (waiting.any { Held.base(it.station) == s && it.id == id }) {
            waiting = waiting.map { if (Held.base(it.station) == s && it.id == id) it.copy(seenMs = now) else it }
        } else waiting = waiting + Waiting(station, id, now)
        save()
    }

    @Synchronized fun markAsked(station: String, id: Int, now: Long) {
        load()
        waiting = waiting.map { if (it.station == station && it.id == id) it.copy(askedMs = now) else it }
        save()
    }

    /** A message arrived from [station]: whatever it offered is now in the Inbox. */
    @Synchronized fun gotFrom(station: String, id: Int?) {
        load()
        val s = Held.base(station)
        val w = waiting.filterNot { Held.base(it.station) == s && (id == null || it.id == id) && it.askedMs != null }
        if (w.size != waiting.size) { waiting = w; save() }
    }

    @Synchronized fun dismissWaiting(station: String, id: Int) {
        load(); waiting = waiting.filterNot { it.station == station && it.id == id }; save()
    }

    /** Hold MSG TO: messages for other stations (desktop JS8Call: on unless relaying is turned off). */
    val holdEnabled: Boolean get() = prefs.getBoolean(KEY_HOLD, true)
    /** Answer QUERY MSGS / QUERY MSG and mention waiting messages in heartbeat ACKs (with auto-reply on). */
    val serveEnabled: Boolean get() = prefs.getBoolean(KEY_SERVE, true)

    var postNoticeShown: Boolean
        get() = prefs.getBoolean(KEY_POST_NOTICE, false)
        set(v) { prefs.edit().putBoolean(KEY_POST_NOTICE, v).apply() }

    private fun encode(h: Held) = JSONObject().apply {
        put("id", h.id); put("to", h.to); put("from", h.from); put("text", h.text)
        put("received", h.receivedMs); put("kind", h.kind.name)
        h.expiresMs?.let { put("expires", it) }
        put("delivered", JSONArray(h.deliveredTo.toList()))
        put("sends", h.sends)
        h.lastSentMs?.let { put("lastSent", it) }
        h.submode?.let { put("submode", it) }
        h.reportId?.let { put("report", it) }
        h.via?.let { put("via", it) }
    }

    private fun decode(o: JSONObject) = Held(
        id = o.getInt("id"), to = o.getString("to"), from = o.getString("from"), text = o.getString("text"),
        receivedMs = o.getLong("received"), kind = Held.Kind.valueOf(o.optString("kind", "RELAY")),
        expiresMs = if (o.has("expires")) o.getLong("expires") else null,
        deliveredTo = o.optJSONArray("delivered")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
        sends = o.optInt("sends", 0),
        lastSentMs = if (o.has("lastSent")) o.getLong("lastSent") else null,
        submode = if (o.has("submode")) o.getInt("submode") else null,
        reportId = if (o.has("report")) o.getString("report") else null,
        via = if (o.has("via")) o.getString("via") else null,
    )

    companion object {
        const val KEY_HOLD = "sf_hold_enabled"
        const val KEY_SERVE = "sf_serve_enabled"
        const val KEY_POST_NOTICE = "sf_post_notice_shown"
        private const val DAY_MS = 24 * 3600_000L

        // Holds only the application context (see of()), which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: HeldStore? = null
        fun of(ctx: Context): HeldStore =
            instance ?: synchronized(this) { instance ?: HeldStore(ctx.applicationContext).also { instance = it } }
    }
}
