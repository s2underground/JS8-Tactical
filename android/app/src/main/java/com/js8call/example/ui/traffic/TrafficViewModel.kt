package com.js8call.example.ui.traffic

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.example.reports.Reports
import com.js8call.example.rollup.HeaderParser
import com.js8call.example.rollup.RolledMessage
import com.js8call.example.rollup.RollupEngine

/**
 * State for the Traffic screen: the rolled-up view of everything heard.
 *
 * Every decoded frame goes into a [RollupEngine]. Stations are tracked by
 * callsign across offsets (a station's offset changes between
 * transmissions); heartbeats only refresh a station's row, they don't become
 * message cards.
 */
class TrafficViewModel(application: Application) : AndroidViewModel(application) {

    data class StationRow(
        val key: String,
        val label: String,
        val isGroup: Boolean,
        val color: Int,
        val detail: String,
        val lastHeardMs: Long,
        val unread: Int,
        val selected: Boolean,
        val receiving: Boolean,
    )

    sealed class ThreadItem(val timeMs: Long) {
        class Incoming(val message: RolledMessage, val color: Int) : ThreadItem(message.lastHeardMs)
        class Outgoing(val to: String?, val text: String, val sentMs: Long, val laneHz: Float) : ThreadItem(sentMs)
    }

    data class Marker(val label: String, val hz: Float, val color: Int)

    data class Live(val sender: String?, val laneHz: Float, val frames: Int, val text: String, val color: Int)

    /** A station placed on the map at the centre of its grid square. */
    data class MapStation(val call: String, val grid: String, val position: LatLon, val color: Int, val lastHeardMs: Long, val snr: Int)

    /** A received report with a location. */
    data class MapReport(
        val id: Long,
        val report: Reports.Report,
        val sender: String?,
        val position: LatLon,
        val timeMs: Long,
        val color: Int,
    )

    data class MapData(val stations: List<MapStation>, val reports: List<MapReport>)

    private data class Heard(
        var lastHeardMs: Long,
        var laneHz: Float,
        var snr: Int,
        var grid: String?,
    )

    private val engine = RollupEngine()
    private val heard = mutableMapOf<String, Heard>()
    private val outgoing = mutableListOf<ThreadItem.Outgoing>()
    private val lastViewedMs = mutableMapOf<String, Long>()
    private var selectedKey: String = ALL

    private val _stations = MutableLiveData<List<StationRow>>(emptyList())
    val stations: LiveData<List<StationRow>> = _stations
    private val _thread = MutableLiveData<List<ThreadItem>>(emptyList())
    val thread: LiveData<List<ThreadItem>> = _thread
    private val _live = MutableLiveData<Live?>(null)
    val live: LiveData<Live?> = _live
    private val _markers = MutableLiveData<List<Marker>>(emptyList())
    val markers: LiveData<List<Marker>> = _markers
    private val _mapData = MutableLiveData(MapData(emptyList(), emptyList()))
    val mapData: LiveData<MapData> = _mapData
    private val _selected = MutableLiveData(ALL)
    val selected: LiveData<String> = _selected

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            engine.tick(System.currentTimeMillis())
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        handler.postDelayed(ticker, TICK_MS)
    }

    override fun onCleared() {
        handler.removeCallbacks(ticker)
    }

    /** One decoded frame from the engine service. */
    fun addFrame(utc: Int, snr: Int, frequency: Float, text: String, type: Int, mode: Int) =
        addFrameAt(utc, snr, frequency, text, type, mode, System.currentTimeMillis())

    private fun addFrameAt(utc: Int, snr: Int, frequency: Float, text: String, type: Int, mode: Int, now: Long) {
        val frame = RollupEngine.Frame(utc, frequency, type, text, snr, mode, now)
        engine.addFrame(frame, now)
        if (frame.isFirst) {
            HeaderParser.parse(text)?.let { header ->
                val h = heard.getOrPut(header.sender) { Heard(now, frequency, snr, null) }
                h.lastHeardMs = now
                h.laneHz = frequency
                h.snr = snr
                gridIn(text)?.let { h.grid = it }
            }
        }
        publish()
    }

    /** Something this station transmitted. */
    fun addOutgoing(text: String, frequency: Float) {
        val header = HeaderParser.parse(text)
        val to = header?.recipient
        val body = if (header != null) text.substring(minOf(header.headerLength, text.length)).trim() else text.trim()
        val shownTo = to ?: firstToken(text)?.takeIf { it.startsWith("@") || looksLikeCall(it) }
        val shownBody = if (to == null && shownTo != null) text.trim().removePrefix(shownTo).trim() else body
        outgoing += ThreadItem.Outgoing(shownTo, shownBody.ifEmpty { text.trim() }, System.currentTimeMillis(), frequency)
        publish()
    }

    fun select(key: String) {
        selectedKey = key
        lastViewedMs[key] = System.currentTimeMillis()
        _selected.value = key
        publish()
    }

    /** Where Send goes for the current selection: a callsign, a group, or null for an open broadcast. */
    fun sendTarget(): String? = selectedKey.takeIf { it != ALL }

    /**
     * Test builds only: a few minutes of made-up traffic, including two damaged
     * copies of one message (to show the merge) and a message with a missing frame.
     */
    fun loadDemo() {
        val now = System.currentTimeMillis()
        val slot0 = (now / 15_000L - 34) * 15_000L // 34 slots (8.5 min) ago
        fun at(slot: Int, hz: Float, snr: Int, text: String, type: Int) {
            val start = slot0 + slot * 15_000L
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = start }
            val utc = cal.get(java.util.Calendar.HOUR_OF_DAY) * 10000 + cal.get(java.util.Calendar.MINUTE) * 100 + cal.get(java.util.Calendar.SECOND)
            addFrameAt(utc, snr, hz, text, type, 0, start + 14_000L)
        }
        val road = listOf("KN4XYZ: @TEAM " to 1, "ROAD BLOCKED AT BRIDGE" to 4, ", REROUTING VIA " to 4, "COUNTY 12, ETA 1" to 4, "630" to 6)
        at(0, 690f, -14, "AC4LT: @HB HEARTBEAT EM72", 3)
        at(0, 1180f, -9, "KN4XYZ: @HB HEARTBEAT EM73", 3)
        at(0, 905f, -16, "N0DEF: @HB HEARTBEAT EM74", 3)
        at(0, 2140f, -5, "W4QRP: @HB HEARTBEAT EM83", 3)
        road.forEachIndexed { i, (t, ty) -> if (i != 2) at(1 + i, 1450f, -12, t, ty) }
        listOf("N0DEF: @TEAM " to 1, "ARRIVED AT CP1, " to 4, "ALL GOOD" to 6).forEachIndexed { i, (t, ty) -> at(2 + i, 820f, -17, t, ty) }
        road.forEachIndexed { i, (t, ty) -> if (i != 3) at(8 + i, 1520f, -10, t, ty) }
        listOf("W4QRP: KQ4BR " to 1, "CAN YOU RELAY TO " to 4, "KF4MNO?" to 6).forEachIndexed { i, (t, ty) -> at(14 + i, 2010f, -6, t, ty) }
        at(17, 640f, -21, "KF4MNO: @HB HEARTBEAT EM63", 3)
        listOf("KN4XYZ: @TEAM " to 1, "WATER POINT AT S" to 4, "T MARKS OPEN UNT" to 4, "IL 1800, BRING C" to 4, "ONTAINERS" to 6)
            .forEachIndexed { i, (t, ty) -> if (i != 3) at(20 + i, 1450f, -13, t, ty) }
        listOf(
            "N0DEF: @TEAM " to 1, "#SALUTE S:ABOUT 2" to 4, "0 A:LINE AT FUEL" to 4, " STATION L:16S FD" to 4,
            " 6410 3236 U:CIV" to 4, "ILIANS T:181320Z" to 4, " E:CARS" to 6,
        ).forEachIndexed { i, (t, ty) -> at(26 + i, 820f, -15, t, ty) }
        listOf("W4QRP: @TEAM " to 1, "#CHECKIN S:NEED " to 4, "L:EM83 N:WATER" to 6)
            .forEachIndexed { i, (t, ty) -> at(29 + i, 2010f, -7, t, ty) }
        engine.tick(now)
        publish()
    }

    fun clear() {
        engine.clear()
        heard.clear()
        outgoing.clear()
        publish()
    }

    // ------------------------------------------------------------------ build

    private fun publish() {
        val now = System.currentTimeMillis()
        lastViewedMs[selectedKey] = now
        val messages = engine.messages().filterNot { isBeacon(it) }
        val live = engine.live()
        val myGroups = myGroups()

        // Groups: configured ones plus any heard as recipients.
        val groupKeys = (myGroups + messages.mapNotNull { it.recipient }.filter { it.startsWith("@") })
            .filterNot { it == "@HB" }
            .distinct()
        val groupRows = groupKeys.map { g ->
            val inGroup = messages.filter { it.recipient == g }
            val last = inGroup.maxOfOrNull { it.lastHeardMs } ?: 0L
            StationRow(
                key = g, label = g, isGroup = true, color = ACCENT,
                detail = if (inGroup.isEmpty()) "No traffic yet" else "${inGroup.size} message${if (inGroup.size == 1) "" else "s"}",
                lastHeardMs = last,
                unread = inGroup.count { it.lastHeardMs > (lastViewedMs[g] ?: 0L) },
                selected = g == selectedKey,
                receiving = false,
            )
        }.sortedByDescending { it.lastHeardMs }

        val stationRows = heard.entries
            .sortedByDescending { it.value.lastHeardMs }
            .map { (call, h) ->
                val fromThem = messages.filter { it.sender == call }
                StationRow(
                    key = call, label = call, isGroup = false, color = colorFor(call),
                    detail = listOfNotNull(h.grid, "+${h.laneHz.toInt()} Hz", "${h.snr} dB").joinToString(" · "),
                    lastHeardMs = h.lastHeardMs,
                    unread = fromThem.count { it.lastHeardMs > (lastViewedMs[call] ?: 0L) },
                    selected = call == selectedKey,
                    receiving = live.any { it.sender == call },
                )
            }

        val allRow = StationRow(
            key = ALL, label = "All traffic", isGroup = true, color = ACCENT,
            detail = "${messages.size} messages · ${heard.size} stations",
            lastHeardMs = messages.maxOfOrNull { it.lastHeardMs } ?: 0L,
            unread = 0, selected = selectedKey == ALL, receiving = false,
        )
        _stations.value = listOf(allRow) + groupRows + stationRows

        // Thread for the selection.
        val key = selectedKey
        val incoming = when {
            key == ALL -> messages
            key.startsWith("@") -> messages.filter { it.recipient == key }
            else -> messages.filter { it.sender == key || it.recipient == key }
        }.map { ThreadItem.Incoming(it, colorFor(it.sender)) }
        val mine = when {
            key == ALL -> outgoing
            else -> outgoing.filter { it.to == key }
        }
        _thread.value = (incoming + mine).sortedBy { it.timeMs }

        // Live bar: prefer the selected station, else the most recent.
        val liveForKey = live.filter { key == ALL || it.sender == key || key.startsWith("@") }
            .maxByOrNull { it.lastFrameMs }
        _live.value = liveForKey?.let { Live(it.sender, it.laneHz, it.framesSoFar, it.textSoFar.trim(), colorFor(it.sender)) }

        // Map: stations with a known grid, and every report that carries a location.
        _mapData.value = MapData(
            stations = heard.mapNotNull { (call, h) ->
                val grid = h.grid ?: return@mapNotNull null
                val pos = Geo.maidenheadToLatLon(grid) ?: return@mapNotNull null
                MapStation(call, grid, pos, colorFor(call), h.lastHeardMs, h.snr)
            },
            reports = messages.mapNotNull { m ->
                val report = Reports.parse(m.bodyText) ?: return@mapNotNull null
                val pos = report.location ?: return@mapNotNull null
                MapReport(m.id, report, m.sender, pos, m.lastHeardMs, colorFor(m.sender))
            },
        )

        // Waterfall markers: stations heard in the last 10 minutes, at their latest offset.
        _markers.value = heard.filter { now - it.value.lastHeardMs < 10 * 60_000L }
            .map { (call, h) -> Marker(call, h.laneHz, colorFor(call)) }
    }

    private fun isBeacon(m: RolledMessage): Boolean {
        if (m.recipient == "@HB") return true
        val body = m.bodyText.uppercase()
        return body.startsWith("HEARTBEAT") || body.startsWith("HB ")
    }

    private fun myGroups(): List<String> {
        val raw = PreferenceManager.getDefaultSharedPreferences(getApplication())
            .getString("my_groups", "").orEmpty()
        return raw.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.length > 1 }
            .map { if (it.startsWith("@")) it else "@$it" }
    }

    companion object {
        const val ALL = "*"
        private const val TICK_MS = 5_000L
        private const val ACCENT = 0xFFF2A33A.toInt()

        /** Distinct, legible-on-dark colours; amber is reserved for the accent. */
        private val PALETTE = intArrayOf(
            0xFF4FC3F7.toInt(), 0xFFB39DFF.toInt(), 0xFFA6E35A.toInt(), 0xFFFF8FB1.toInt(),
            0xFF5EEAD4.toInt(), 0xFFFFD166.toInt(), 0xFF9DB4FF.toInt(), 0xFFF5A3FF.toInt(),
        )

        fun colorFor(call: String?): Int {
            if (call == null) return 0xFF9AA4B2.toInt()
            return PALETTE[Math.floorMod(call.hashCode(), PALETTE.size)]
        }

        private val gridRegex = Regex("""\b([A-R]{2}[0-9]{2}(?:[A-X]{2})?)\b""")
        private fun gridIn(text: String): String? =
            gridRegex.findAll(text.uppercase()).map { it.groupValues[1] }.lastOrNull()

        private fun firstToken(text: String) = text.trim().split(Regex("\\s+")).firstOrNull()?.trimEnd(':')
        private fun looksLikeCall(t: String) = Regex("""^[A-Z0-9]{1,3}[0-9][A-Z0-9/]*[A-Z]$""").matches(t.uppercase())
    }
}
