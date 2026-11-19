package com.js8call.example.atak

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.js8call.example.reports.LatLon
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.Executors

/**
 * Sends JS8 reports to ATAK as CoT over UDP. No network library, no server:
 * ATAK on this device (or anyone on the local network, for the mesh option)
 * just listens for CoT packets.
 *
 * Targets:
 *  - "local"  127.0.0.1:4242  ATAK on this device; ATAK needs a UDP input on port 4242.
 *  - "mesh"   239.2.3.1:6969  ATAK's mesh SA multicast; every TAK device on the
 *                             network sees the points (no ATAK setup needed).
 *  - "custom" host:port from settings (another device, a TAK Server's UDP input…).
 *
 * Points are sent when they appear or change, and again every [RESEND_MS] while
 * not stale, so an ATAK that was restarted picks them up.
 */
object AtakBridge {

    private const val TAG = "AtakBridge"
    const val KEY_ENABLED = "atak_enabled"
    const val KEY_TARGET = "atak_target"
    const val KEY_HOST = "atak_host"
    const val KEY_PORT = "atak_port"
    const val KEY_STATIONS = "atak_stations"
    const val KEY_STALE_HOURS = "atak_stale_hours"
    private const val KEY_SENT_UIDS = "atak_sent_uids"
    private const val RESEND_MS = 10 * 60_000L
    private const val MULTICAST_TTL = 16

    data class Target(val host: String, val port: Int, val label: String)

    data class Status(val sentPackets: Int, val lastSentMs: Long, val lastError: String?, val target: String)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "atak-cot").apply { isDaemon = true } }
    private val lastSent = HashMap<String, Pair<String, Long>>() // uid -> (signature, sentMs)
    @Volatile var status = Status(0, 0L, null, "")
        private set

    fun enabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun includeStations(ctx: Context) = prefs(ctx).getBoolean(KEY_STATIONS, false)
    fun staleAfterMs(ctx: Context): Long =
        (prefs(ctx).getString(KEY_STALE_HOURS, "24")?.toDoubleOrNull() ?: 24.0).coerceIn(0.1, 24.0 * 365) .let { (it * 3_600_000L).toLong() }

    fun target(ctx: Context): Target {
        val p = prefs(ctx)
        return when (p.getString(KEY_TARGET, "local")) {
            "mesh" -> Target("239.2.3.1", 6969, "Mesh SA multicast 239.2.3.1:6969")
            "custom" -> {
                val host = p.getString(KEY_HOST, "")?.trim().orEmpty().ifEmpty { "127.0.0.1" }
                val port = p.getString(KEY_PORT, "4242")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 4242
                Target(host, port, "$host:$port")
            }
            else -> Target("127.0.0.1", 4242, "This device, UDP 4242")
        }
    }

    /**
     * Bring ATAK up to date with the current points. Cheap when nothing changed;
     * call it whenever the traffic picture is rebuilt.
     */
    fun sync(ctx: Context, points: List<Cot.Point>, nowMs: Long = System.currentTimeMillis()) {
        if (!enabled(ctx)) return
        val due = points.filter { p ->
            if (p.staleMs <= nowMs) return@filter false
            val prev = lastSent[p.uid]
            prev == null || prev.first != p.signature || nowMs - prev.second >= RESEND_MS
        }
        if (due.isEmpty()) return
        due.forEach { lastSent[it.uid] = it.signature to nowMs }
        rememberUids(ctx, due.map { it.uid })
        val target = target(ctx)
        val payloads = due.map { Cot.xml(it, nowMs) }
        io.execute { sendAll(target, payloads) }
    }

    /** One short-lived point at [where] so the user can check ATAK is receiving. */
    fun sendTest(ctx: Context, where: LatLon, done: (String?) -> Unit) {
        val target = target(ctx)
        val xml = Cot.xml(Cot.testPoint(where, System.currentTimeMillis()), System.currentTimeMillis())
        io.execute { done(sendAll(target, listOf(xml))) }
    }

    /** Remove every point this app has sent from ATAK (and from anyone else who received them). */
    fun removeAll(ctx: Context, done: (Int, String?) -> Unit) {
        val p = prefs(ctx)
        val uids = p.getStringSet(KEY_SENT_UIDS, emptySet()).orEmpty() + "JS8-Tactical.test"
        val now = System.currentTimeMillis()
        val target = target(ctx)
        val payloads = uids.map { Cot.deleteXml(it, now) }
        lastSent.clear()
        p.edit().remove(KEY_SENT_UIDS).apply()
        io.execute { val err = sendAll(target, payloads); done(uids.size - 1, err) }
    }

    /** Forget what was sent, so everything is sent again (e.g. after the target changes). */
    fun resendAll() = lastSent.clear()

    private fun rememberUids(ctx: Context, uids: List<String>) {
        val p = prefs(ctx)
        val old = p.getStringSet(KEY_SENT_UIDS, emptySet()).orEmpty()
        if (old.containsAll(uids)) return
        // Bounded: keep the newest few thousand so the set can't grow without limit.
        val merged = (old + uids).toList().takeLast(4000).toSet()
        p.edit().putStringSet(KEY_SENT_UIDS, merged).apply()
    }

    /** Returns null on success, else a short error for the settings screen. */
    private fun sendAll(target: Target, payloads: List<String>): String? {
        return try {
            val addr = InetAddress.getByName(target.host)
            val socket: DatagramSocket = if (addr.isMulticastAddress) {
                MulticastSocket().apply { timeToLive = MULTICAST_TTL }
            } else DatagramSocket()
            socket.use { s ->
                payloads.forEach { xml ->
                    val bytes = xml.toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(bytes, bytes.size, addr, target.port))
                }
            }
            status = Status(status.sentPackets + payloads.size, System.currentTimeMillis(), null, target.label)
            null
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message ?: "send failed"}"
            Log.w(TAG, "CoT send to ${target.label} failed", e)
            status = status.copy(lastError = msg, target = target.label)
            msg
        }
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)
}
