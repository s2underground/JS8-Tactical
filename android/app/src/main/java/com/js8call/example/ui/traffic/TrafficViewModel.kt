package com.js8call.example.ui.traffic

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.js8call.example.prop.Band
import com.js8call.example.prop.ListeningLog
import com.js8call.example.prop.SpaceWeatherRepository
import com.js8call.example.prop.Observation
import com.js8call.example.prop.ObservationStore
import com.js8call.example.atak.AtakBridge
import com.js8call.example.atak.Cot
import com.js8call.example.digest.DigestBuilder
import com.js8call.example.team.TeamBoard
import com.js8call.example.team.TeamMember
import com.js8call.example.team.TeamRoster
import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.example.reports.Reports
import com.js8call.example.rollup.HeaderParser
import com.js8call.example.rollup.RolledMessage
import com.js8call.example.rollup.RollupEngine
import com.js8call.example.service.JS8EngineService
import java.io.File

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
        /** When the message was first heard; stable across restarts (ATAK point ids use it). */
        val firstHeardMs: Long = timeMs,
    )

    data class MapData(val stations: List<MapStation>, val reports: List<MapReport>)

    data class DigestAlerts(val count: Int, val tone: DigestBuilder.Tone)

    private data class Heard(
        var lastHeardMs: Long,
        var laneHz: Float,
        var snr: Int,
        var grid: String?,
        val firstHeardMs: Long = lastHeardMs,
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

    /** Propagation evidence (who we heard, who heard us, on which band); kept for a week. */
    val observationStore = ObservationStore(File(application.filesDir, "observations.csv"))
    private val _observationsVersion = MutableLiveData(0)
    val observationsVersion: LiveData<Int> = _observationsVersion
    private var rigDialHz: Long? = null
    /** Set while the demo loads: its made-up stations are shown but never saved. */
    private var demoBand: Band? = null

    /** Hours spent listening per band: what makes "not heard" count as evidence. */
    val listeningLog = ListeningLog(File(application.filesDir, "listening.csv"))
    val weather = SpaceWeatherRepository(application)
    private var listening = false
    private var lastFlushMs = 0L

    private val startedMs = System.currentTimeMillis()

    /** Message history on the device (Settings: keep for N hours, or clear). */
    private val history = TrafficHistory(File(application.filesDir, "traffic-history.tsv"))
    private val historyIo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "traffic-history").apply { isDaemon = true } }
    private var replaying = false
    /** Earliest message time we hold (replayed history), for the Digest's "data since". */
    private var historyStartMs: Long? = null
    /** Messages up to this time count as already read (replayed history). */
    private var viewedBaselineMs = 0L
    private var lastPruneMs = 0L
    private val _historyLoaded = MutableLiveData(false)
    val historyLoaded: LiveData<Boolean> = _historyLoaded
    private val _digestAlerts = MutableLiveData(DigestAlerts(0, DigestBuilder.Tone.GREEN))
    /** Open needs/emergencies in the last 24 h, for the Digest button on the Traffic tab. */
    val digestAlerts: LiveData<DigestAlerts> = _digestAlerts
    private var lastDigestCheckMs = 0L

    private val _pendingCompose = MutableLiveData<String?>(null)
    /** Text another screen wants put in the compose box (e.g. a #SWX line from the Prop tab). */
    val pendingCompose: LiveData<String?> = _pendingCompose

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            engine.tick(now)
            if (listening) currentBand()?.let { listeningLog.add(now, it, TICK_MS / 60_000.0) }
            if (now - lastFlushMs > 60_000L) { listeningLog.flush(); lastFlushMs = now }
            if (now - lastPruneMs > 10 * 60_000L) { lastPruneMs = now; pruneHistory(now) }
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Receiver running or not (from the Radio screen's state). */
    fun setListening(on: Boolean) {
        listening = on
        if (!on) listeningLog.flush()
    }

    fun requestCompose(text: String) { _pendingCompose.value = text }
    fun composeConsumed() { _pendingCompose.value = null }

    /**
     * Traffic listens to the engine service itself, not through the screen, so
     * nothing is missed while the app is in the background or the screen is off.
     */
    private val serviceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            try {
                when (intent.action) {
                    JS8EngineService.ACTION_DECODE -> addFrame(
                        intent.getIntExtra(JS8EngineService.EXTRA_UTC, 0),
                        intent.getIntExtra(JS8EngineService.EXTRA_SNR, 0),
                        intent.getFloatExtra(JS8EngineService.EXTRA_FREQ, 0f),
                        intent.getStringExtra(JS8EngineService.EXTRA_TEXT) ?: "",
                        intent.getIntExtra(JS8EngineService.EXTRA_TYPE, 0),
                        intent.getIntExtra(JS8EngineService.EXTRA_MODE, 0),
                    )
                    JS8EngineService.ACTION_TX_SENT -> {
                        val text = intent.getStringExtra(JS8EngineService.EXTRA_TX_SENT_TEXT) ?: return
                        addOutgoing(text, intent.getFloatExtra(JS8EngineService.EXTRA_TX_SENT_FREQ, 0f))
                    }
                    JS8EngineService.ACTION_RADIO_FREQUENCY -> {
                        val hz = intent.getLongExtra(JS8EngineService.EXTRA_RADIO_FREQUENCY_HZ, 0L)
                        if (hz > 0) setDialFrequency(hz)
                    }
                    JS8EngineService.ACTION_ENGINE_STATE ->
                        setListening(intent.getStringExtra(JS8EngineService.EXTRA_STATE) == JS8EngineService.STATE_RUNNING)
                }
            } catch (e: Exception) {
                // One bad frame must never take the traffic picture down.
                android.util.Log.w("TrafficViewModel", "broadcast ${intent.action} failed", e)
            }
        }
    }

    init {
        handler.postDelayed(ticker, TICK_MS)
        loadHistory()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(application).registerReceiver(
            serviceReceiver,
            android.content.IntentFilter().apply {
                addAction(JS8EngineService.ACTION_DECODE)
                addAction(JS8EngineService.ACTION_TX_SENT)
                addAction(JS8EngineService.ACTION_RADIO_FREQUENCY)
                addAction(JS8EngineService.ACTION_ENGINE_STATE)
            },
        )
    }

    // ---------------------------------------------------------------- history

    private fun keepMs(): Long {
        val hours = PreferenceManager.getDefaultSharedPreferences(getApplication())
            .getString(TrafficHistory.KEY_KEEP_HOURS, TrafficHistory.DEFAULT_KEEP_HOURS.toString())?.toLongOrNull()
            ?: TrafficHistory.DEFAULT_KEEP_HOURS.toLong()
        return hours.coerceAtLeast(0) * 3_600_000L
    }

    /** Read the saved history off the main thread, then replay it through the rollup engine. */
    private fun loadHistory() {
        val keep = keepMs()
        if (keep <= 0) { historyIo.execute { history.clear() }; _historyLoaded.value = true; return }
        val since = System.currentTimeMillis() - keep
        historyIo.execute {
            val entries = history.load(since)
            handler.post { replay(entries) }
        }
    }

    private fun replay(entries: List<TrafficHistory.Entry>) {
        if (entries.isNotEmpty()) {
            replaying = true
            try {
                // Anything live that arrived while the file was being read stays; replayed frames go underneath.
                val live = engine.messages().isNotEmpty()
                if (!live) {
                    entries.forEach { e ->
                        when (e) {
                            is TrafficHistory.Entry.Frame -> with(e.frame) { addFrameAt(utc, snr, frequencyHz, text, type, mode, receivedAtMs) }
                            is TrafficHistory.Entry.Sent -> addOutgoingAt(e.text, e.frequencyHz, e.timeMs)
                        }
                    }
                    historyStartMs = entries.first().timeMs
                }
            } catch (e: Exception) {
                android.util.Log.w("TrafficViewModel", "history replay failed", e)
            } finally {
                replaying = false
            }
            val now = System.currentTimeMillis()
            engine.tick(now)
            // Evidence in old messages was logged when they were first heard.
            engine.messages().forEach { reportedIds += it.id }
            viewedBaselineMs = now
        }
        _historyLoaded.value = true
        publish()
    }

    private fun record(entry: TrafficHistory.Entry) {
        if (replaying || demoBand != null || keepMs() <= 0) return
        historyIo.execute { history.append(entry) }
    }

    private fun pruneHistory(now: Long) {
        val keep = keepMs()
        if (keep <= 0) return
        val cutoff = now - keep
        engine.prune(cutoff)
        outgoing.removeAll { it.sentMs < cutoff }
        heard.entries.removeAll { it.value.lastHeardMs < cutoff }
        historyStartMs = historyStartMs?.coerceAtLeast(cutoff)
        historyIo.execute { history.prune(cutoff) }
    }

    /** Settings changed how long to keep history: apply now (0 = stop saving and delete the file). */
    fun historySettingChanged() {
        if (keepMs() <= 0) historyIo.execute { history.clear() } else pruneHistory(System.currentTimeMillis())
        publish()
    }

    /** Delete all saved and shown traffic (messages, sent, stations heard). Propagation logs are kept. */
    fun clearHistory() {
        engine.clear()
        heard.clear()
        outgoing.clear()
        reportedIds.clear()
        gridIds.clear()
        lastViewedMs.clear()
        historyStartMs = null
        historyIo.execute { history.clear() }
        publish()
    }

    fun historySizeBytes(): Long = history.sizeBytes()

    override fun onCleared() {
        handler.removeCallbacks(ticker)
        listeningLog.flush()
    }

    /** One decoded frame from the engine service. */
    fun addFrame(utc: Int, snr: Int, frequency: Float, text: String, type: Int, mode: Int) =
        addFrameAt(utc, snr, frequency, text, type, mode, System.currentTimeMillis())

    private fun addFrameAt(utc: Int, snr: Int, frequency: Float, text: String, type: Int, mode: Int, now: Long) {
        val frame = RollupEngine.Frame(utc, frequency, type, text, snr, mode, now)
        engine.addFrame(frame, now)
        record(TrafficHistory.Entry.Frame(frame))
        if (frame.isFirst) {
            HeaderParser.parse(text)?.takeUnless { isMe(it.sender) }?.let { header ->
                val h = heard.getOrPut(header.sender) { Heard(now, frequency, snr, null) }
                h.lastHeardMs = now
                h.laneHz = frequency
                h.snr = snr
                gridIn(text)?.let { h.grid = it }
                if (!replaying) currentBand()?.let { band ->
                    observe(Observation(now, header.sender, h.grid, band, snr, Observation.Kind.HEARD))
                }
            }
        }
        if (!replaying) requestPublish()
    }

    /**
     * A busy slot ends with a burst of decodes at once; rebuild the picture once
     * for the burst instead of once per frame.
     */
    private fun requestPublish() {
        if (publishPending) return
        publishPending = true
        handler.postDelayed(publishSoon, 300L)
    }
    private var publishPending = false
    private val publishSoon = Runnable { publishPending = false; publish() }

    /** Dial frequency from rig control (null or 0 when there is none). */
    fun setDialFrequency(hz: Long?) {
        rigDialHz = hz?.takeIf { it > 0 }
    }

    /** Band we are listening on: rig frequency if known, else the frequency picked on the Radio tab. */
    fun currentBand(): Band? {
        demoBand?.let { return it }
        rigDialHz?.let { return Band.forDialHz(it) }
        val saved = PreferenceManager.getDefaultSharedPreferences(getApplication())
            .getString("last_frequency", "")?.trim()?.toLongOrNull()
        return Band.forDialHz(saved)
    }

    /** Last grid heard from [call], for observations logged before their grid was known. */
    fun gridFor(call: String): String? = heard[call]?.grid

    private fun observe(o: Observation) {
        if (observationStore.add(o, persist = demoBand == null)) _observationsVersion.value = (_observationsVersion.value ?: 0) + 1
    }

    /**
     * Propagation evidence in other people's traffic, all usable offline:
     *  - "A: B SNR -12"   A heard B (if B is us: our signal reached A)
     *  - "A: B HEARING C D"  A is hearing C and D
     *  - "#SWX F:116 K:1 T:181900Z"  solar numbers relayed by another station
     */
    private fun noteNetEvidence(messages: List<RolledMessage>) {
        val me = PreferenceManager.getDefaultSharedPreferences(getApplication())
            .getString("callsign", "").orEmpty().trim().uppercase().substringBefore('/')
        val band = currentBand()
        messages.forEach { m ->
            if (m.id in reportedIds) return@forEach
            val sender = m.sender ?: return@forEach
            val body = m.bodyText.uppercase()
            val to = m.recipient?.uppercase()
            var used = false
            if (band != null && to != null && !to.startsWith("@")) {
                snrReport.find(body)?.groupValues?.get(1)?.toIntOrNull()?.let { snr ->
                    used = true
                    if (me.isNotEmpty() && to.substringBefore('/') == me) {
                        observe(Observation(m.lastHeardMs, sender, heard[sender]?.grid, band, snr, Observation.Kind.HEARD_ME))
                    } else {
                        observe(Observation(m.lastHeardMs, to, heard[to]?.grid, band, snr, Observation.Kind.PATH, by = sender))
                    }
                }
            }
            if (band != null) {
                hearingList.find(body)?.groupValues?.get(1)?.split(Regex("\\s+"))?.filter { looksLikeCall(it) }?.forEach { call ->
                    used = true
                    if (me.isEmpty() || call.substringBefore('/') != me) {
                        observe(Observation(m.lastHeardMs, call, heard[call]?.grid, band, 0, Observation.Kind.PATH, by = sender))
                    }
                }
            }
            if (m.isComplete) Reports.parse(m.bodyText)?.takeIf { it.type == Reports.SWX }?.let { r ->
                used = true
                val sfi = r.values["F"]?.toDoubleOrNull()
                val k = r.values["K"]?.toDoubleOrNull()
                if (sfi != null && k != null) {
                    val at = r.values["T"]?.let { Reports.dtgToMs(it) } ?: m.lastHeardMs
                    if (weather.offerRelayed(sfi, k, at, sender) != null) {
                        _observationsVersion.value = (_observationsVersion.value ?: 0) + 1
                    }
                }
            }
            if (used && m.isComplete) reportedIds += m.id
        }
    }

    private val reportedIds = mutableSetOf<Long>()

    /**
     * Grids that arrive in the body of a message, e.g. an answer to GRID?
     * ("X: ME GRID EN90QK") or a heartbeat split over frames. The first-frame
     * check in [addFrameAt] misses these.
     */
    private fun noteGrids(messages: List<RolledMessage>) {
        messages.forEach { m ->
            if (m.id in gridIds) return@forEach
            val sender = m.sender ?: return@forEach
            if (isMe(sender)) { gridIds += m.id; return@forEach }
            val grid = gridStatement.find(m.bodyText.uppercase())?.groupValues?.get(1) ?: return@forEach
            heard[sender]?.let { h ->
                // Keep the more precise of two grids in the same square.
                if (h.grid == null || !(h.grid!!.length > grid.length && h.grid!!.startsWith(grid))) h.grid = grid
            }
            if (m.isComplete) gridIds += m.id
        }
    }
    private val gridIds = mutableSetOf<Long>()

    private fun myBaseCall(): String = PreferenceManager.getDefaultSharedPreferences(getApplication())
        .getString("callsign", "").orEmpty().trim().uppercase().substringBefore('/')

    /** Our own signal decoded (echo, or a station relaying it): never a "heard" station. */
    private fun isMe(call: String?): Boolean {
        val me = myBaseCall()
        return me.isNotEmpty() && call != null && call.uppercase().substringBefore('/') == me
    }

    /** Something this station transmitted. */
    fun addOutgoing(text: String, frequency: Float) {
        val now = System.currentTimeMillis()
        record(TrafficHistory.Entry.Sent(now, frequency, text))
        addOutgoingAt(text, frequency, now)
    }

    private fun addOutgoingAt(text: String, frequency: Float, sentMs: Long) {
        val header = HeaderParser.parse(text)
        val to = header?.recipient
        val body = if (header != null) text.substring(minOf(header.headerLength, text.length)).trim() else text.trim()
        val shownTo = to ?: firstToken(text)?.takeIf { it.startsWith("@") || looksLikeCall(it) }
        val shownBody = if (to == null && shownTo != null) text.trim().removePrefix(shownTo).trim() else body
        outgoing += ThreadItem.Outgoing(shownTo, shownBody.ifEmpty { text.trim() }, sentMs, frequency)
        if (!replaying) requestPublish()
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
        demoBand = currentBand() ?: Band.B40
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
        demoBand = null
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
        val all = engine.messages()
        val messages = all.filterNot { isBeacon(it) }
        // Heartbeat replies ("X: ME HEARTBEAT SNR -19") are the best "they heard me" evidence: scan everything.
        noteNetEvidence(all)
        noteGrids(all)
        val live = engine.live()
        val myGroups = myGroups()

        // Groups: configured ones plus any heard as recipients.
        val groupKeys = (myGroups + messages.mapNotNull { it.recipient }.filter { it.startsWith("@") })
            .filterNot { it == "@HB" }
            .distinct()
        val byRecipient = messages.groupBy { it.recipient }
        val groupRows = groupKeys.map { g ->
            val inGroup = byRecipient[g].orEmpty()
            val last = inGroup.maxOfOrNull { it.lastHeardMs } ?: 0L
            StationRow(
                key = g, label = g, isGroup = true, color = ACCENT,
                detail = if (inGroup.isEmpty()) "No traffic yet" else "${inGroup.size} message${if (inGroup.size == 1) "" else "s"}",
                lastHeardMs = last,
                unread = inGroup.count { it.lastHeardMs > (lastViewedMs[g] ?: viewedBaselineMs) },
                selected = g == selectedKey,
                receiving = false,
            )
        }.sortedByDescending { it.lastHeardMs }

        val bySender = messages.groupBy { it.sender }
        val stationRows = heard.entries
            .sortedByDescending { it.value.lastHeardMs }
            .map { (call, h) ->
                val fromThem = bySender[call].orEmpty()
                StationRow(
                    key = call, label = call, isGroup = false, color = colorFor(call),
                    detail = listOfNotNull(h.grid, "+${h.laneHz.toInt()} Hz", "${h.snr} dB").joinToString(" · "),
                    lastHeardMs = h.lastHeardMs,
                    unread = fromThem.count { it.lastHeardMs > (lastViewedMs[call] ?: viewedBaselineMs) },
                    selected = call == selectedKey,
                    receiving = live.any { it.sender == call },
                )
            }

        // Team shortcut (opens the Team tab): how many members were heard in the last 24 h.
        val roster = TeamRoster.decode(PreferenceManager.getDefaultSharedPreferences(getApplication()).getString(TeamRoster.KEY, null))
        val heardBase = heard.filter { now - it.value.lastHeardMs < 24 * 3_600_000L }.keys.map(TeamMember::baseOf).toSet()
        val teamRow = StationRow(
            key = TEAM, label = "My team", isGroup = true, color = ACCENT,
            detail = if (roster.isEmpty()) "Add your team's callsigns" else "${roster.count { it.baseCall in heardBase }} of ${roster.size} heard in 24 h",
            lastHeardMs = 0L, unread = 0, selected = false, receiving = false,
        )

        val allRow = StationRow(
            key = ALL, label = "All traffic", isGroup = true, color = ACCENT,
            detail = "${messages.size} messages · ${heard.size} stations",
            lastHeardMs = messages.maxOfOrNull { it.lastHeardMs } ?: 0L,
            unread = 0, selected = selectedKey == ALL, receiving = false,
        )
        _stations.value = listOf(allRow, teamRow) + groupRows + stationRows

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
                val report = parsedReport(m) ?: return@mapNotNull null
                val pos = report.location ?: return@mapNotNull null
                MapReport(m.id, report, m.sender, pos, m.lastHeardMs, colorFor(m.sender), m.firstHeardMs)
            },
        )

        // Digest button badge, at most every few seconds.
        if (now - lastDigestCheckMs >= TICK_MS) {
            lastDigestCheckMs = now
            val d = DigestBuilder.build(digestInput(now - 24 * 3_600_000L, now))
            val count = d.sections.first().lines.count { it.tone == DigestBuilder.Tone.RED || it.tone == DigestBuilder.Tone.AMBER }
            if (_digestAlerts.value != DigestAlerts(count, d.tone)) _digestAlerts.value = DigestAlerts(count, d.tone)
        }

        // ATAK: the same reports (and optionally stations) as spot-map points. Demo data never goes out.
        if (demoBand == null) sendToAtak(_mapData.value!!, now)

        // Waterfall markers: stations heard in the last 10 minutes, at their latest offset.
        _markers.value = heard.filter { now - it.value.lastHeardMs < 10 * 60_000L }
            .map { (call, h) -> Marker(call, h.laneHz, colorFor(call)) }
    }

    /** What the Team tab needs: last heard per base callsign and recent messages. */
    fun teamHeard(): Map<String, TeamBoard.Heard> {
        val out = HashMap<String, TeamBoard.Heard>()
        heard.forEach { (call, h) ->
            val key = TeamMember.baseOf(call)
            val prev = out[key]
            if (prev == null || h.lastHeardMs > prev.lastHeardMs) out[key] = TeamBoard.Heard(h.lastHeardMs, h.snr, h.grid ?: prev?.grid)
        }
        return out
    }

    fun teamMessages(): List<TeamBoard.Msg> =
        engine.messages().filterNot { isBeacon(it) }.map { TeamBoard.Msg(it.sender, it.bodyText, it.lastHeardMs) }

    /** Everything the Digest tab needs for the window starting at [sinceMs]. */
    fun digestInput(sinceMs: Long, now: Long = System.currentTimeMillis()): DigestBuilder.Input {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val messages = engine.messages().filterNot { isBeacon(it) }.map { m ->
            DigestBuilder.Msg(m.id, m.sender, m.recipient, m.bodyText, m.lastHeardMs, m.isComplete, m.missingFrames, m.bestSnr)
        }
        val sent = outgoing.map { DigestBuilder.Sent(it.to, it.text, it.sentMs) }
        val stations = heard.map { (call, h) -> DigestBuilder.Station(call, h.grid, h.firstHeardMs, h.lastHeardMs, h.snr) }
        val listenedMin = runCatching {
            listeningLog.hours(0.0).filterKeys { it.first >= sinceMs - 3_600_000L }.values.sum()
        }.getOrNull()
        val wx = runCatching { weather.effective(now, now) }.getOrNull()
        val solar = wx?.takeIf { it.updatedMs > 0 }?.let {
            val ageH = ((now - it.updatedMs) / 3_600_000L).coerceAtLeast(0)
            "SFI ${it.sfi.toInt()}, K ${"%.0f".format(java.util.Locale.US, it.kIndex)} (${it.source}, ${ageH} h old)"
        }
        return DigestBuilder.Input(
            nowMs = now,
            sinceMs = sinceMs,
            myCall = prefs.getString("callsign", "").orEmpty().trim().uppercase().substringBefore('/'),
            myGrid = prefs.getString("grid", null),
            myGroups = myGroups(),
            messages = messages,
            sent = sent,
            stations = stations,
            listeningHours = listenedMin?.let { it / 60.0 }?.takeIf { it > 0 },
            solar = solar,
            dataStartMs = minOf(startedMs, historyStartMs ?: startedMs),
        )
    }

    private fun sendToAtak(map: MapData, now: Long) {
        val app = getApplication<Application>()
        if (!AtakBridge.enabled(app)) return
        val stale = AtakBridge.staleAfterMs(app)
        val points = map.reports.map { r ->
            Cot.reportPoint(r.report, r.sender, r.position, r.timeMs, r.firstHeardMs / 1000, stale, now)
        } + if (AtakBridge.includeStations(app)) map.stations.map { s ->
            Cot.stationPoint(s.call, s.grid, s.position, s.lastHeardMs, s.snr, stale, now)
        } else emptyList()
        try {
            AtakBridge.sync(app, points, now)
        } catch (e: Exception) {
            android.util.Log.w("TrafficViewModel", "ATAK sync failed", e)
        }
    }

    /** Report parsing is regex work; cache it per message until its text changes. */
    private val reportCache = HashMap<Long, Pair<String, Reports.Report?>>()
    private fun parsedReport(m: RolledMessage): Reports.Report? {
        val body = m.bodyText
        reportCache[m.id]?.let { (text, r) -> if (text == body) return r }
        val r = Reports.parse(body)
        reportCache[m.id] = body to r
        if (reportCache.size > 4 * (engine.messages().size + 64)) {
            val live = engine.messages().mapTo(HashSet()) { it.id }
            reportCache.keys.retainAll(live)
        }
        return r
    }

    private fun isBeacon(m: RolledMessage): Boolean {
        if (m.recipient == "@HB") return true
        val body = m.bodyText.uppercase()
        if (!(body.startsWith("HEARTBEAT") || body.startsWith("HB "))) return false
        // A heartbeat reply addressed to us ("HEARTBEAT SNR -19") is worth seeing in the thread.
        return !isMe(m.recipient)
    }

    private fun myGroups(): List<String> {
        val raw = PreferenceManager.getDefaultSharedPreferences(getApplication())
            .getString("my_groups", "").orEmpty()
        return raw.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.length > 1 }
            .map { if (it.startsWith("@")) it else "@$it" }
    }

    companion object {
        @Volatile private var instance: TrafficViewModel? = null

        /**
         * The one Traffic model for the whole app. It lives as long as the process
         * (not one screen), so decodes keep landing in history, Digest, Team, ATAK
         * and the propagation log while the app is in the background.
         * Call on the main thread.
         */
        fun of(context: android.content.Context): TrafficViewModel =
            instance ?: synchronized(this) {
                instance ?: TrafficViewModel(context.applicationContext as Application).also { instance = it }
            }

        const val ALL = "*"
        /** Station-list row that opens the Team tab. */
        const val TEAM = "#TEAM"
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

        private val snrReport = Regex("""\bSNR\s+([+-]?\d{1,2})\b""")
        private val hearingList = Regex("""\bHEARING\s+([A-Z0-9/ ]+)""")
        /** "GRID EN90QK" or "HEARTBEAT EN90" in a message body. */
        private val gridStatement = Regex("""\b(?:GRID|HEARTBEAT|HB)\s+([A-R]{2}[0-9]{2}(?:[A-X]{2})?)\b""")
        private val gridRegex = Regex("""\b([A-R]{2}[0-9]{2}(?:[A-X]{2})?)\b""")
        private fun gridIn(text: String): String? =
            gridRegex.findAll(text.uppercase()).map { it.groupValues[1] }.lastOrNull()

        private fun firstToken(text: String) = text.trim().split(Regex("\\s+")).firstOrNull()?.trimEnd(':')
        private fun looksLikeCall(t: String) = Regex("""^[A-Z0-9]{1,3}[0-9][A-Z0-9/]*[A-Z]$""").matches(t.uppercase())
    }
}
