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
    data class MapStation(
        val call: String, val grid: String, val position: LatLon, val color: Int, val lastHeardMs: Long, val snr: Int,
        /** Band labels ("40m") it was heard on; empty if not known (heard before bands were kept). */
        val bands: Set<String> = emptySet(),
    )

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

    /**
     * Two other stations heard working each other (neither is us). [snr] is the
     * latest signal report one gave the other, sent by [reporter]. [confirmed]:
     * there is evidence a signal got through, not just one station calling.
     */
    data class MapLink(
        val a: String,
        val b: String,
        val aPos: LatLon,
        val bPos: LatLon,
        val snr: Int?,
        val reporter: String?,
        val confirmed: Boolean,
        val lastHeardMs: Long,
        val bands: Set<String> = emptySet(),
    )

    data class MapData(val stations: List<MapStation>, val reports: List<MapReport>, val links: List<MapLink> = emptyList())

    data class DigestAlerts(val count: Int, val tone: DigestBuilder.Tone)

    private data class Heard(
        var lastHeardMs: Long,
        var laneHz: Float,
        var snr: Int,
        var grid: String?,
        val firstHeardMs: Long = lastHeardMs,
        /** Dial frequency we were on when last heard: waterfall tags only show on that frequency. */
        var dialHz: Long? = null,
    )

    /**
     * Our own transmission decoded back by our receiver: where it was heard ([heardHz])
     * against where we sent it ([sentHz]). Ground truth for the waterfall markers.
     */
    data class EchoCheck(val heardHz: Float, val sentHz: Float, val atMs: Long) {
        val offByHz: Float get() = heardHz - sentHz
        val mismatch: Boolean get() = kotlin.math.abs(offByHz) > ECHO_TOLERANCE_HZ
    }

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

    private val _ownEcho = MutableLiveData<EchoCheck?>(null)
    /** Latest own-echo reading (null until our receiver decodes one of our own sends). */
    val ownEcho: LiveData<EchoCheck?> = _ownEcho
    /** Own-callsign frames decoded back, waiting to be matched to what we sent (offset, time). */
    private val echoFrames = mutableListOf<Pair<Float, Long>>()

    /** Band/frequency changes clear the waterfall tags right away. */
    private val dialPrefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "last_frequency") handler.post { refreshMarkers(System.currentTimeMillis()) }
    }
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
    /** Station grids and who-worked-whom for the Map, kept across restarts (7 days). */
    private val mapMemory = com.js8call.example.ui.map.MapMemory()
    private val mapMemoryFile = File(application.filesDir, "map-memory.tsv")
    private var lastMapSaveMs = 0L
    /** Made-up stations from the demo: shown, never saved. */
    private val demoCalls = mutableSetOf<String>()
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
            safeEngine { engine.tick(now) }
            if (listening) currentBand()?.let { listeningLog.add(now, it, TICK_MS / 60_000.0) }
            if (now - lastFlushMs > 60_000L) { listeningLog.flush(); lastFlushMs = now }
            if (now - lastPruneMs > 10 * 60_000L) { lastPruneMs = now; pruneHistory(now) }
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Receiver running or not (from the Radio screen's state). */
    fun setListening(on: Boolean) {
        val wasListening = listening
        listening = on
        if (!on) {
            listeningLog.flush()
            // Receiver off: nothing is still arriving, so close half-received transmissions now
            // (otherwise "RX…" stays on a station until the idle timeout).
            if (wasListening) { safeEngine { engine.tick(System.currentTimeMillis() + 10 * 60_000L) }; requestPublish() }
        }
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
        loadMapMemory()
        PreferenceManager.getDefaultSharedPreferences(application).registerOnSharedPreferenceChangeListener(dialPrefListener)
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
    /** Set when this start skipped the saved history because the app kept crashing. */
    private val _safeStart = MutableLiveData<String?>(null)
    val safeStart: LiveData<String?> = _safeStart
    fun safeStartShown() { _safeStart.value = null }

    private fun loadHistory() {
        // Crash loop guard: if the app crashed twice in a few minutes, something in the saved
        // history may be what keeps bringing it down. Set it aside (kept, not deleted) and start clean.
        if (com.js8call.example.CrashReporter.takeCrashLoop(getApplication())) {
            val aside = history.setAside()
            _safeStart.value = "The app crashed more than once in the last few minutes, so it started with an empty " +
                "message list. Your earlier history was set aside" + (aside?.let { " ($it)" } ?: "") +
                " and is included in the diagnostic report."
            _historyLoaded.value = true
            publish()
            return
        }
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
            safeEngine { engine.tick(now) }
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
        mapMemory.clear()
        linkIds.clear()
        demoCalls.clear()
        historyIo.execute { history.clear(); mapMemoryFile.delete() }
        publish()
    }

    fun historySizeBytes(): Long = history.sizeBytes()

    private fun loadMapMemory() {
        historyIo.execute {
            val loaded = com.js8call.example.ui.map.MapMemory.load(mapMemoryFile) ?: return@execute
            handler.post {
                mapMemory.merge(loaded)
                mapMemory.prune(System.currentTimeMillis() - com.js8call.example.ui.map.MapMemory.KEEP_MS)
                publish()
            }
        }
    }

    /** Write the map memory a little after it changes (not on every decode). */
    private fun saveMapMemorySoon(now: Long, force: Boolean = false) {
        if (!mapMemory.dirty || (!force && now - lastMapSaveMs < 15_000L)) return
        lastMapSaveMs = now
        mapMemory.prune(now - com.js8call.example.ui.map.MapMemory.KEEP_MS)
        val text = mapMemory.serialize(exclude = demoCalls.toSet())
        historyIo.execute { com.js8call.example.ui.map.MapMemory.save(mapMemoryFile, text) }
    }

    /**
     * Who is working whom, from other stations' directed traffic:
     *  - "A: B ..."           A called B
     *  - "A: B SNR -12"       A heard B at -12 dB (also heartbeat replies)
     *  - "A: B HEARING C D"   A is hearing C and D
     * Traffic to or from us is left out: the Map already draws our own paths.
     */
    private fun noteLinks(messages: List<RolledMessage>) {
        messages.forEach { m ->
            if (m.id in linkIds) return@forEach
            val from = m.sender?.uppercase() ?: return@forEach
            if (isMe(from)) { linkIds += m.id; return@forEach }
            val to = m.recipient?.uppercase()
            val body = m.bodyText.uppercase()
            if (to != null && !to.startsWith("@") && looksLikeCall(to) && !isMe(to)) {
                val snr = if (m.isComplete) snrReport.find(body)?.groupValues?.get(1)?.toIntOrNull() else null
                mapMemory.noteLink(from, to, m.lastHeardMs, snr, band = bandOf(from))
            }
            if (m.isComplete) {
                hearingList.find(body)?.groupValues?.get(1)?.split(Regex("\\s+"))
                    ?.filter { looksLikeCall(it) && !isMe(it) }
                    ?.forEach { mapMemory.noteLink(from, it, m.lastHeardMs, hearing = true, band = bandOf(from)) }
                linkIds += m.id
            }
        }
    }
    private val linkIds = mutableSetOf<Long>()

    override fun onCleared() {
        handler.removeCallbacks(ticker)
        saveMapMemorySoon(System.currentTimeMillis(), force = true)
        listeningLog.flush()
    }

    /** One decoded frame from the engine service. */
    fun addFrame(utc: Int, snr: Int, frequency: Float, text: String, type: Int, mode: Int) =
        addFrameAt(utc, snr, frequency, text, type, mode, System.currentTimeMillis())

    private fun addFrameAt(utc: Int, snr: Int, frequency: Float, text: String, type: Int, mode: Int, now: Long) {
        val frame = RollupEngine.Frame(utc, frequency, type, text, snr, mode, now)
        safeEngine { engine.addFrame(frame, now) }
        record(TrafficHistory.Entry.Frame(frame))
        if (frame.isFirst) {
            val header = HeaderParser.parse(text)
            if (header != null && isMe(header.sender) && !replaying && demoBand == null) echoFrames += frequency to now
            header?.takeUnless { isMe(it.sender) }?.let { header ->
                val h = heard.getOrPut(header.sender) { Heard(now, frequency, snr, null) }
                h.lastHeardMs = now
                h.laneHz = frequency
                h.snr = snr
                h.dialHz = if (demoBand != null) null else currentDialHz()
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
        val before = currentDialHz()
        rigDialHz = hz?.takeIf { it > 0 }
        if (before != currentDialHz()) refreshMarkers(System.currentTimeMillis())
    }

    /** Dial frequency we're on: the radio's if rig control reports it, else the one picked in the app. */
    private fun currentDialHz(): Long? =
        rigDialHz ?: PreferenceManager.getDefaultSharedPreferences(getApplication())
            .getString("last_frequency", "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /**
     * Relay options for reaching [target] on the band we're on now: who heard us, who we
     * hear, and who has been heard working [target] (see [RelayFinder]).
     */
    fun relayPlan(target: String): RelayFinder.Plan {
        val now = System.currentTimeMillis()
        val band = currentBand()
        val label = band?.label
        val stations = HashMap<String, RelayFinder.Station>()
        heard.forEach { (call, h) ->
            val hBand = Band.forDialHz(h.dialHz)
            if (band != null && hBand != null && hBand != band) return@forEach
            stations[call.uppercase()] = RelayFinder.Station(call.uppercase(), h.snr, h.lastHeardMs, null, null)
        }
        observationStore.all()
            .filter { it.kind == Observation.Kind.HEARD_ME && (band == null || it.band == band) }
            .groupBy { it.call.uppercase() }
            .forEach { (call, list) ->
                val latest = list.maxBy { it.timeMs }
                val prev = stations[call]
                stations[call] = (prev ?: RelayFinder.Station(call, null, null, null, null))
                    .copy(heardUsSnr = latest.snr, heardUsMs = latest.timeMs)
            }
        val links = mapMemory.links()
            .filter { label == null || it.bands.isEmpty() || label in it.bands }
            .map { l ->
                RelayFinder.Link(l.a, l.b, l.lastHeardMs, l.snr, l.reporter, bothWays = l.seen and 3 == 3, hearing = l.seen and 4 != 0)
            }
        return RelayFinder.plan(target, stations, links, now)
    }

    /** Band [call] was on when we last heard it (the dial we were on then), else the band we're on now. */
    private fun bandOf(call: String): String? =
        if (demoBand != null) demoBand?.label
        else (Band.forDialHz(heard[call]?.dialHz) ?: Band.forDialHz(currentDialHz()))?.label

    /** Waterfall tags: stations heard in the last [MARKER_MS] on the frequency we're on now. */
    private fun refreshMarkers(now: Long) {
        val dial = currentDialHz()
        _markers.value = heard.filter { (_, h) ->
            now - h.lastHeardMs < MARKER_MS &&
                (dial == null || h.dialHz == null || kotlin.math.abs(h.dialHz!! - dial) < 1_500L)
        }.map { (call, h) -> Marker(call, h.laneHz, colorFor(call)) }
    }

    /**
     * What got louder in the receive audio while we sent (see OwnSignalProbe). Logged for
     * diagnostics. Not used to claim where our signal is (only a decoded echo does that):
     * another station keying in our slot also looks "new". Used instead to find spots that
     * light up every time we transmit, whatever offset we send on: those are not our JS8
     * signal (an artifact of the radio or interface while keyed), and the waterfall says so.
     */
    fun reportOwnSignal(reading: com.js8call.example.ui.OwnSignalProbe.Reading) {
        val now = System.currentTimeMillis()
        spectrumReadings += reading to now
        spectrumReadings.removeAll { now - it.second > 2 * 3_600_000L }
        while (spectrumReadings.size > 12) spectrumReadings.removeAt(0)
        // A spot is an artifact when it showed up in at least 3 sends made at offsets at least
        // 100 Hz apart, and never right at the offset being sent.
        val artifacts = mutableListOf<Float>()
        reading.peaks.forEach { p ->
            val hits = spectrumReadings.filter { (r, _) ->
                r.peaks.any { kotlin.math.abs(it.hz - p.hz) <= 30f } && kotlin.math.abs(p.hz - r.sentHz) > 80f
            }
            val offsets = hits.map { it.first.sentHz }
            if (hits.size >= 3 && (offsets.maxOrNull() ?: 0f) - (offsets.minOrNull() ?: 0f) > 100f) artifacts += p.hz
        }
        val merged = (_txArtifacts.value.orEmpty() + artifacts)
            .sorted().fold(mutableListOf<Float>()) { acc, hz -> if (acc.isEmpty() || hz - acc.last() > 40f) acc += hz; acc }
        if (merged != _txArtifacts.value) _txArtifacts.value = merged
        val line = String.format(
            java.util.Locale.US, "%s spectrum while sending at %.0f Hz: louder at %s; at the sent offset %+.0f dB%s",
            java.time.Instant.ofEpochMilli(now).toString(), reading.sentHz,
            reading.peaks.joinToString(", ") { String.format(java.util.Locale.US, "%.0f Hz (+%.0f dB)", it.hz, it.riseDb) },
            reading.riseAtSentDb,
            if (artifacts.isNotEmpty()) " · appears on every send at " + artifacts.joinToString { "${it.toInt()} Hz" } else "",
        )
        historyIo.execute { OwnEchoLog.append(getApplication(), line) }
    }
    private val spectrumReadings = mutableListOf<Pair<com.js8call.example.ui.OwnSignalProbe.Reading, Long>>()
    private val _txArtifacts = MutableLiveData<List<Float>>(emptyList())
    /** Spots (Hz) that light up whenever we transmit, whatever the offset: not our signal. */
    val txArtifacts: LiveData<List<Float>> = _txArtifacts

    /**
     * Match own-callsign frames decoded back to what we sent at about the same time, and
     * report where our signal was actually heard. A mismatch is logged for diagnostics.
     */
    private fun checkEchoes(now: Long) {
        if (echoFrames.isEmpty()) return
        val done = mutableListOf<Pair<Float, Long>>()
        echoFrames.forEach { echo ->
            val (heardHz, ms) = echo
            val sent = outgoing.filter { kotlin.math.abs(it.sentMs - ms) <= 90_000L }
                .minByOrNull { kotlin.math.abs(it.sentMs - ms) }
            when {
                sent != null -> {
                    val check = EchoCheck(heardHz, sent.laneHz, ms)
                    _ownEcho.value = check
                    val line = String.format(
                        java.util.Locale.US, "%s sent %.0f Hz, heard back %.0f Hz (%+.0f Hz)%s",
                        java.time.Instant.ofEpochMilli(ms).toString(), sent.laneHz, heardHz, check.offByHz,
                        if (check.mismatch) " MISMATCH" else "",
                    )
                    historyIo.execute { OwnEchoLog.append(getApplication(), line) }
                    done += echo
                }
                now - ms > 120_000L -> done += echo // nothing sent near it
            }
        }
        echoFrames.removeAll(done)
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
            // Our own transmissions decoded back (audio loopback) are not evidence of anything.
            if (isMe(sender)) { reportedIds += m.id; return@forEach }
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
        // A relay we sent ("RELAY>TARGET>TEXT") belongs in the target's thread.
        relayPattern.matchEntire(text.trim().uppercase())?.let { m ->
            val (relay, target, body) = m.destructured
            outgoing += ThreadItem.Outgoing(target, "via $relay: ${body.trim()}", sentMs, frequency)
            if (!replaying) requestPublish()
            return
        }
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
    fun sendTarget(): String? = selectedKey.takeIf { it != ALL && !it.startsWith("#") }

    /**
     * Message assembly must never take the app down: a bad edge case loses one
     * message, not the receiver. Failures are kept for the diagnostics report.
     */
    private inline fun safeEngine(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            android.util.Log.e("TrafficViewModel", "rollup engine error", e)
            runCatching { com.js8call.example.CrashReporter.recordNonFatal("rollup engine", e) }
        }
    }

    /** Unread stored messages (the inbox), set by the screen that observes the message database. */
    private var inboxUnread = 0
    fun setInboxUnread(count: Int) {
        if (count == inboxUnread) return
        inboxUnread = count
        publish()
    }

    /** Add an @group or #tag to watch (Settings → My Groups holds the list). */
    fun watch(entry: String): String? {
        val e = entry.trim().uppercase().replace(Regex("\\s+"), "")
        val norm = when {
            e.startsWith("#") || e.startsWith("@") -> e
            else -> "@$e"
        }
        if (norm.length < 2) return null
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val list = prefs.getString("my_groups", "").orEmpty().split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (norm !in list) prefs.edit().putString("my_groups", (list + norm).joinToString(",")).apply()
        select(norm)
        return norm
    }

    /** Stop watching an @group or #tag. */
    fun unwatch(entry: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val list = prefs.getString("my_groups", "").orEmpty().split(',', ' ', ';').map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && it != entry && "@$it" != entry }
        prefs.edit().putString("my_groups", list.joinToString(",")).apply()
        if (selectedKey == entry) select(ALL) else publish()
    }

    /**
     * Test builds only: a few minutes of made-up traffic, including two damaged
     * copies of one message (to show the merge) and a message with a missing frame.
     */
    fun loadDemo() {
        val now = System.currentTimeMillis()
        demoBand = currentBand() ?: Band.B40
        val heardBeforeDemo = heard.keys.toSet()
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
        at(9, 1180f, -8, "KN4XYZ: AC4LT SNR -11", 3)
        at(11, 690f, -15, "AC4LT: KN4XYZ SNR -9", 3)
        at(22, 2140f, -6, "W4QRP: N0DEF HEARING AC4LT KF4MNO", 3)
        demoCalls += heard.keys - heardBeforeDemo
        safeEngine { engine.tick(now) }
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
        val all = engine.messages().filterNot { isOwnEcho(it) }
        val messages = all.filterNot { isBeacon(it) }
        // Heartbeat replies ("X: ME HEARTBEAT SNR -19") are the best "they heard me" evidence: scan everything.
        noteNetEvidence(all)
        noteGrids(all)
        noteLinks(all)
        val live = engine.live()
        val myGroups = myGroups()

        // Groups: configured ones plus any heard as recipients.
        val groupKeys = (myGroups + messages.mapNotNull { it.recipient }.filter { it.startsWith("@") })
            .filterNot { it == "@HB" }
            .distinct()
        // Watched #tags (e.g. #SITREP, #SWX): every message that carries the tag.
        val tagRows = myTags().map { t ->
            val tagged = messages.filter { hasTag(it, t) }
            StationRow(
                key = t, label = t, isGroup = true, color = ACCENT,
                detail = if (tagged.isEmpty()) "Watching · nothing yet" else "${tagged.size} message${if (tagged.size == 1) "" else "s"}",
                lastHeardMs = tagged.maxOfOrNull { it.lastHeardMs } ?: 0L,
                unread = tagged.count { it.lastHeardMs > (lastViewedMs[t] ?: viewedBaselineMs) },
                selected = t == selectedKey, receiving = false,
            )
        }
        val byRecipient = messages.groupBy { it.recipient }
        val groupRows = groupKeys.map { g ->
            val inGroup = byRecipient[g].orEmpty()
            val last = inGroup.maxOfOrNull { it.lastHeardMs } ?: 0L
            StationRow(
                key = g, label = g, isGroup = true, color = ACCENT,
                detail = when {
                    inGroup.isNotEmpty() -> "${inGroup.size} message${if (inGroup.size == 1) "" else "s"}"
                    g in myGroups -> "Watching · nothing yet"
                    else -> "No traffic yet"
                },
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
                val unreadForMe = fromThem.count { isMe(it.recipient) && it.lastHeardMs > (lastViewedMs[call] ?: viewedBaselineMs) }
                StationRow(
                    key = call, label = call, isGroup = false, color = colorFor(call),
                    detail = listOfNotNull(
                        if (unreadForMe > 0) "► FOR YOU" else null,
                        h.grid, "+${h.laneHz.toInt()} Hz", "${h.snr} dB",
                    ).joinToString(" · "),
                    lastHeardMs = h.lastHeardMs,
                    unread = fromThem.count { it.lastHeardMs > (lastViewedMs[call] ?: viewedBaselineMs) },
                    selected = call == selectedKey,
                    receiving = live.any { it.sender == call },
                )
            }

        // Team shortcut (opens the Team tab): how many members were heard in the last 24 h.
        val roster = TeamRoster.decode(PreferenceManager.getDefaultSharedPreferences(getApplication()).getString(TeamRoster.KEY, null))
        val heardBase = heard.filter { now - it.value.lastHeardMs < 24 * 3_600_000L }.keys.map(TeamMember::baseOf).toSet()
        val inboxRow = StationRow(
            key = INBOX, label = "Inbox", isGroup = true, color = ACCENT,
            detail = "Stored messages (MSG) sent to you or your groups",
            lastHeardMs = 0L, unread = inboxUnread, selected = false, receiving = false,
        )
        val watchRow = StationRow(
            key = WATCH, label = "+ Watch a group or #tag", isGroup = true, color = ACCENT,
            detail = "e.g. @GHOSTNET, #SITREP", lastHeardMs = 0L, unread = 0, selected = false, receiving = false,
        )
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
        _stations.value = listOf(allRow, inboxRow, teamRow) + groupRows + tagRows + watchRow + stationRows

        // Thread for the selection.
        val key = selectedKey
        val incoming = when {
            key == ALL -> messages
            key.startsWith("@") -> messages.filter { it.recipient == key }
            key.startsWith("#") -> messages.filter { hasTag(it, key) }
            else -> messages.filter { it.sender == key || it.recipient == key }
        }.map { ThreadItem.Incoming(it, colorFor(it.sender)) }
        val mine = when {
            key == ALL -> outgoing
            key.startsWith("#") -> outgoing.filter { Regex("(^|\\s)" + Regex.escape(key) + "(\\s|$|:)").containsMatchIn(it.text.uppercase()) }
            else -> outgoing.filter { it.to == key }
        }
        _thread.value = (incoming + mine).sortedBy { it.timeMs }

        // Live bar: prefer the selected station, else the most recent.
        val liveForKey = live.filter { key == ALL || it.sender == key || key.startsWith("@") }
            .maxByOrNull { it.lastFrameMs }
        _live.value = liveForKey?.let { Live(it.sender, it.laneHz, it.framesSoFar, it.textSoFar.trim(), colorFor(it.sender)) }

        // Map: stations with a known grid (this session's and remembered ones), links between
        // other stations, and every report that carries a location.
        heard.forEach { (call, h) -> h.grid?.let { mapMemory.noteStation(call, it, h.lastHeardMs, h.snr, if (h.dialHz != null || demoBand != null) bandOf(call) else null) } }
        val positions = HashMap<String, LatLon?>()
        fun posOf(call: String): LatLon? = positions.getOrPut(call) {
            (mapMemory.grid(call) ?: heard[call]?.grid)?.let { Geo.maidenheadToLatLon(it) }
        }
        _mapData.value = MapData(
            stations = mapMemory.stations().mapNotNull { st ->
                if (isMe(st.call)) return@mapNotNull null
                val pos = posOf(st.call) ?: return@mapNotNull null
                MapStation(st.call, st.grid, pos, colorFor(st.call), st.lastHeardMs, st.snr, st.bands)
            },
            links = mapMemory.links().mapNotNull { l ->
                val pa = posOf(l.a) ?: return@mapNotNull null
                val pb = posOf(l.b) ?: return@mapNotNull null
                MapLink(l.a, l.b, pa, pb, l.snr, l.reporter, l.confirmed, l.lastHeardMs, l.bands)
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
        saveMapMemorySoon(now)

        refreshMarkers(now)
        checkEchoes(now)
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
        engine.messages().filterNot { isBeacon(it) || isOwnEcho(it) }.map { TeamBoard.Msg(it.sender, it.bodyText, it.lastHeardMs) }

    /** What the Net screen needs for [group] since [sinceMs]. */
    fun netInput(group: String, sinceMs: Long, expected: List<String>, now: Long = System.currentTimeMillis()): com.js8call.example.net.NetRoster.Input {
        return com.js8call.example.net.NetRoster.Input(
            group = group,
            sinceMs = sinceMs,
            nowMs = now,
            myCall = myBaseCall(),
            messages = engine.messages().filterNot { isOwnEcho(it) }.map { com.js8call.example.net.NetRoster.Msg(it.sender, it.recipient, it.bodyText, it.firstHeardMs, it.isComplete) },
            sent = outgoing.map { com.js8call.example.net.NetRoster.Sent(it.to, it.text, it.sentMs) },
            stations = heard.mapKeys { it.key.uppercase() }.mapValues { (_, h) -> com.js8call.example.net.NetRoster.Seen(h.grid, h.snr, h.lastHeardMs) },
            expected = expected,
        )
    }

    /** Everything the Digest tab needs for the window starting at [sinceMs]. */
    fun digestInput(sinceMs: Long, now: Long = System.currentTimeMillis()): DigestBuilder.Input {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val messages = engine.messages().filterNot { isBeacon(it) || isOwnEcho(it) }.map { m ->
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
            activity = engine.messages().filterNot { isOwnEcho(it) }.mapNotNull { m ->
                m.sender?.let { DigestBuilder.Activity(it, m.lastHeardMs) }
            },
            heardMe = observationStore.all().filter { it.kind == Observation.Kind.HEARD_ME && it.timeMs >= sinceMs }.map {
                DigestBuilder.HeardMe(it.call, it.snr, it.timeMs, it.grid ?: heard[it.call]?.grid)
            },
        )
    }

    private fun sendToAtak(map: MapData, now: Long) {
        val app = getApplication<Application>()
        if (!AtakBridge.enabled(app)) return
        val stale = AtakBridge.staleAfterMs(app)
        val points = map.reports.map { r ->
            Cot.reportPoint(r.report, r.sender, r.position, r.timeMs, r.firstHeardMs / 1000, stale, now)
        } + if (AtakBridge.includeStations(app)) map.stations.filter { it.call in heard && it.call !in demoCalls }.map { s ->
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

    /**
     * Our own transmission decoded back by our receiver (the radio's RX audio isn't muted
     * during TX on some rigs). Shown already as a "You" bubble, so the copy is hidden:
     * anything from our callsign, and headless pieces on the lane we just sent on.
     */
    private fun isOwnEcho(m: RolledMessage): Boolean {
        val sender = m.sender
        if (sender != null) return isMe(sender)
        return outgoing.any { o ->
            kotlin.math.abs(o.laneHz - m.laneHz) <= 25f && kotlin.math.abs(o.sentMs - m.lastHeardMs) <= 90_000L
        }
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
        return raw.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.length > 1 && !it.startsWith("#") }
            .map { if (it.startsWith("@")) it else "@$it" }
    }

    /** Watched hashtags from the same list (entries starting with #). */
    private fun myTags(): List<String> =
        PreferenceManager.getDefaultSharedPreferences(getApplication()).getString("my_groups", "").orEmpty()
            .split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.length > 1 && it.startsWith("#") }.distinct()

    private fun hasTag(m: RolledMessage, tag: String): Boolean =
        Regex("(^|\\s)" + Regex.escape(tag) + "(\\s|$|:)").containsMatchIn(m.bodyText.uppercase())

    companion object {
        @Volatile private var instance: TrafficViewModel? = null

        /** True when [call] is this station's callsign (ignoring /P and other suffixes). */
        fun isMyCall(context: android.content.Context, call: String?): Boolean {
            if (call.isNullOrBlank()) return false
            val me = PreferenceManager.getDefaultSharedPreferences(context).getString("callsign", "").orEmpty()
                .trim().uppercase().substringBefore('/')
            return me.isNotEmpty() && call.trim().uppercase().substringBefore('/') == me
        }

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
        const val TEAM = "§TEAM"
        /** Station-list row that adds a group or tag to watch. */
        const val WATCH = "§WATCH"
        /** Station-list row that opens the stored-message inbox. */
        const val INBOX = "§INBOX"
        private const val TICK_MS = 5_000L
        private val relayPattern = Regex("""^([A-Z0-9/]+)>([A-Z0-9/@]+)>(.*)$""")
        /** Waterfall tags disappear after this long without hearing the station. */
        private const val MARKER_MS = 5 * 60_000L
        /** Own echo heard further than this from where we sent it counts as a mismatch. */
        const val ECHO_TOLERANCE_HZ = 15f
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
