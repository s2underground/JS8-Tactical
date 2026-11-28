package com.js8call.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.js8call.core.BluetoothSerialBridge
import com.js8call.core.BluetoothSerialPortCatalog
import com.js8call.core.HamlibRigControl
import com.js8call.core.JS8AudioHelper
import com.js8call.core.JS8Engine
import com.js8call.core.QmxDirectSerial
import com.js8call.core.TruSdxDirectSerial
import com.js8call.core.UsbSerialBridge
import com.js8call.core.UsbSerialPortCatalog
import com.js8call.core.rxMaskOf
import com.js8call.example.AppBroadcasts
import com.js8call.example.BuildConfig
import com.js8call.example.MainActivity
import com.js8call.example.MessageLogWriter
import com.js8call.example.R
import com.js8call.example.data.MessageRepository
import com.js8call.example.model.DecodedMessage
import com.js8call.example.network.PskReporterClient
import com.js8call.example.ui.AudioDevices
import com.js8call.example.util.CallsignValidator
import com.js8call.example.util.Js8Commands
import com.js8call.example.util.TxMessageClassifier
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service for running the JS8 engine in the background.
 *
 * This service manages the native engine lifecycle, audio capture,
 * and broadcasts decode events to the UI.
 */
class JS8EngineService : Service() {

    private val messageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageRepository by lazy { MessageRepository.getInstance(applicationContext) }
    private var engine: JS8Engine? = null
    private var audioHelper: JS8AudioHelper? = null
    private var rigCtlClient: RigCtlClient? = null
    private var rigCtlConnected: Boolean = false
    private var rigCtlErrorShown: Boolean = false
    private var rigControlMode: String = "none"
    private var hamlibRigControl: HamlibRigControl? = null
    private var hamlibRigConnected: Boolean = false
    private var rtsPttConnected: Boolean = false
    private var trusdxConnected: Boolean = false
    private var qmxConnected: Boolean = false
    private var uvproConnected: Boolean = false
    private var rtsPttTransport: SerialTransport? = null
    private var usbSerialBridge: UsbSerialBridge? = null
    private var bluetoothSerialBridge: BluetoothSerialBridge? = null
    private var trusdxDirectSerial: TruSdxDirectSerial? = null
    private var trusdxSerialSession: TruSdxSerialSession? = null
    private var qmxDirectSerial: QmxDirectSerial? = null
    private var qmxCatSession: QmxCatSession? = null
    private var uvProSession: UvProSession? = null
    /** SDR receiver (rig types rtl_sdr and airspy_hf, issue #54): receive only. */
    private var sdrSession: SdrSession? = null
    @Volatile private var sdrConnected = false
    private val sdrWorkersActive = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var trusdxInitInProgress: Boolean = false
    @Volatile private var engineStartGeneration: Int = 0
    @Volatile private var engineStartInProgress: Boolean = false
    @Volatile private var trusdxStartupWorkerActive: Boolean = false
    @Volatile private var qmxStartupWorkerActive: Boolean = false
    @Volatile private var uvproSessionGeneration: Int = 0
    @Volatile private var trusdxParserResyncs: Long = 0
    @Volatile private var trusdxRxFrames: Long = 0
    @Volatile private var trusdxRxSamples: Long = 0
    @Volatile private var trusdxTxFrames: Long = 0
    @Volatile private var trusdxTxSamples: Long = 0
    @Volatile private var trusdxTxDrops: Long = 0
    @Volatile private var trusdxTxSilentFrames: Long = 0
    @Volatile private var trusdxRxUnderruns: Long = 0
    @Volatile private var trusdxRxSubmitDrops: Long = 0
    @Volatile private var trusdxLastRxAudioNs: Long = 0L
    @Volatile private var trusdxLastRxRearmNs: Long = 0L
    @Volatile private var trusdxWatchdogToken: Int = 0
    @Volatile private var spectrumEventCount: Long = 0
    @Volatile private var trusdxRxRateWindowStartNs: Long = 0L
    @Volatile private var trusdxRxRateWindowSamples: Long = 0L
    @Volatile private var trusdxRxFrameDrops: Long = 0L
    @Volatile private var trusdxRxKeepaliveToken: Int = 0
    @Volatile private var trusdxRxKeepaliveCount: Long = 0L
    private val trusdxRxFrameQueue = LinkedBlockingDeque<ByteArray>(TRUSDX_RX_FRAME_QUEUE_MAX)
    @Volatile private var trusdxRxWorkerRunning = false
    @Volatile private var trusdxRxWorkerThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // The radio's sound card dropped off: stop, wait for it to come back, start again.
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (engine == null && !engineStartInProgress) return
            val lost = removed.firstOrNull { it.id == selectedAudioDeviceId } ?: return
            val name = AudioDevices.nameFor(lost) ?: "Audio device"
            Log.w(TAG, "Selected audio device removed: $name (ID: ${lost.id}); stopping engine")
            val now = System.currentTimeMillis()
            val txNow = isTransmitActive() || txSessionActive
            UsbDropouts.record(this@JS8EngineService, name, now, lastTxActivityMs, txNow)
            reconnectStartPending = false
            stopEngine()
            broadcastAudioDeviceLost(name)
            // Field case Nov 19: the interface kept dropping and coming back; the receiver
            // stayed off each time and a queued send went out the moment it returned.
            // Now: wait up to RECONNECT_WINDOW_MS for it, restart, and hold sends a while.
            reconnectName = name
            reconnectDeadlineMs = now + RECONNECT_WINDOW_MS
            holdTxUntilMs = Long.MAX_VALUE
            mainHandler.removeCallbacks(reconnectGiveUp)
            mainHandler.postDelayed(reconnectGiveUp, RECONNECT_WINDOW_MS)
            UsbDropouts.rfHint(this@JS8EngineService, now)?.let { broadcastWarning(it) }
        }

        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            val wanted = reconnectName ?: return
            if (engine != null || engineStartInProgress) return
            if (System.currentTimeMillis() > reconnectDeadlineMs) return
            val back = added.firstOrNull { it.isSource && AudioDevices.nameFor(it) == wanted } ?: return
            Log.i(TAG, "Audio device back: $wanted (new ID: ${back.id}); restarting the receiver shortly")
            mainHandler.removeCallbacks(reconnectRestart)
            reconnectDeviceId = back.id
            // Let the USB device (and its serial port) finish enumerating first
            mainHandler.postDelayed(reconnectRestart, RECONNECT_SETTLE_MS)
        }
    }

    private var reconnectName: String? = null
    private var reconnectDeadlineMs = 0L
    private var reconnectDeviceId = -1
    /** Sends wait until this time (radio link just came back and may still be settling). */
    @Volatile private var holdTxUntilMs = 0L
    /** Last time a send started, ended or failed; for the USB dropout log. */
    @Volatile private var lastTxActivityMs = 0L

    private val reconnectRestart: Runnable = object : Runnable {
        override fun run() {
        val id = reconnectDeviceId
        if (engine != null || engineStartInProgress || id < 0) return
        // The previous engine may still be releasing its audio, or its rig control still shutting
        // down; try again shortly. Field report (build 360): the restart came 3 s after the radio
        // returned, rig teardown was still retrying the PTT release, and the start was refused, so
        // the receiver stayed off until the operator restarted it.
        if (restartBlocked() && System.currentTimeMillis() < reconnectDeadlineMs) {
            mainHandler.postDelayed(this, 1_000L)
            return
        }
        reconnectName = null
        mainHandler.removeCallbacks(reconnectGiveUp)
        selectedAudioDeviceId = id
        PreferenceManager.getDefaultSharedPreferences(this@JS8EngineService).edit().putInt("last_audio_device_id", id).apply()
        holdTxUntilMs = System.currentTimeMillis() + RECONNECT_TX_HOLD_MS
        mainHandler.postDelayed({ broadcastProcessTxQueue() }, RECONNECT_TX_HOLD_MS + 500L)
        reconnectStartPending = true
        broadcastWarning(
            "The radio's USB audio dropped out and came back. The receiver has restarted. " +
                "Anything waiting to be sent goes out after ${RECONNECT_TX_HOLD_MS / 1000} s of stable connection.",
            quiet = true,
        )
        startEngine()
        }
    }

    /** Set when a dropout restart is under way; the dropout log records the recovery once the engine is up. */
    @Volatile private var reconnectStartPending = false

    /** Something from the previous run that startEngine waits for is still shutting down. */
    private fun restartBlocked(): Boolean =
        engineStoppingSinceMs > 0 || rigTeardownPending || trusdxStartupWorkerActive || qmxStartupWorkerActive

    private val reconnectGiveUp = Runnable {
        if (reconnectName == null || engine != null || engineStartInProgress) return@Runnable
        Log.w(TAG, "Audio device did not come back within ${RECONNECT_WINDOW_MS / 60_000} min; stopping")
        reconnectName = null
        holdTxUntilMs = 0L
        stopSelf()
    }

    private fun broadcastWarning(message: String, quiet: Boolean = false) {
        AppBroadcasts.send(
            this,
            Intent(ACTION_WARNING).putExtra(EXTRA_ERROR_MESSAGE, message).putExtra(EXTRA_WARNING_QUIET, quiet)
        )
    }
    private val txHandlerThread = HandlerThread("Js8Tx")
    private lateinit var txHandler: Handler
    // Stop cancels only this, never a queued rig teardown
    private val txWorkToken = Any()
    private val txSubmitLock = Any()
    private lateinit var txMonitorHandler: Handler
    private var selectedAudioDeviceId: Int = -1  // -1 means use default
    private var selectedOutputDeviceId: Int = -1  // -1 means use default
    /** The TX offset: saved, so it survives restarts and is the one the waterfall shows. */
    private val currentTxOffsetHz: Float get() = TxOffsets.txOffset(this)
    private var txMonitorActive = false
    private var txMonitorWasAudioActive = false
    /** The text each frame of the send in progress carries (display form), for the "Sending" bar. */
    @Volatile private var txFrameTexts: List<String> = emptyList()
    /** Frames of the send in progress whose audio has started. */
    @Volatile private var txFramesStarted = 0
    // Drifted clock at the send's first audio; 0 until it goes on air
    @Volatile private var txSentStartMs = 0L
    private val pttStateLock = Any()
    @Volatile private var txPttGeneration = 0
    @Volatile private var rigPttAsserted = false
    /**
     * A key command went to the radio and no release has been confirmed since. A "key" whose
     * reply was lost may still have keyed the radio, so Stop and failures unkey whenever this is
     * set, not only when [rigPttAsserted] says the key took.
     */
    private var rigPttMaybeKeyed = false
    /** Release attempts that have failed in a row (they are retried until one works). */
    private var rigPttReleaseFailures = 0
    /** Key commands and confirmed releases as the radio saw them (see [PttLedger]). */
    private val pttLedger = PttLedger()
    private var rigPttDesired = false
    private var rigPttDesiredGeneration = 0
    private var rigPttCommandPending = false
    private var rigPttFailureCount = 0
    private var rigPttCompletion: ((Boolean) -> Unit)? = null
    private var pttShuttingDown = false
    @Volatile private var txPttFailed = false
    @Volatile private var txSessionActive = false
        // On-device AI waits while this is set (see RadioGate): the radio gets the CPU first.
        set(value) { field = value; com.js8call.example.ai.RadioGate.sending = value }
    @Volatile private var txAudioActive = false
    @Volatile private var trusdxTxIntentActive = false
    private var scoRoutingActive = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var scoRestartAttempts = 0
    private var scoSilenceCheckToken = 0
    private var scoStartToken = 0
    private var scoSourceIndex = 0
    private val scoSourceCandidates = intArrayOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC
    )
    private var callsignWarningShown = false
    private var lastTxMessage: String = ""
    /** The last message the operator sent (automatic replies don't count): what AGN? replays. */
    private var lastOperatorTxMessage: String = ""
    /** One automatic AGN? replay per station per 10 minutes (see [ReplayGuard]). */
    private val agnReplayGuard = ReplayGuard()
    /** One answer per asker, group and query per 15 minutes while the auto-reply safeguard is on. */
    private val groupReplyGuard = ReplayGuard(windowMs = 15 * 60_000L)
    private var lastTxDirected: String = ""
    private var lastTxSubmode: Int = SUBMODE_NORMAL
    private var lastTxFrequencyHz: Double = DEFAULT_AUDIO_FREQUENCY_HZ
    private var messageLogger: MessageLogWriter? = null
    private val heldStore by lazy { com.js8call.example.storeforward.HeldStore.of(this) }
    /** Where a directed YES / HEARTBEAT SNR to us started, so a following "MSG ID n" data frame can be tied to it. */
    private val offerStarts = mutableMapOf<Int, Pair<String, Long>>()
    private val heardCallsigns = mutableMapOf<String, Long>()
    private val heardLock = Any()
    /** Relays addressed to us, collected frame by frame (see [RelayAssembler]). */
    private val relayAssembler = RelayAssembler()
    private val gridRegex = Regex("\\b[A-R]{2}[0-9]{2}([A-X]{2})?\\b", RegexOption.IGNORE_CASE)
    private val txMonitorRunnable = object : Runnable {
        override fun run() {
            val generation = txPttGeneration
            val activeEngine = engine
            if (activeEngine == null) {
                txMonitorActive = false
                return
            }
            val sessionActive = activeEngine.isTransmitting()
            val audioActive = activeEngine.isTransmittingAudio()
            val millisecondsUntilAudio = activeEngine.txMillisecondsUntilAudio()
            txSessionActive = sessionActive
            txAudioActive = audioActive
            if (!sessionActive) {
                txMonitorActive = false
                txMonitorWasAudioActive = false
                trusdxTxIntentActive = false
                if (isRigControlConnected()) {
                    requestRigPtt(false, generation) { released ->
                        if (!isCurrentTxGeneration(generation)) return@requestRigPtt
                        if (!released) broadcastError("Failed to release PTT after transmission")
                        txFrameTexts = emptyList()
                        broadcastTxSent()
                        broadcastTxState(TX_STATE_FINISHED)
                    }
                } else {
                    if (isCurrentTxGeneration(generation)) {
                        txFrameTexts = emptyList()
                        broadcastTxSent()
                        broadcastTxState(TX_STATE_FINISHED)
                    }
                }
                return
            }

            val rigRequired = isRigPttRequired()
            val rigConnected = isRigControlConnected()
            if (rigRequired && !rigConnected) {
                failTransmitForPtt(generation, "Rig control is not connected")
                return
            }
            if (rigConnected && !audioActive && millisecondsUntilAudio in 0..PTT_LEAD_TIME_MS) {
                requestRigPtt(true, generation)
            }

            if (audioActive && rigConnected && !isRigPttReady(generation)) {
                activeEngine.setTransmitReady(false)
                // Field report: radio stuck keyed at zero watts. Audio is held here until PTT is
                // "ready" for this TX generation; if the generation moved on after PTT was keyed,
                // nothing asked again and the hold never ended. Ask again (no-op if a command is
                // already pending), and give up safely if audio still can't start.
                requestRigPtt(true, generation)
                val now = SystemClock.uptimeMillis()
                if (pttHoldSinceMs == 0L) pttHoldSinceMs = now
                if (now - pttHoldSinceMs > PTT_HOLD_LIMIT_MS) {
                    pttHoldSinceMs = 0L
                    failTransmitForPtt(generation, "Radio keyed but audio could not start; transmit stopped and PTT released")
                    return
                }
                txMonitorHandler.postDelayed(this, TX_PREKEY_MONITOR_INTERVAL_MS)
                return
            }
            pttHoldSinceMs = 0L

            if (audioActive && !txMonitorWasAudioActive) {
                txMonitorWasAudioActive = true
                if (txSentStartMs == 0L) {
                    txSentStartMs = System.currentTimeMillis() + activeEngine.timeDriftMs()
                }
                logTxStarted()
                txFramesStarted++
                broadcastTxState(TX_STATE_STARTED)
                broadcastTxProgress(onAir = true)
            } else if (!audioActive && txMonitorWasAudioActive) {
                txMonitorWasAudioActive = false
                if (rigConnected) {
                    requestRigPtt(false, generation)
                }
                broadcastTxState(TX_STATE_QUEUED)
                broadcastTxProgress(onAir = false)
            }

            val nextCheckMs = if (millisecondsUntilAudio.toLong() > PTT_LEAD_TIME_MS + TX_MONITOR_INTERVAL_MS) {
                TX_MONITOR_INTERVAL_MS
            } else {
                TX_PREKEY_MONITOR_INTERVAL_MS
            }
            txMonitorHandler.postDelayed(this, nextCheckMs)
        }
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var nextHeartbeatTime = 0L
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            checkHeartbeat()
        }
    }

    private var pskReporterClient: PskReporterClient? = null
    private var pskReporterEnabled = false
    private var currentDialHz: Long = 0
    private var currentCallsign: String = ""
    private var currentGrid: String = ""

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_HEARTBEAT_INTERVAL) {
            scheduleHeartbeat(true)
        }
        if (key == PREF_PSK_REPORTER || key == "callsign" || key == "grid") {
            updatePskReporterState()
        }
        if (key == "last_frequency") {
            updateDialFromPrefs()
            noteDialChange(currentDialHz)
        }
        if (key == PREF_LOG_MESSAGES) {
            updateMessageLogging()
        }
        if ((key == PREF_RTLSDR_DIRECT_SAMPLING || key == PREF_RTLSDR_PPM || key == PREF_RTLSDR_GAIN) &&
            isSdrMode() && currentDialHz > 0) {
            // Retune in place with the new sampling mode, correction or gain.
            setFrequency(currentDialHz)
        }
        if (key in RIG_SETTING_KEYS) {
            // Rig control is only set up when the receiver starts: restart it so the change is used
            // (after a short pause, so several changes in a row restart once).
            mainHandler.removeCallbacks(rigSettingsRestart)
            mainHandler.postDelayed(rigSettingsRestart, RIG_SETTINGS_SETTLE_MS)
        }
    }

    /** Radio settings changed while receiving: restart the receiver so they take effect. Never mid-send. */
    private val rigSettingsRestart: Runnable = object : Runnable {
        override fun run() {
            if (engine == null && !engineStartInProgress) return // not running: the next start reads them
            if (engineStartInProgress || isTransmitActive()) { mainHandler.postDelayed(this, 5_000L); return }
            Log.i(TAG, "Radio settings changed; restarting the receiver to use them")
            broadcastWarning("Radio settings changed: restarting the receiver to use them.", quiet = true)
            stopEngine()
            mainHandler.postDelayed(rigSettingsStart, 1_000L)
        }
    }

    private val rigSettingsStart: Runnable = object : Runnable {
        override fun run() {
            if (engine != null || engineStartInProgress) return
            // The old rig link may still be closing on the TX thread.
            if (engineStoppingSinceMs > 0) { mainHandler.postDelayed(this, 1_000L); return }
            startEngine()
        }
    }

    /** Scheduled reports (Reports tab): queued when their time comes while the receiver runs. */
    private val reportTicker = object : Runnable {
        override fun run() {
            try { sendDueReports() } catch (e: Exception) { Log.e(TAG, "Scheduled report failed", e) }
            try { if (engineRunning) scanForAi() } catch (e: Exception) { Log.e(TAG, "AI scan failed", e) }
            try { runNetSwitcher() } catch (e: Exception) { Log.e(TAG, "Net Switcher failed", e) }
            mainHandler.postDelayed(this, REPORT_TICK_MS)
        }
    }

    private var lastAiScanMs = 0L

    /** While the receiver runs, bulletins get their AI one-line summary as they arrive (if AI is on). */
    private fun scanForAi() {
        val now = System.currentTimeMillis()
        if (now - lastAiScanMs < 60_000L) return
        lastAiScanMs = now
        if (!com.js8call.example.ai.AiModels.enabled(this)) return
        val input = com.js8call.example.ui.traffic.TrafficViewModel.of(applicationContext).digestInput(now - 24 * 3_600_000L, now)
        com.js8call.example.ai.AiWork.summariseBulletins(this, input.messages)
    }

    // ------------------------------------------------------------------ Net Switcher

    /** Heartbeats and auto-replies wait until then after the switcher changed band (setting). */
    private var netHoldUntilMs = 0L

    /** When the switcher last changed the dial (a rig's frequency report from before it is ignored). */
    private var netSwitchedAtMs = 0L

    /** Rig control that can set the dial is configured (whether or not it is connected right now). */
    private fun catConfigured(): Boolean = rigControlMode !in setOf("none", "rts_ptt", "btech_uvpro")

    /** Every tick: change net when one is due, unless transmitting (never cut off) or a message is coming in. */
    private fun runNetSwitcher() {
        val store = com.js8call.example.nets.NetStore.of(this)
        if (!store.enabled) { store.waiting = null; return }
        val now = System.currentTimeMillis()
        val receiving = runCatching { com.js8call.example.ui.traffic.TrafficViewModel.of(applicationContext).messageInProgress(now) }.getOrDefault(false)
        when (val d = com.js8call.example.nets.NetSwitcher.decide(store.nets(), store.home(), true, store.appliedKey,
            store.overrideUntilMs, now, isTransmitActive(), receiving)) {
            is com.js8call.example.nets.NetSwitcher.Decision.Idle -> store.waiting = null
            is com.js8call.example.nets.NetSwitcher.Decision.Wait -> {
                if (store.waiting != d.reason) Log.i(TAG, "Net Switcher: change due, waiting for ${d.reason}")
                store.waiting = d.reason
            }
            is com.js8call.example.nets.NetSwitcher.Decision.Switch -> {
                // Rig control configured but dropped out (USB hiccup): wait for it rather than
                // asking the operator to retune, up to 3 minutes.
                val prevDial = currentDialHz.takeIf { it > 0 }
                val needsDial = d.target.dialHz != null && !com.js8call.example.nets.NetSwitcher.sameDial(d.target.dialHz, prevDial)
                if (needsDial && catConfigured() && !isRigControlConnected() && now - d.target.sinceMs < 3 * 60_000L) {
                    if (store.waiting == null) Log.i(TAG, "Net Switcher: change due, waiting for rig control to reconnect")
                    store.waiting = "rig control to reconnect"
                } else applyNetTarget(store, d.target, now)
            }
        }
    }

    private fun applyNetTarget(store: com.js8call.example.nets.NetStore, t: com.js8call.example.nets.NetSchedule.Target, now: Long) {
        store.waiting = null
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val name = t.net?.name ?: "between nets"
        val done = ArrayList<String>()
        val prevDial = currentDialHz.takeIf { it > 0 } ?: prefs.getString("last_frequency", "")?.toLongOrNull()
        t.dialHz?.let { hz ->
            if (!com.js8call.example.nets.NetSwitcher.sameDial(hz, prevDial)) {
                store.lastSetDialHz = hz
                netSwitchedAtMs = now
                currentDialHz = hz
                prefs.edit().putString("last_frequency", hz.toString()).apply()
                val mhz = com.js8call.example.nets.NetSchedule.mhz(hz)
                val canCat = isRigControlConnected() && catConfigured()
                if (canCat) {
                    setFrequency(hz)
                    broadcastRadioFrequency(hz)
                    done += "$mhz MHz"
                } else {
                    netNotify("Change the radio to $mhz MHz", "Net Switcher: $name (no rig control, so this one is up to you)")
                    done += "$mhz MHz (asked you to change it: no rig control)"
                }
                val bandBefore = com.js8call.example.prop.Band.forDialHz(prevDial)
                val hold = store.holdAfterBandChangeMin
                if (hold > 0 && bandBefore != com.js8call.example.prop.Band.forDialHz(hz)) {
                    netHoldUntilMs = now + hold * 60_000L
                    done += "automatic sends held $hold min (band change)"
                }
            }
        }
        t.speed?.let { sm ->
            if (prefs.getInt(PREF_TX_SUBMODE, SUBMODE_NORMAL) != sm) {
                prefs.edit().putInt(PREF_TX_SUBMODE, sm).apply()
                done += "speed ${speedName(sm)}"
            }
        }
        t.heartbeatMin?.let { min ->
            if (prefs.getString(PREF_HEARTBEAT_INTERVAL, "0") != min.toString()) {
                prefs.edit().putString(PREF_HEARTBEAT_INTERVAL, min.toString()).apply()
                done += if (min <= 0) "heartbeat off" else "heartbeat every $min min"
            }
        }
        store.appliedKey = t.key
        val line = (if (t.net != null) "On ${t.net.name}" else "Between nets") + if (done.isEmpty()) " (nothing to change)" else ": " + done.joinToString(", ")
        store.log(now, line)
        Log.i(TAG, "Net Switcher: $line")
        // Messages queued for this net can go out now.
        mainHandler.post { runCatching { com.js8call.example.ui.TransmitViewModel.of(applicationContext).processNext() } }
    }

    /** The dial changed: if not by the switcher, the operator did it, so step back until the next change. */
    private fun noteDialChange(hz: Long, fromRig: Boolean = false) {
        val store = com.js8call.example.nets.NetStore.of(this)
        if (!store.enabled || hz <= 0) return
        // A frequency report the rig made before it finished retuning isn't the operator's doing.
        if (fromRig && System.currentTimeMillis() - netSwitchedAtMs < 15_000L) return
        // Messages queued for the net we're now on can go.
        mainHandler.post { runCatching { com.js8call.example.ui.TransmitViewModel.of(applicationContext).processNext() } }
        if (com.js8call.example.nets.NetSwitcher.sameDial(hz, store.lastSetDialHz)) return
        val now = System.currentTimeMillis()
        val target = com.js8call.example.nets.NetSchedule.target(store.nets(), store.home(), now)
        if (target.dialHz == null || com.js8call.example.nets.NetSwitcher.sameDial(hz, target.dialHz)) return
        val until = com.js8call.example.nets.NetSwitcher.overrideUntil(store.nets(), store.home(), now)
        if (store.overrideUntilMs >= until) return
        store.overrideUntilMs = until
        store.lastSetDialHz = hz
        val at = java.text.SimpleDateFormat("HHmm'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(until))
        store.log(now, "Paused: frequency changed by hand to ${com.js8call.example.nets.NetSchedule.mhz(hz)} MHz; back on schedule at $at")
        Log.i(TAG, "Net Switcher paused by a hand-made frequency change until $at")
    }

    private fun speedName(sm: Int) = when (sm) { SUBMODE_SLOW -> "Slow"; SUBMODE_FAST -> "Fast"; SUBMODE_TURBO -> "Turbo"; else -> "Normal" }

    private fun netNotify(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(this, 77, Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIFICATION_ID_NET, NotificationCompat.Builder(this, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_js8t)
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open).setAutoCancel(true).build())
    }

    private fun sendDueReports() {
        if (!engineRunning) return
        val now = System.currentTimeMillis()
        val store = com.js8call.example.reports.ReportStore.of(this)
        if (store.list().none { (it.schedule?.atMs ?: Long.MAX_VALUE) <= now }) return
        val due = store.takeDue(now)
        val tx = com.js8call.example.ui.TransmitViewModel.of(this)
        due.send.forEach { d ->
            Log.i(TAG, "Scheduled report '${d.displayTitle()}' queued")
            if (d.to.isBlank()) tx.queueManualMessage(d.sendText, d.speed) else tx.queueThreadMessage(d.to.uppercase(), d.sendText, d.speed)
        }
        due.missed.forEach { Log.w(TAG, "Scheduled report '${it.displayTitle()}' missed (receiver was off)") }
    }

    override fun onCreate() {
        super.onCreate()
        // If the app crashes while the radio may be keyed, try once to unkey it (CrashReporter).
        emergencyUnkeyHook = {
            val keyed = synchronized(pttStateLock) { rigPttAsserted || rigPttMaybeKeyed }
            !keyed || setRigPtt(false)
        }
        // Make sure Traffic is listening even if Android restarted this service without the app's screen.
        runCatching { com.js8call.example.ui.traffic.TrafficViewModel.of(applicationContext) }
        // The send queue must exist (and listen) even when no screen has opened yet.
        runCatching { com.js8call.example.ui.TransmitViewModel.of(applicationContext) }
        createNotificationChannel()
        createRigTransports()
        txHandlerThread.start()
        txHandler = Handler(txHandlerThread.looper)
        txMonitorHandler = Handler(Looper.getMainLooper())
        (getSystemService(AUDIO_SERVICE) as AudioManager)
            .registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        messageLogger = MessageLogWriter(applicationContext)
        updateMessageLogging()
        scheduleHeartbeat(true)
        mainHandler.postDelayed(reportTicker, REPORT_TICK_MS)
        initPskReporter()

        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Android closed the app while the receiver was on and has now restarted this
            // service by itself, with no screen. A microphone service can't start listening
            // from the background, so say what happened and offer a one-tap restart.
            if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_RECEIVER_WANTED, false)) {
                Log.w(TAG, "Restarted by Android after the app was closed; offering a receiver restart")
                showReceiverRestartNotification()
            }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting engine")
                PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_RECEIVER_WANTED, true).apply()
                reconnectName = null
                holdTxUntilMs = 0L
                mainHandler.removeCallbacks(reconnectRestart)
                mainHandler.removeCallbacks(reconnectGiveUp)
                // Get preferred device ID from intent
                if (intent.hasExtra(EXTRA_AUDIO_DEVICE_ID)) {
                    selectedAudioDeviceId = intent.getIntExtra(EXTRA_AUDIO_DEVICE_ID, -1)
                    Log.i(TAG, "Start requested with device ID: $selectedAudioDeviceId")
                }
                updateDialFromPrefs()
                updatePskReporterState()
                startForegroundService()
                startEngine()
            }
            ACTION_STOP -> {
                PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_RECEIVER_WANTED, false).apply()
                reconnectName = null
                holdTxUntilMs = 0L
                mainHandler.removeCallbacks(reconnectRestart)
                mainHandler.removeCallbacks(reconnectGiveUp)
                Log.i(TAG, "Stopping engine")
                stopEngine()
                stopSelf()
            }
            ACTION_EXIT -> {
                // Notification "Exit": off the air, out of recent apps, service gone.
                PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_RECEIVER_WANTED, false).apply()
                reconnectName = null
                holdTxUntilMs = 0L
                mainHandler.removeCallbacks(reconnectRestart)
                mainHandler.removeCallbacks(reconnectGiveUp)
                Log.i(TAG, "Exit: stopping engine and closing the app")
                PreferenceManager.getDefaultSharedPreferences(this).edit().putLong(com.js8call.example.MainActivity.PREF_EXITED_AT, System.currentTimeMillis()).apply()
                runCatching { com.js8call.example.ui.TransmitViewModel.of(applicationContext).cancelAll() }
                stopEngine()
                runCatching { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks.forEach { it.finishAndRemoveTask() } }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SWITCH_AUDIO_DEVICE -> {
                val deviceId = intent.getIntExtra(EXTRA_AUDIO_DEVICE_ID, -1)
                Log.i(TAG, "Switching audio device to ID: $deviceId")
                switchAudioDevice(deviceId)
            }
            ACTION_SET_FREQUENCY -> {
                val frequencyHz = intent.getLongExtra(EXTRA_FREQUENCY_HZ, 0L)
                Log.i(TAG, "Setting frequency to $frequencyHz Hz")
                if (frequencyHz > 0) {
                    currentDialHz = frequencyHz
                }
                setFrequency(frequencyHz)
            }
            ACTION_HALT_TX -> haltTransmit()
            ACTION_SET_TX_OFFSET -> {
                val offsetHz = intent.getFloatExtra(EXTRA_TX_OFFSET_HZ, TxOffsets.txOffset(this))
                Log.i(TAG, "Setting TX offset to $offsetHz Hz")
                TxOffsets.setTxOffset(this, offsetHz)
            }
            ACTION_TRANSMIT_MESSAGE -> when {
                engine != null -> {
                    val txIntent = Intent(intent)
                    postTxWork { handleTransmitMessage(txIntent) }
                }
                engineStartInProgress -> handBack("until the engine is running")
                else -> {
                    // The UI missed a stop
                    broadcastEngineState(STATE_STOPPED)
                    stopSelfResult(startId)
                }
            }
            ACTION_TIME_SYNC_ONCE -> {
                Log.i(TAG, "One-shot time sync armed; waiting for next decode")
                timeSyncOncePending = true
            }
            ACTION_SET_TIME_DRIFT -> {
                val driftMs = intent.getLongExtra(EXTRA_TIME_DRIFT_MS, 0L)
                Log.i(TAG, "Setting time drift to $driftMs ms")
                timeSyncOncePending = false
                // A manual setting starts automatic sync afresh from what is heard next.
                driftEstimator.clear()
                lastAppliedDriftMs = driftMs
                applyTimeDrift(driftMs)
            }
            ACTION_DEBUG_INJECT_DECODE -> {
                if (BuildConfig.DEBUG) injectDecode(intent)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        emergencyUnkeyHook = null
        com.js8call.example.ai.RadioGate.sending = false
        Log.i(TAG, "Service destroyed")
        (getSystemService(AUDIO_SERVICE) as AudioManager)
            .unregisterAudioDeviceCallback(audioDeviceCallback)
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        heartbeatHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacks(reportTicker)
        messageLogger?.shutdown()

        stopEngine()
        holdCpu(false)
        txHandlerThread.quitSafely()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away (or Close all) is a habit, not a decision to go off the air:
        // by default the receiver keeps running; Stop is in the notification.
        val keep = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_KEEP_RECEIVING, true)
        if (keep && engineRunning) {
            Log.i(TAG, "Task removed; receiver keeps running (Stop is in the notification)")
        } else {
            Log.i(TAG, "Task removed; stopping engine")
            if (engineRunning) PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_RECEIVER_WANTED, false).apply()
            stopEngine()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, JS8EngineService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_exit),
                PendingIntent.getService(this, 1, Intent(this, JS8EngineService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE)
            )
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Build the rig transports. Called again on stop, once the old ones have
     * been handed to the teardown, so a restart never reaches a closing link.
     */
    private fun createRigTransports() {
        usbSerialBridge = UsbSerialBridge(applicationContext)
        bluetoothSerialBridge = BluetoothSerialBridge(applicationContext)
        trusdxDirectSerial = TruSdxDirectSerial(applicationContext)
        qmxDirectSerial = QmxDirectSerial(applicationContext)
        hamlibRigControl = HamlibRigControl()
        trusdxSerialSession = trusdxDirectSerial?.let { direct ->
            TruSdxSerialSession(
                direct,
                object : TruSdxSerialSession.Listener {
                    override fun onCatMessage(message: String) {
                        if (isTruSdxDiagnosticsEnabled()) {
                            Log.v(TAG, "TruSDX CAT <= $message")
                        }
                    }

                    override fun onAudioFrame(samplesU8: ByteArray) {
                        handleTruSdxAudioFrame(samplesU8)
                    }

                    override fun onParserResync(reason: String) {
                        trusdxParserResyncs += 1
                        if (isTruSdxDiagnosticsEnabled()) {
                            Log.w(TAG, "TruSDX parser resync ($reason), count=$trusdxParserResyncs")
                        }
                    }

                    override fun onIoError(message: String) {
                        Log.w(TAG, "TruSDX I/O error: $message")
                        trusdxConnected = false
                        broadcastError("TruSDX serial link lost")
                    }

                }
            )
        }
        qmxCatSession = qmxDirectSerial?.let { direct ->
            QmxCatSession(direct, object : QmxCatSession.Listener {
                override fun onFrequency(frequencyHz: Long) {
                    currentDialHz = frequencyHz
                    mainHandler.post { broadcastRadioFrequency(frequencyHz); noteDialChange(frequencyHz, fromRig = true) }
                }

                override fun onMessage(message: String) {
                    Log.d(TAG, "QMX CAT <= $message")
                }

                override fun onIoError(message: String) {
                    Log.w(TAG, "QMX CAT I/O error: $message")
                    qmxConnected = false
                    mainHandler.post { broadcastError("QMX CAT link lost") }
                }
            })
        }
        uvProSession = createUvProSession()
    }

    private fun createUvProSession(): UvProSession {
        // Callbacks from a replaced session must not touch the current one.
        val generation = ++uvproSessionGeneration
        return UvProSession(applicationContext, object : UvProSession.Listener {
            override fun onRxAudio(samples: ShortArray, sampleRateHz: Int) {
                if (generation != uvproSessionGeneration) return
                engine?.submitAudioRaw(samples, samples.size, sampleRateHz, System.nanoTime())
            }

            override fun onTxTimeout() {
                mainHandler.post {
                    if (generation != uvproSessionGeneration) return@post
                    failTransmitForPtt(txPttGeneration, "UV-Pro transmit timed out; PTT released")
                }
            }

            override fun onIoError(message: String) {
                if (generation != uvproSessionGeneration) return
                Log.w(TAG, "UV-Pro I/O error: $message")
                uvproConnected = false
                mainHandler.post { broadcastError("UV-Pro Bluetooth link lost") }
            }
        })
    }

    private fun startEngine(resumeGeneration: Int? = null) {
        val generation = if (resumeGeneration == null) {
            if (engineStartInProgress || engine != null) {
                Log.w(TAG, "Ignoring duplicate engine start request")
                broadcastEngineState(if (engineStartInProgress) STATE_STARTING else STATE_RUNNING)
                return
            }
            val stoppingFor = engineStoppingSinceMs.let { if (it > 0) System.currentTimeMillis() - it else -1L }
            if (stoppingFor in 0..15_000L) {
                Log.w(TAG, "Ignoring engine start while the previous engine is still releasing audio")
                broadcastError("The receiver is still shutting down. Please try again in a few seconds.")
                broadcastEngineState(STATE_STOPPED)
                stopSelf()
                return
            }
            if (rigTeardownPending) {
                Log.w(TAG, "Ignoring engine start while rig teardown is still active")
                broadcastError("Rig control is still stopping. Please try again.")
                broadcastEngineState(STATE_STOPPED)
                stopSelf()
                return
            }
            if (trusdxStartupWorkerActive) {
                Log.w(TAG, "Ignoring engine start while TruSDX cleanup is still active")
                broadcastError("TruSDX serial initialization is still stopping. Please try again.")
                broadcastEngineState(STATE_STOPPED)
                stopSelf()
                return
            }
            if (qmxStartupWorkerActive) {
                Log.w(TAG, "Ignoring engine start while QMX cleanup is still active")
                broadcastError("QMX CAT initialization is still stopping. Please try again.")
                broadcastEngineState(STATE_STOPPED)
                stopSelf()
                return
            }
            if (sdrWorkersActive.get() > 0) {
                Log.w(TAG, "Ignoring engine start while the SDR is still closing")
                broadcastError("The SDR is still closing. Please try again.")
                broadcastEngineState(STATE_STOPPED)
                stopSelf()
                return
            }
            if (uvproWorkersActive.get() > 0) {
                Log.w(TAG, "Ignoring engine start while UV-Pro cleanup is still active")
                broadcastError("UV-Pro Bluetooth is still disconnecting. Please try again.")
                broadcastEngineState(STATE_STOPPED)
                stopSelf()
                return
            }

            engineStartInProgress = true
            val nextGeneration = ++engineStartGeneration
            try {
                initializeRigControl(nextGeneration)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing rig control", e)
                failEngineStart(nextGeneration, "Error initializing rig control: ${e.message}")
                return
            }

            if (rigControlMode == "trusdx_serial" || rigControlMode == "qmx_serial" || rigControlMode == "btech_uvpro" || isSdrMode()) {
                return
            }
            nextGeneration
        } else {
            if (!isCurrentEngineStart(resumeGeneration)) {
                Log.i(TAG, "Ignoring stale engine start continuation generation=$resumeGeneration")
                return
            }
            resumeGeneration
        }

        try {
            // Create callback handler that marshals to main thread
            val callbackHandler = object : JS8Engine.CallbackHandler {
                override fun onDecoded(
                    utc: Int, snr: Int, dt: Float, freq: Float,
                    text: String, type: Int, quality: Float, mode: Int, driftMs: Int
                ) {
                    Log.d(TAG, "Decoded: $text (SNR: $snr dB)")
                    logRxDecode(text, snr, freq, mode)

                    // Broadcast on main thread
                    mainHandler.post {
                        maybeApplyTimeSync(driftMs, freq)
                        if (interferenceWatch()) {
                            com.js8call.example.interference.Interference.onDecode(this@JS8EngineService, System.currentTimeMillis(), interferenceDialHz(), snr)
                        }
                        handleDecode(utc, snr, dt, freq, text, type, quality, mode, driftMs)
                        maybeReportToPskReporter(utc, snr, freq, text)
                    }
                }

                override fun onSpectrum(
                    bins: FloatArray, binHz: Float,
                    powerDb: Float, peakDb: Float
                ) {
                    spectrumEventCount += 1
                    // Interference watch (receive only): the same spectrum the waterfall draws.
                    if (interferenceWatch()) {
                        val now = System.currentTimeMillis()
                        if (engine?.isTransmitting() == true) com.js8call.example.interference.Interference.onTransmit(this@JS8EngineService, now)
                        com.js8call.example.interference.Interference.onSpectrum(this@JS8EngineService, now, interferenceDialHz(), bins, binHz)
                    }
                    if (rigControlMode == "trusdx_serial" && spectrumEventCount % 20L == 0L) {
                        Log.i(TAG, "Spectrum events: count=$spectrumEventCount bins=${bins.size} binHz=$binHz power=$powerDb peak=$peakDb")
                    }
                    // Broadcast spectrum data (main thread)
                    mainHandler.post {
                        broadcastSpectrum(bins, binHz, powerDb, peakDb)
                    }
                }

                override fun onDecodeStarted(submodes: Int) {
                    Log.d(TAG, "Decode started: submodes=$submodes")
                    mainHandler.post {
                        broadcastDecodeStarted(submodes)
                    }
                }

                override fun onDecodeFinished(count: Int) {
                    Log.d(TAG, "Decode finished: count=$count")
                    mainHandler.post {
                        broadcastDecodeFinished(count)
                    }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Engine error: $message")
                    mainHandler.post {
                        broadcastError(message)
                    }
                }

                override fun onLog(level: Int, message: String) {
                    val levelStr = when (level) {
                        0 -> "TRACE"
                        1 -> "DEBUG"
                        2 -> "INFO"
                        3 -> "WARN"
                        4 -> "ERROR"
                        else -> "LOG"
                    }
                    Log.d(TAG, "[$levelStr] $message")
                }

                override fun onTxAudio(samples: ShortArray, sampleRateHz: Int) {
                    handleTruSdxTxAudio(samples, sampleRateHz)
                    handleUvProTxAudio(samples, sampleRateHz)
                }
            }

            // Create engine
            engine = JS8Engine.create(
                sampleRateHz = 12000,
                submodes = rxMaskOf(SUBMODE_NORMAL, SUBMODE_FAST, SUBMODE_TURBO, SUBMODE_SLOW),
                callbackHandler = callbackHandler,
                enableTxAudioTap = rigControlMode == "trusdx_serial" || rigControlMode == "btech_uvpro",
                useQmxUsbAudio = rigControlMode == "qmx_serial"
            )

            if (rigControlMode == "qmx_serial") {
                selectedOutputDeviceId = findOutputDeviceId(selectedAudioDeviceId)
                engine?.setOutputDevice(selectedOutputDeviceId)
                Log.i(
                    TAG,
                    "QMX USB audio route: input=$selectedAudioDeviceId output=$selectedOutputDeviceId (${getOutputDeviceName(selectedOutputDeviceId)})"
                )
            }

            // Start engine
            if (engine?.start() == true) {
                Log.i(TAG, "Engine started successfully")
                if (reconnectStartPending) {
                    reconnectStartPending = false
                    UsbDropouts.recordRecovery(this, System.currentTimeMillis())
                }
                spectrumEventCount = 0

                applyTxBoostSetting()
                applyTimeDriftSetting()

                if (rigControlMode == "trusdx_serial") {
                    Log.i(TAG, "TruSDX mode active: skipping microphone capture")
                    val label = if (selectedAudioDeviceId == TRUSDX_AUDIO_SPEAKER_ID) {
                        TRUSDX_AUDIO_SPEAKER_NAME
                    } else {
                        TRUSDX_AUDIO_SERIAL_NAME
                    }
                    broadcastAudioDevice(label)
                    broadcastEngineState(STATE_RUNNING)
                    broadcastProcessTxQueue()
                    startTruSdxRxWorker()
                    scheduleTruSdxRxKeepAlive()
                } else if (rigControlMode == "btech_uvpro") {
                    Log.i(TAG, "UV-Pro mode active: skipping microphone capture")
                    broadcastAudioDevice(UVPRO_AUDIO_NAME)
                    broadcastEngineState(STATE_RUNNING)
                    broadcastProcessTxQueue()
                } else if (isSdrMode()) {
                    // Audio comes from the dongle; receive only, so no queued sends to start.
                    Log.i(TAG, "SDR receiver active: ${sdrSession?.description.orEmpty()}")
                    broadcastAudioDevice(sdrSession?.kind?.label ?: "SDR")
                    broadcastEngineState(STATE_RUNNING)
                } else {
                    // Start audio capture with selected device (if any)
                    scoRestartAttempts = 0
                    scoSourceIndex = 0
                    scoSilenceCheckToken++
                    scoStartToken++
                    if (isScoInputDevice(selectedAudioDeviceId)) {
                        startAudioCaptureWithScoWait(engine!!, selectedAudioDeviceId)
                    } else {
                        startAudioCapture(engine!!, selectedAudioDeviceId)
                    }
                }
                engineStartInProgress = false
            } else {
                Log.e(TAG, "Failed to start engine")
                engine?.close()
                engine = null
                failEngineStart(generation, "Failed to start engine")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting engine", e)
            engine?.close()
            engine = null
            failEngineStart(generation, "Error starting engine: ${e.message}")
        }
    }

    private fun isCurrentEngineStart(generation: Int): Boolean {
        return engineStartInProgress && engineStartGeneration == generation
    }

    private fun failEngineStart(generation: Int, message: String) {
        if (!isCurrentEngineStart(generation)) return
        if (rigControlMode == "trusdx_serial" && trusdxSerialSession?.isConnected() == true) {
            trusdxStartupWorkerActive = true
            Thread {
                try {
                    trusdxSerialSession?.stop()
                } finally {
                    trusdxStartupWorkerActive = false
                }
            }.start()
        }
        if (rigControlMode == "qmx_serial" && qmxCatSession?.isConnected() == true) {
            qmxStartupWorkerActive = true
            Thread {
                try {
                    qmxCatSession?.stop()
                } finally {
                    qmxStartupWorkerActive = false
                }
            }.start()
        }
        if (isSdrMode()) {
            val failed = sdrSession
            sdrSession = null
            sdrConnected = false
            if (failed != null) {
                sdrWorkersActive.incrementAndGet()
                Thread { try { failed.stop() } finally { sdrWorkersActive.decrementAndGet() } }.start()
            }
        }
        if (rigControlMode == "btech_uvpro") {
            // A fresh session for the next start, so this stop cannot close it.
            val failedSession = uvProSession
            uvProSession = createUvProSession()
            uvproConnected = false
            uvproWorkersActive.incrementAndGet()
            Thread {
                try {
                    failedSession?.stop()
                } finally {
                    uvproWorkersActive.decrementAndGet()
                }
            }.start()
        }
        engineStartInProgress = false
        trusdxInitInProgress = false
        trusdxConnected = false
        rigCtlErrorShown = true
        broadcastError(message)
        broadcastEngineState(STATE_ERROR)
    }

    private fun initializeRigControl(generation: Int) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rigControlEnabled = prefs.getBoolean("rig_control_enabled", false)
        val rigType = prefs.getString("rig_type", "none")

        if (!rigControlEnabled || rigType == "none") {
            Log.i(TAG, "Rig control not enabled")
            rigControlMode = "none"
            rtsPttConnected = false
            trusdxConnected = false
            qmxConnected = false
            rtsPttTransport = null
            return
        }

        rigControlMode = rigType ?: "none"
        rtsPttConnected = false
        trusdxConnected = false
        qmxConnected = false
        rtsPttTransport = null
        when (rigType) {
            "network" -> initializeNetworkRigControl()
            "hamlib_usb" -> initializeHamlibUsbControl()
            "rts_ptt" -> initializeRtsPttControl()
            "trusdx_serial" -> initializeTruSdxControl(generation)
            "qmx_serial" -> initializeQmxControl(generation)
            "btech_uvpro" -> initializeUvProControl(generation)
            "rtl_sdr", "airspy_hf" -> initializeSdr(generation, rigType)
            else -> Log.w(TAG, "Unknown rig type: $rigType")
        }
    }

    private fun sdrSettings(): SdrSession.Settings {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val ds = prefs.getString(PREF_RTLSDR_DIRECT_SAMPLING, "0")?.toIntOrNull() ?: 0
        val ppm = prefs.getString(PREF_RTLSDR_PPM, "0")?.trim()?.toIntOrNull()?.coerceIn(-500, 500) ?: 0
        val gain = prefs.getString(PREF_RTLSDR_GAIN, "")?.trim()?.toDoubleOrNull()
        return SdrSession.Settings(ds, ppm, gain?.let { (it * 10).toInt().coerceIn(0, 600) } ?: -1)
    }

    private fun sdrDialHz(): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return currentDialHz.takeIf { it > 0 } ?: prefs.getString("last_frequency", "")?.toLongOrNull() ?: 7_078_000L
    }

    /** An SDR dongle is the receiver: rig type rtl_sdr or airspy_hf. Receive only. */
    private fun isSdrMode() = rigControlMode == "rtl_sdr" || rigControlMode == "airspy_hf"

    private fun initializeSdr(generation: Int, rigType: String) {
        val kind = if (rigType == "airspy_hf") SdrSession.Kind.AIRSPY_HF else SdrSession.Kind.RTL_SDR
        Log.i(TAG, "Initializing ${kind.label} receiver")
        sdrConnected = false
        val session = SdrSession(applicationContext, kind, object : SdrSession.Listener {
            override fun onRxAudio(samples: ShortArray, count: Int, sampleRateHz: Int) {
                engine?.submitAudioRaw(samples, count, sampleRateHz, System.nanoTime())
            }

            override fun onLost(message: String) {
                mainHandler.post {
                    if (!isSdrMode()) return@post
                    sdrConnected = false
                    broadcastError(message)
                }
            }
        })
        sdrSession = session
        val dial = sdrDialHz()
        val settings = sdrSettings()
        sdrWorkersActive.incrementAndGet()
        Thread {
            val opened = try {
                session.start(dial, settings)
            } catch (error: Throwable) {
                Log.e(TAG, "${kind.label} start failed", error)
                false
            }
            mainHandler.post {
                sdrWorkersActive.decrementAndGet()
                if (!isCurrentEngineStart(generation)) {
                    if (opened) Thread { session.stop() }.start()
                } else if (opened) {
                    sdrConnected = true
                    currentDialHz = dial
                    rigCtlErrorShown = false
                    startEngine(generation)
                } else {
                    failEngineStart(generation, session.lastError ?: "The ${kind.label} could not be started.")
                }
            }
        }.start()
    }

    private fun initializeUvProControl(generation: Int) {
        Log.i(TAG, "Initializing UV-Pro Bluetooth audio")
        uvproConnected = false
        val session = uvProSession
        uvproWorkersActive.incrementAndGet()
        Thread {
            val opened = try {
                session?.start() == true
            } catch (error: Throwable) {
                Log.e(TAG, "UV-Pro start failed", error)
                false
            }
            mainHandler.post {
                uvproWorkersActive.decrementAndGet()
                if (!isCurrentEngineStart(generation)) {
                    if (opened) session?.stop()
                } else if (opened) {
                    uvproConnected = true
                    rigCtlErrorShown = false
                    startEngine(generation)
                } else {
                    failEngineStart(generation, session?.getLastError() ?: "Failed to connect to the UV-Pro.")
                }
            }
        }.start()
    }

    private fun initializeTruSdxControl(generation: Int) {
        Log.i(TAG, "Initializing TruSDX serial control")
        trusdxInitInProgress = true
        trusdxConnected = false
        trusdxParserResyncs = 0
        trusdxRxFrames = 0
        trusdxRxSamples = 0
        trusdxTxFrames = 0
        trusdxTxSamples = 0
        trusdxTxDrops = 0
        trusdxTxSilentFrames = 0
        trusdxRxUnderruns = 0
        trusdxRxSubmitDrops = 0
        trusdxLastRxAudioNs = 0L
        trusdxLastRxRearmNs = 0L
        trusdxTxIntentActive = false
        trusdxRxRateWindowStartNs = 0L
        trusdxRxRateWindowSamples = 0L
        trusdxRxFrameDrops = 0L
        trusdxRxKeepaliveToken++
        trusdxRxKeepaliveCount = 0L
        clearTruSdxRxFrameQueue()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val selectedPort = prefs.getString("rig_hamlib_usb_port", "auto")?.trim().orEmpty()
        val selection = resolveSerialSelection(selectedPort, prefs)
        if (selection == null) {
            Log.w(TAG, "Invalid serial port selection: $selectedPort")
            failEngineStart(generation, "Invalid serial port selection for TruSDX control.")
            return
        }

        if (selection.transport != SerialTransport.USB) {
            Log.w(TAG, "TruSDX only supports USB serial transport")
            failEngineStart(generation, "TruSDX mode supports USB serial only.")
            return
        }

        openTruSdxSerialUsb(selection, generation)
    }

    private fun openTruSdxSerialUsb(selection: SerialSelection, generation: Int) {
        var deviceId = selection.usbDeviceId
        var portIndex = selection.portIndex
        // A hand-picked port goes stale when the radio is unplugged (Android renumbers devices).
        if (deviceId != null && UsbPermissionHelper.findUsbDeviceById(this, deviceId) == null) deviceId = null
        if (deviceId == null) {
            val ports = try {
                UsbSerialPortCatalog.listPorts(this)
            } catch (_: Throwable) {
                emptyList()
            }
            val firstPort = ports.firstOrNull()
            if (firstPort != null) {
                deviceId = firstPort.deviceId
                portIndex = firstPort.portIndex
                Log.i(TAG, "Auto-selected USB serial port for TruSDX: ${firstPort.label}")
            }
        }

        val usbDevice = if (deviceId != null) {
            UsbPermissionHelper.findUsbDeviceById(this, deviceId)
        } else {
            null
        }

        if (usbDevice == null) {
            Log.w(TAG, "No USB serial device found for TruSDX")
            failEngineStart(generation, "No USB serial device found for TruSDX control.")
            return
        }

        Log.i(TAG, "TruSDX USB device selected: ${usbDevice.deviceName} (id=${usbDevice.deviceId}) port=$portIndex")

        if (!UsbPermissionHelper.hasPermission(this, usbDevice)) {
            Log.i(TAG, "Requesting USB permission for TruSDX device...")
            UsbPermissionHelper.requestPermission(this, usbDevice) { granted ->
                if (!isCurrentEngineStart(generation)) {
                    Log.i(TAG, "Ignoring stale TruSDX permission result generation=$generation")
                    return@requestPermission
                }
                if (granted) {
                    Log.i(TAG, "USB permission granted for TruSDX device")
                    openTruSdxSerialUsbInternal(usbDevice.deviceId, portIndex, generation)
                } else {
                    Log.w(TAG, "USB permission denied for TruSDX device")
                    failEngineStart(generation, "USB permission denied. Please grant USB access in Settings.")
                }
            }
        } else {
            Log.i(TAG, "USB permission already granted for TruSDX device")
            openTruSdxSerialUsbInternal(usbDevice.deviceId, portIndex, generation)
        }
    }

    private fun openTruSdxSerialUsbInternal(deviceId: Int, portIndex: Int, generation: Int) {
        trusdxStartupWorkerActive = true
        Thread {
            try {
                if (!isCurrentEngineStart(generation)) {
                    trusdxStartupWorkerActive = false
                    return@Thread
                }
                val session = trusdxSerialSession
                val opened = session?.start(deviceId, portIndex) == true
                if (opened) {
                    session.setSpeakerEnabled(selectedAudioDeviceId == TRUSDX_AUDIO_SPEAKER_ID)
                }
                val initialized = opened && session.initializeRigState() == true
                if (!isCurrentEngineStart(generation)) {
                    if (opened) session.stop()
                    trusdxStartupWorkerActive = false
                    return@Thread
                }
                mainHandler.post {
                    if (!isCurrentEngineStart(generation)) {
                        if (opened) session.stop()
                        trusdxStartupWorkerActive = false
                        return@post
                    }
                    if (initialized) {
                        trusdxConnected = true
                        rigCtlErrorShown = false
                        trusdxInitInProgress = false
                        trusdxStartupWorkerActive = false
                        Log.i(TAG, "TruSDX serial control ready device=$deviceId port=$portIndex")
                        if (currentDialHz > 0L) {
                            Thread {
                                val ok = session.setFrequency(currentDialHz) == true
                                Log.i(TAG, "TruSDX initial frequency apply: hz=$currentDialHz ok=$ok")
                            }.start()
                        }
                        startEngine(generation)
                    } else {
                        session?.stop()
                        trusdxStartupWorkerActive = false
                        trusdxConnected = false
                        Log.w(TAG, "Failed to initialize TruSDX serial control")
                        failEngineStart(generation, "Failed to initialize TruSDX serial control.")
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "TruSDX serial initialization failed", error)
                trusdxStartupWorkerActive = false
                mainHandler.post {
                    failEngineStart(generation, "Failed to initialize TruSDX serial control: ${error.message}")
                }
            }
        }.start()
    }

    private fun initializeQmxControl(generation: Int) {
        Log.i(TAG, "Initializing QMX CAT control")
        qmxConnected = false
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val selectedPort = prefs.getString("rig_hamlib_usb_port", "auto")?.trim().orEmpty()
        val selection = resolveSerialSelection(selectedPort, prefs)
        if (selection?.transport != SerialTransport.USB) {
            failEngineStart(generation, "QMX mode supports USB CAT only.")
            return
        }

        var deviceId = selection.usbDeviceId
        var portIndex = selection.portIndex
        // A hand-picked port goes stale when the radio is unplugged (Android renumbers devices).
        if (deviceId != null && UsbPermissionHelper.findUsbDeviceById(this, deviceId) == null) deviceId = null
        if (deviceId == null) {
            val firstPort = try { UsbSerialPortCatalog.listPorts(this).firstOrNull() } catch (_: Throwable) { null }
            deviceId = firstPort?.deviceId
            portIndex = firstPort?.portIndex ?: 0
        }
        val usbDevice = deviceId?.let { UsbPermissionHelper.findUsbDeviceById(this, it) }
        if (usbDevice == null) {
            failEngineStart(generation, "No USB serial device found for QMX CAT control.")
            return
        }

        val open = {
            qmxStartupWorkerActive = true
            Thread {
                try {
                    val baudRate = prefs.getString("qmx_cat_baud", "115200")?.toIntOrNull() ?: 115200
                    val session = qmxCatSession
                    val opened = session?.start(usbDevice.deviceId, portIndex, baudRate) == true
                    val initialized = opened && session.initialize() == true
                    mainHandler.post {
                        if (!isCurrentEngineStart(generation)) {
                            if (opened) session.stop()
                        } else if (initialized) {
                            qmxConnected = true
                            rigCtlErrorShown = false
                            if (currentDialHz > 0L) session.setFrequency(currentDialHz)
                            startEngine(generation)
                        } else {
                            session?.stop()
                            failEngineStart(generation, "Failed to initialize QMX CAT control.")
                        }
                        qmxStartupWorkerActive = false
                    }
                } catch (error: Throwable) {
                    qmxStartupWorkerActive = false
                    mainHandler.post {
                        failEngineStart(generation, "Failed to initialize QMX CAT control: ${error.message}")
                    }
                }
            }.start()
        }
        if (UsbPermissionHelper.hasPermission(this, usbDevice)) {
            open()
        } else {
            UsbPermissionHelper.requestPermission(this, usbDevice) { granted ->
                if (!isCurrentEngineStart(generation)) return@requestPermission
                if (granted) open() else failEngineStart(generation, "USB permission denied for QMX CAT control.")
            }
        }
    }

    private fun applyTxBoostSetting() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // The generic boost uses soft limiting, which corrupts QMX's narrow FSK tones.
        // QMX receives the engine's full-scale USB waveform without it.
        val txBoostEnabled = rigControlMode != "qmx_serial" && prefs.getBoolean("tx_boost_enabled", false)
        engine?.setTxBoostEnabled(txBoostEnabled)
        Log.i(TAG, "TX boost: ${if (txBoostEnabled) "enabled (+10 dB)" else "disabled"}${if (rigControlMode == "qmx_serial") " for QMX clean USB audio" else ""}")
    }

    private fun initializeNetworkRigControl() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val host = prefs.getString("rigctld_host", "localhost") ?: "localhost"
        val portStr = prefs.getString("rigctld_port", "4532") ?: "4532"
        val port = portStr.toIntOrNull() ?: 4532

        Log.i(TAG, "Initializing network rig control: $host:$port")

        // Connect on background thread to avoid NetworkOnMainThreadException
        Thread {
            try {
                rigCtlClient = RigCtlClient(host, port)
                rigCtlConnected = rigCtlClient?.connect() == true
                rigCtlErrorShown = false

                mainHandler.post {
                    if (rigCtlConnected) {
                        Log.i(TAG, "Connected to rigctld at $host:$port")
                    } else {
                        Log.w(TAG, "Failed to connect to rigctld at $host:$port")
                        broadcastError("Failed to connect to rigctld. Rig control unavailable.")
                        rigCtlErrorShown = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing network rig control", e)
                mainHandler.post {
                    broadcastError("Error connecting to rigctld: ${e.message}")
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private fun initializeHamlibUsbControl() {
        Log.i(TAG, "Initializing serial rig control (Hamlib)")
        hamlibRigConnected = false

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rigModelStr = prefs.getString("rig_hamlib_model", "0")?.trim().orEmpty()
        val selectedPort = prefs.getString("rig_hamlib_usb_port", "auto")?.trim().orEmpty()

        val rigModel = rigModelStr.toIntOrNull() ?: 0
        Log.i(TAG, "Hamlib rig model selected: $rigModel")
        if (rigModel <= 0) {
            Log.w(TAG, "Hamlib rig model not selected")
            broadcastError("Select a Hamlib rig model before enabling serial control.")
            rigCtlErrorShown = true
            return
        }

        val selection = resolveSerialSelection(selectedPort, prefs)
        if (selection == null) {
            Log.w(TAG, "Invalid serial port selection: $selectedPort")
            broadcastError("Invalid serial port selection for Hamlib control.")
            rigCtlErrorShown = true
            return
        }

        when (selection.transport) {
            SerialTransport.USB -> openHamlibSerialUsb(rigModel, selection)
            SerialTransport.BLUETOOTH -> openHamlibSerialBluetooth(rigModel, selection)
        }
    }

    private fun initializeRtsPttControl() {
        Log.i(TAG, "Initializing RTS PTT control")
        rtsPttConnected = false
        rtsPttTransport = null

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val selectedPort = prefs.getString("rig_hamlib_usb_port", "auto")?.trim().orEmpty()

        val selection = resolveSerialSelection(selectedPort, prefs)
        if (selection == null) {
            Log.w(TAG, "Invalid serial port selection: $selectedPort")
            broadcastError("Invalid serial port selection for RTS PTT control.")
            rigCtlErrorShown = true
            return
        }

        when (selection.transport) {
            SerialTransport.USB -> openRtsSerialUsb(selection)
            SerialTransport.BLUETOOTH -> openRtsSerialBluetooth(selection)
        }
    }

    private fun openRtsSerialUsb(selection: SerialSelection) {
        val params = readSerialParams()

        usbSerialBridge?.registerNative()

        var deviceId = selection.usbDeviceId
        var portIndex = selection.portIndex
        if (deviceId == null) {
            val ports = try {
                UsbSerialPortCatalog.listPorts(this)
            } catch (_: Throwable) {
                emptyList()
            }
            val firstPort = ports.firstOrNull()
            if (firstPort != null) {
                deviceId = firstPort.deviceId
                portIndex = firstPort.portIndex
                Log.i(TAG, "Auto-selected USB serial port for RTS PTT: ${firstPort.label}")
            }
        }

        val usbDevice = if (deviceId != null) {
            UsbPermissionHelper.findUsbDeviceById(this, deviceId)
        } else {
            null
        }

        if (usbDevice == null) {
            if (selection.path == "auto") {
                val btPorts = try {
                    BluetoothSerialPortCatalog.listPorts(this)
                } catch (_: Throwable) {
                    emptyList()
                }
                val btPort = btPorts.firstOrNull()
                if (btPort != null) {
                    val btSelection = SerialSelection(
                        transport = SerialTransport.BLUETOOTH,
                        path = "android-bt:${btPort.address}:${btPort.portIndex}",
                        usbDeviceId = null,
                        btAddress = btPort.address,
                        portIndex = btPort.portIndex
                    )
                    Log.i(TAG, "Auto-selected Bluetooth serial port for RTS PTT: ${btPort.label}")
                    openRtsSerialBluetooth(btSelection)
                    return
                }
            }
            Log.w(TAG, "No USB serial device found for RTS PTT")
            broadcastError("No USB serial device found for RTS PTT control.")
            rigCtlErrorShown = true
            return
        }

        Log.i(TAG, "RTS PTT USB device selected: ${usbDevice.deviceName} (id=${usbDevice.deviceId}) port=$portIndex")

        if (!UsbPermissionHelper.hasPermission(this, usbDevice)) {
            Log.i(TAG, "Requesting USB permission for RTS PTT device...")
            UsbPermissionHelper.requestPermission(this, usbDevice) { granted ->
                if (granted) {
                    Log.i(TAG, "USB permission granted for RTS PTT device")
                    openRtsSerialUsbInternal(usbDevice.deviceId, portIndex, params)
                } else {
                    Log.w(TAG, "USB permission denied for RTS PTT device")
                    broadcastError("USB permission denied. Please grant USB access in Settings.")
                    rigCtlErrorShown = true
                }
            }
        } else {
            Log.i(TAG, "USB permission already granted for RTS PTT device")
            openRtsSerialUsbInternal(usbDevice.deviceId, portIndex, params)
        }
    }

    private fun openRtsSerialUsbInternal(deviceId: Int, portIndex: Int, params: SerialParams) {
        val bridge = usbSerialBridge
        Thread {
            // A timed-out Hamlib open from an earlier setting would close this port when it gives up.
            val ok = HamlibRigControl.waitForPendingOpens(PENDING_RIG_OPEN_WAIT_MS) && bridge?.open(
                deviceId,
                portIndex,
                params.baudRate,
                params.dataBits,
                params.stopBits,
                params.parityValue
            ) == true

            mainHandler.post {
                if (bridge !== usbSerialBridge) return@post
                rtsPttConnected = ok
                rigCtlErrorShown = false
                if (ok) {
                    rtsPttTransport = SerialTransport.USB
                    usbSerialBridge?.setRts(false)
                    Log.i(TAG, "RTS PTT USB serial opened device=$deviceId port=$portIndex")
                } else {
                    Log.w(TAG, "RTS PTT USB serial open failed for device=$deviceId port=$portIndex")
                    broadcastError("Failed to open USB serial port for RTS PTT control.")
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private fun openRtsSerialBluetooth(selection: SerialSelection) {
        val params = readSerialParams()

        bluetoothSerialBridge?.registerNative()

        val address = selection.btAddress
        if (address == null) {
            Log.w(TAG, "Bluetooth address missing for RTS PTT")
            broadcastError("No Bluetooth serial device selected for RTS PTT control.")
            rigCtlErrorShown = true
            return
        }

        if (BluetoothSerialPortCatalog.findBondedDevice(this, address) == null) {
            Log.w(TAG, "Bluetooth device not paired or unavailable: $address")
            broadcastError("Bluetooth serial device not available. Check pairing and settings.")
            rigCtlErrorShown = true
            return
        }

        val bridge = bluetoothSerialBridge
        Thread {
            // A timed-out Hamlib open from an earlier setting would close this port when it gives up.
            val ok = HamlibRigControl.waitForPendingOpens(PENDING_RIG_OPEN_WAIT_MS) && bridge?.open(
                address,
                selection.portIndex,
                params.baudRate,
                params.dataBits,
                params.stopBits,
                params.parityValue
            ) == true

            mainHandler.post {
                if (bridge !== bluetoothSerialBridge) return@post
                rtsPttConnected = ok
                rigCtlErrorShown = false
                if (ok) {
                    rtsPttTransport = SerialTransport.BLUETOOTH
                    bluetoothSerialBridge?.setRts(false)
                    Log.i(TAG, "RTS PTT Bluetooth serial opened addr=$address port=${selection.portIndex}")
                } else {
                    val detail = bluetoothSerialBridge?.getLastError().orEmpty()
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "RTS PTT Bluetooth open failed for addr=$address port=${selection.portIndex}: $detail")
                        broadcastError("Failed to open Bluetooth serial port for RTS PTT control: $detail")
                    } else {
                        Log.w(TAG, "RTS PTT Bluetooth open failed for addr=$address port=${selection.portIndex}")
                        broadcastError("Failed to open Bluetooth serial port for RTS PTT control.")
                    }
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private fun openHamlibSerialUsb(rigModel: Int, selection: SerialSelection) {
        val params = readSerialParams()

        usbSerialBridge?.registerNative()

        var deviceId = selection.usbDeviceId
        var portIndex = selection.portIndex
        // Auto: the device's other ports, tried in turn if the radio doesn't answer on the first.
        // Dual-port USB serial chips (the Xiegu X6100/X6200's WCH CH342, for one) carry CAT on
        // just one of them, and not always the first.
        var autoPorts: List<Int> = emptyList()
        // Android gives a USB device a new id every time it is plugged in, so a port picked by hand
        // goes stale after an unplug. Then do what Auto does rather than give up.
        if (deviceId != null && UsbPermissionHelper.findUsbDeviceById(this, deviceId) == null) {
            Log.w(TAG, "The USB serial device picked in Settings (id=$deviceId) isn't plugged in; looking for another")
            deviceId = null
        }
        if (deviceId == null) {
            val ports = try {
                UsbSerialPortCatalog.listPorts(this)
            } catch (_: Throwable) {
                emptyList()
            }
            val firstPort = ports.firstOrNull()
            if (firstPort != null) {
                deviceId = firstPort.deviceId
                portIndex = firstPort.portIndex
                autoPorts = ports.filter { it.deviceId == firstPort.deviceId }.map { it.portIndex }
                Log.i(TAG, "Auto-selected USB serial port: ${firstPort.label}")
            }
        }

        val usbDevice = if (deviceId != null) {
            UsbPermissionHelper.findUsbDeviceById(this, deviceId)
        } else {
            null
        }

        if (usbDevice == null) {
            if (selection.path == "auto") {
                val btPorts = try {
                    BluetoothSerialPortCatalog.listPorts(this)
                } catch (_: Throwable) {
                    emptyList()
                }
                val btPort = btPorts.firstOrNull()
                if (btPort != null) {
                    val btSelection = SerialSelection(
                        transport = SerialTransport.BLUETOOTH,
                        path = "android-bt:${btPort.address}:${btPort.portIndex}",
                        usbDeviceId = null,
                        btAddress = btPort.address,
                        portIndex = btPort.portIndex
                    )
                    Log.i(TAG, "Auto-selected Bluetooth serial port: ${btPort.label}")
                    openHamlibSerialBluetooth(rigModel, btSelection)
                    return
                }
            }
            Log.w(TAG, "No USB serial device found for Hamlib")
            broadcastError("No USB serial device found for rig control. Check the radio's USB cable, then Settings → Radio.")
            rigCtlErrorShown = true
            return
        }

        // Remembered from an earlier Auto connect: the port this radio answered on.
        val rememberKey = if (autoPorts.size > 1) "rig_auto_port_%04x_%04x_%d".format(java.util.Locale.US, usbDevice.vendorId, usbDevice.productId, rigModel) else null
        if (rememberKey != null) {
            val remembered = PreferenceManager.getDefaultSharedPreferences(this).getInt(rememberKey, -1)
            autoPorts = (listOf(remembered).filter { it in autoPorts } + autoPorts).distinct()
            portIndex = autoPorts.first()
        }
        val alternates = autoPorts.drop(1)

        Log.i(TAG, "Hamlib USB device selected: ${usbDevice.deviceName} (id=${usbDevice.deviceId}) port=$portIndex")

        if (!UsbPermissionHelper.hasPermission(this, usbDevice)) {
            Log.i(TAG, "Requesting USB permission for Hamlib device...")
            UsbPermissionHelper.requestPermission(this, usbDevice) { granted ->
                if (granted) {
                    Log.i(TAG, "USB permission granted for Hamlib device")
                    openHamlibSerialUsbInternal(rigModel, usbDevice.deviceId, portIndex, params, alternates, rememberKey)
                } else {
                    Log.w(TAG, "USB permission denied for Hamlib device")
                    broadcastError("USB permission denied. Please grant USB access in Settings.")
                    rigCtlErrorShown = true
                }
            }
        } else {
            Log.i(TAG, "USB permission already granted for Hamlib device")
            openHamlibSerialUsbInternal(rigModel, usbDevice.deviceId, portIndex, params, alternates, rememberKey)
        }
    }

    private fun openHamlibSerialUsbInternal(
        rigModel: Int,
        deviceId: Int,
        portIndex: Int,
        params: SerialParams,
        /** Auto mode: other ports of the same device to try if the radio doesn't answer. */
        alternates: List<Int> = emptyList(),
        /** Auto mode: where to remember the port that answered. */
        rememberKey: String? = null,
    ) {
        // Captured: a stop swaps these fields while this connects
        val bridge = usbSerialBridge
        val hamlib = hamlibRigControl
        Thread {
            var ok = false
            var anyPreopen = false
            var serialPath = ""
            var answeredOn: Int? = null
            var earlierOpenRunning = false
            for ((attempt, port) in (listOf(portIndex) + alternates).withIndex()) {
                if (bridge !== usbSerialBridge) return@Thread // stopped meanwhile
                // A rig open that timed out (this probe's last port, or an earlier session) is
                // still running and will close the serial bridge when it gives up: wait for it,
                // or that close would land on the port opened next.
                if (!HamlibRigControl.waitForPendingOpens(PENDING_RIG_OPEN_WAIT_MS)) {
                    Log.w(TAG, "An earlier rig open is still running; not opening the serial port again yet")
                    earlierOpenRunning = true
                    break
                }
                if (bridge !== usbSerialBridge) return@Thread
                if (attempt > 0) {
                    bridge?.close()
                    Log.i(TAG, "Radio didn't answer on the previous port; trying port $port")
                }
                val preopenOk = bridge?.open(
                    deviceId,
                    port,
                    params.baudRate,
                    params.dataBits,
                    params.stopBits,
                    params.parityValue
                ) == true
                if (!preopenOk) continue
                anyPreopen = true

                val resolvedPortIndex = bridge.getActivePortIndex() ?: port
                serialPath = "android-usb:$deviceId:$resolvedPortIndex"
                ok = hamlib?.openSerialPath(
                    rigModel,
                    serialPath,
                    params.baudRate,
                    params.dataBits,
                    params.stopBits,
                    params.parity
                ) == true
                if (ok) { answeredOn = port; break }
            }

            val answered = answeredOn
            if (!anyPreopen) {
                mainHandler.post {
                    // Stopped since this thread started
                    if (bridge !== usbSerialBridge) return@post
                    hamlibRigConnected = false
                    rigCtlErrorShown = true
                    Log.w(TAG, "Hamlib USB pre-open failed for device=$deviceId port=$portIndex")
                    broadcastError(
                        if (earlierOpenRunning) "The radio didn't answer an earlier connection attempt; try again in a moment."
                        else "Failed to open USB serial port for Hamlib control."
                    )
                }
                return@Thread
            }

            mainHandler.post {
                if (bridge !== usbSerialBridge) return@post
                hamlibRigConnected = ok
                rigCtlErrorShown = false
                if (ok) {
                    Log.i(TAG, "Hamlib rig opened (model=$rigModel path=$serialPath)")
                    if (rememberKey != null && answered != null) {
                        PreferenceManager.getDefaultSharedPreferences(this@JS8EngineService).edit { putInt(rememberKey, answered) }
                    }
                } else {
                    val detail = hamlib?.getLastError().orEmpty()
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "Hamlib rig open failed: $detail")
                        broadcastError("Failed to open Hamlib rig: $detail")
                    } else {
                        Log.w(TAG, "Hamlib rig open failed")
                        broadcastError("Failed to open Hamlib rig. Check serial connection and settings.")
                    }
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private fun openHamlibSerialBluetooth(rigModel: Int, selection: SerialSelection) {
        val params = readSerialParams()

        bluetoothSerialBridge?.registerNative()

        val address = selection.btAddress
        if (address == null) {
            Log.w(TAG, "Bluetooth address missing for Hamlib")
            broadcastError("No Bluetooth serial device selected for Hamlib control.")
            rigCtlErrorShown = true
            return
        }

        if (BluetoothSerialPortCatalog.findBondedDevice(this, address) == null) {
            Log.w(TAG, "Bluetooth device not paired or unavailable: $address")
            broadcastError("Bluetooth serial device not available. Check pairing and settings.")
            rigCtlErrorShown = true
            return
        }

        val bridge = bluetoothSerialBridge
        val hamlib = hamlibRigControl
        Thread {
            // As for USB: a timed-out rig open from before closes the bridge when it gives up.
            if (!HamlibRigControl.waitForPendingOpens(PENDING_RIG_OPEN_WAIT_MS)) {
                Log.w(TAG, "An earlier rig open is still running; not opening the Bluetooth port again yet")
                mainHandler.post {
                    if (bridge !== bluetoothSerialBridge) return@post
                    hamlibRigConnected = false
                    rigCtlErrorShown = true
                    broadcastError("The radio didn't answer an earlier connection attempt; try again in a moment.")
                }
                return@Thread
            }
            val preopenOk = bridge?.open(
                address,
                selection.portIndex,
                params.baudRate,
                params.dataBits,
                params.stopBits,
                params.parityValue
            ) == true

            if (!preopenOk) {
                val detail = bridge?.getLastError().orEmpty()
                mainHandler.post {
                    // Stopped since this thread started
                    if (bridge !== bluetoothSerialBridge) return@post
                    hamlibRigConnected = false
                    rigCtlErrorShown = true
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "Hamlib Bluetooth pre-open failed for addr=$address port=${selection.portIndex}: $detail")
                        broadcastError("Failed to open Bluetooth serial port for Hamlib control: $detail")
                    } else {
                        Log.w(TAG, "Hamlib Bluetooth pre-open failed for addr=$address port=${selection.portIndex}")
                        broadcastError("Failed to open Bluetooth serial port for Hamlib control.")
                    }
                }
                return@Thread
            }

            Log.i(TAG, "Hamlib Bluetooth pre-open ok for addr=$address port=${selection.portIndex}")
            val serialPath = selection.path
            Log.i(TAG, "Hamlib opening Bluetooth rig via path=$serialPath")
            val ok = hamlib?.openSerialPath(
                rigModel,
                serialPath,
                params.baudRate,
                params.dataBits,
                params.stopBits,
                params.parity
            ) == true

            mainHandler.post {
                if (bridge !== bluetoothSerialBridge) return@post
                hamlibRigConnected = ok
                rigCtlErrorShown = false
                if (ok) {
                    Log.i(TAG, "Hamlib rig opened (model=$rigModel path=$serialPath)")
                } else {
                    val detail = hamlib?.getLastError().orEmpty()
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "Hamlib rig open failed: $detail")
                        broadcastError("Failed to open Hamlib rig: $detail")
                    } else {
                        Log.w(TAG, "Hamlib rig open failed")
                        broadcastError("Failed to open Hamlib rig. Check serial connection and settings.")
                    }
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private data class SerialParams(
        val baudRate: Int,
        val dataBits: Int,
        val stopBits: Int,
        val parity: String,
        val parityValue: Int
    )

    private fun readSerialParams(): SerialParams {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val baudRate = prefs.getString("rig_serial_baud", "9600")?.toIntOrNull() ?: 9600
        val dataBits = prefs.getString("rig_serial_data_bits", "8")?.toIntOrNull() ?: 8
        val stopBits = prefs.getString("rig_serial_stop_bits", "1")?.toIntOrNull() ?: 1
        val parity = prefs.getString("rig_serial_parity", "none") ?: "none"
        val parityValue = when (parity.lowercase(Locale.US)) {
            "odd" -> 1
            "even" -> 2
            "mark" -> 3
            "space" -> 4
            else -> 0
        }
        return SerialParams(baudRate, dataBits, stopBits, parity, parityValue)
    }

    private fun resolveSerialSelection(
        selection: String,
        prefs: android.content.SharedPreferences
    ): SerialSelection? {
        val trimmed = selection.trim()
        if (trimmed.startsWith("android-bt:")) {
            val remainder = trimmed.removePrefix("android-bt:")
            val parts = remainder.split(':', limit = 2)
            val address = BluetoothSerialPortCatalog.normalizeAddress(parts.firstOrNull().orEmpty())
                ?: return null
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return SerialSelection(
                transport = SerialTransport.BLUETOOTH,
                path = "android-bt:$address:$portIndex",
                usbDeviceId = null,
                btAddress = address,
                portIndex = portIndex
            )
        }

        if (trimmed.startsWith("android-usb:")) {
            val remainder = trimmed.removePrefix("android-usb:")
            val parts = remainder.split(':', limit = 2)
            val deviceId = parts.firstOrNull()?.toIntOrNull() ?: return null
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return SerialSelection(
                transport = SerialTransport.USB,
                path = "android-usb:$deviceId:$portIndex",
                usbDeviceId = deviceId,
                btAddress = null,
                portIndex = portIndex
            )
        }

        if (trimmed.isNotEmpty() && trimmed != "auto") {
            val parts = trimmed.split(':', limit = 2)
            val deviceId = parts.firstOrNull()?.toIntOrNull() ?: return null
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return SerialSelection(
                transport = SerialTransport.USB,
                path = "android-usb:$deviceId:$portIndex",
                usbDeviceId = deviceId,
                btAddress = null,
                portIndex = portIndex
            )
        }

        val deviceId = prefs.getString("rig_usb_device_id", "")?.toIntOrNull()
        val portIndex = prefs.getString("rig_usb_port_index", "0")?.toIntOrNull() ?: 0
        return if (deviceId != null) {
            SerialSelection(
                transport = SerialTransport.USB,
                path = "android-usb:$deviceId:$portIndex",
                usbDeviceId = deviceId,
                btAddress = null,
                portIndex = portIndex
            )
        } else {
            SerialSelection(
                transport = SerialTransport.USB,
                path = "auto",
                usbDeviceId = null,
                btAddress = null,
                portIndex = 0
            )
        }
    }

    private enum class SerialTransport {
        USB,
        BLUETOOTH
    }

    private data class SerialSelection(
        val transport: SerialTransport,
        val path: String,
        val usbDeviceId: Int?,
        val btAddress: String?,
        val portIndex: Int
    )

    private fun interferenceWatch(): Boolean =
        com.js8call.example.interference.Interference.enabled(this)

    /** The dial for the interference watch: the radio's when rig control reports it, else the one picked in the app. */
    private fun interferenceDialHz(): Long =
        currentDialHz.takeIf { it > 0 }
            ?: PreferenceManager.getDefaultSharedPreferences(this).getString("last_frequency", "")?.trim()?.toLongOrNull()
            ?: 0L

    private fun stopEngine() {
        com.js8call.example.interference.Interference.stop()
        try {
            engineStartGeneration++
            engineStartInProgress = false
            trusdxInitInProgress = false
            synchronized(pttStateLock) {
                txPttGeneration++
                rigPttDesired = rigPttAsserted
                rigPttDesiredGeneration = txPttGeneration
                rigPttFailureCount = 0
                rigPttCompletion = null
                pttShuttingDown = true
                txPttFailed = false
            }
            engine?.setTransmitReady(false)
            scoSilenceCheckToken++
            scoStartToken++
            audioHelper?.stopCapture()
            audioHelper?.close()
            audioHelper = null
            stopTxMonitor()
            disableScoRouting()

            txHandler.removeCallbacksAndMessages(txWorkToken)
            synchronized(pttStateLock) {
                rigPttDesired = false
                rigPttDesiredGeneration = txPttGeneration
                rigPttCommandPending = false
                rigPttCompletion = null
            }
            // Rig teardown belongs on the TX handler, not here. With the radio
            // gone a CAT command blocks for hamlib's full timeout, and every
            // HamlibRigControl method shares one monitor, so close() waits
            // behind it. That was nine seconds on the main thread.
            val shutdownMode = rigControlMode
            val shutdownTransport = rtsPttTransport
            val shutdownHamlib = hamlibRigControl
            val shutdownUsb = usbSerialBridge
            val shutdownBluetooth = bluetoothSerialBridge
            val shutdownTruSdx = trusdxSerialSession
            val shutdownQmx = qmxCatSession
            val shutdownUvPro = uvProSession
            val shutdownSdr = sdrSession
            sdrSession = null
            sdrConnected = false
            if (shutdownSdr != null) sdrWorkersActive.incrementAndGet()
            val shutdownNetwork = rigCtlClient
            // Try to unkey whenever a rig was in use, not only when its link is flagged up: a link
            // marked down after an error may still reach a keyed radio. A dead link only costs a
            // timeout here, on the TX thread.
            val shouldReleasePtt = rigControlMode != "none"
            // Retries and the "may still be transmitting" warning only when this app could have
            // keyed the radio: a key command went out with no confirmed release since. Field
            // report (build 324): a receiver restart with no rig model picked yet retried for
            // ~7 s, raised a false PTT-stuck alarm, and the restart was dropped behind it.
            // Field report (build 342): the radio unplugged five minutes after a confirmed release
            // still retried for ~17 s and raised the "may still be transmitting" alarm. Also ask
            // the ledger: no key command since the last confirmed release means it can't be keyed.
            val (flagsSayKeyed, ledgerSaysKeyed) = synchronized(pttStateLock) {
                (rigPttAsserted || rigPttMaybeKeyed) to pttLedger.keyedSinceRelease()
            }
            val couldBeKeyed = flagsSayKeyed && ledgerSaysKeyed
            if (shouldReleasePtt) {
                Log.i(TAG, "Shutdown PTT state: flags ${if (flagsSayKeyed) "keyed" else "clear"}, " +
                    "commands ${if (ledgerSaysKeyed) "keyed" else "clear"} ($pttLedger)")
            }
            if (shutdownMode == "btech_uvpro") uvproWorkersActive.incrementAndGet()

            createRigTransports()
            rigCtlClient = null
            rigCtlConnected = false
            rigCtlErrorShown = false
            hamlibRigConnected = false
            rtsPttConnected = false
            trusdxConnected = false
            qmxConnected = false
            uvproConnected = false
            trusdxWatchdogToken++
            trusdxTxIntentActive = false
            stopTruSdxRxWorker()
            clearTruSdxRxFrameQueue()
            trusdxRxKeepaliveToken++
            trusdxRxKeepaliveCount = 0L
            rtsPttTransport = null
            rigControlMode = "none"
            if (isTruSdxDiagnosticsEnabled() &&
                (trusdxRxFrames > 0 || trusdxTxFrames > 0 || trusdxParserResyncs > 0 || trusdxTxDrops > 0 || trusdxRxUnderruns > 0 || trusdxRxFrameDrops > 0)
            ) {
                Log.i(
                    TAG,
                    "TruSDX diagnostics: rxFrames=$trusdxRxFrames rxSamples=$trusdxRxSamples rxFrameDrops=$trusdxRxFrameDrops rxSubmitDrops=$trusdxRxSubmitDrops rxUnderruns=$trusdxRxUnderruns txFrames=$trusdxTxFrames txSamples=$trusdxTxSamples txSilent=$trusdxTxSilentFrames txDrops=$trusdxTxDrops parserResyncs=$trusdxParserResyncs"
                )
            }

            if (shutdownMode != "none") rigTeardownPending = true
            txHandler.post {
                try {
                    if (shouldReleasePtt) {
                        // Captured references, not setRigPtt: the fields already
                        // hold the transports the next start will use.
                        // A few tries, backing off, before the transport closes for good: once it is
                        // closed nothing can unkey the radio (second audit, N4). About 7 s at most.
                        fun releaseOnce(): Boolean = try {
                            when (shutdownMode) {
                                "network" -> shutdownNetwork?.setPtt(false) == true
                                "hamlib_usb" -> shutdownHamlib?.setPtt(false) == true
                                "rts_ptt" -> when (shutdownTransport) {
                                    SerialTransport.USB -> shutdownUsb?.setRts(false) == true
                                    SerialTransport.BLUETOOTH -> shutdownBluetooth?.setRts(false) == true
                                    else -> false
                                }
                                "trusdx_serial" -> shutdownTruSdx?.setPtt(false) == true
                                "qmx_serial" -> shutdownQmx?.setPtt(false) == true
                                "btech_uvpro" -> shutdownUvPro?.setPtt(false) == true
                                "rtl_sdr", "airspy_hf" -> true // no transmitter
                                else -> false
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "PTT release failed during shutdown", t)
                            false
                        }
                        // Not keyed by us and its USB device already gone: a release can't arrive, and
                        // Hamlib would spend ~8 s timing out on it (build 360), holding up the restart.
                        val linkGone = !couldBeKeyed && shutdownMode == "hamlib_usb" &&
                            shutdownUsb?.isDeviceAttached() == false
                        if (linkGone) Log.i(TAG, "Radio USB device gone and the radio was not keyed; skipping the PTT release")
                        var released = if (linkGone) false else releaseOnce()
                        for (waitMs in longArrayOf(250, 500, 1_000, 2_000, 3_000)) {
                            if (released || !couldBeKeyed) break
                            Log.w(TAG, "PTT release failed during shutdown; trying again in $waitMs ms")
                            Thread.sleep(waitMs)
                            released = releaseOnce()
                        }
                        if (released) {
                            pttLedger.releaseConfirmed()
                            synchronized(pttStateLock) {
                                rigPttAsserted = false
                                rigPttMaybeKeyed = false
                                rigPttReleaseFailures = 0
                            }
                            mainHandler.post { clearPttStuckWarning() }
                        } else if (!couldBeKeyed) {
                            Log.i(TAG, "PTT release not confirmed during shutdown; the radio was not keyed")
                        } else {
                            Log.e(TAG, "Unable to confirm PTT release during shutdown")
                            // The receiver is stopping, so nothing will try again: tell the operator.
                            mainHandler.post { showPttStuckWarning(PTT_RELEASE_WARN_AFTER, stillTrying = false) }
                        }
                    }
                    rigTeardownStep("Hamlib close") { shutdownHamlib?.shutdown() }
                    rigTeardownStep("TruSDX stop") { shutdownTruSdx?.stop() }
                    rigTeardownStep("QMX stop") { shutdownQmx?.stop() }
                    rigTeardownStep("UV-Pro stop") { shutdownUvPro?.stop() }
                    rigTeardownStep("SDR stop") { shutdownSdr?.stop() }
                    // Not before the release: hamlib sends it through these
                    rigTeardownStep("USB serial unregister") { shutdownUsb?.unregisterNative() }
                    rigTeardownStep("Bluetooth serial unregister") { shutdownBluetooth?.unregisterNative() }
                    rigTeardownStep("USB serial close") { shutdownUsb?.shutdown() }
                    rigTeardownStep("Bluetooth serial close") { shutdownBluetooth?.shutdown() }
                    rigTeardownStep("rigctld disconnect") { shutdownNetwork?.disconnect() }
                    Log.i(TAG, "Rig control torn down")
                } finally {
                    if (shutdownMode == "btech_uvpro") uvproWorkersActive.decrementAndGet()
                    if (shutdownSdr != null) sdrWorkersActive.decrementAndGet()
                    if (shutdownMode != "none") rigTeardownPending = false
                }
            }

            pskReporterClient?.stop(flush = true)
            pskReporterEnabled = false

            // Stopping the audio streams can block for seconds (seen: AAudio waiting on a USB
            // audio callback thread for >10 s). Off the main thread, so the app never freezes.
            engine?.let { dying ->
                engine = null
                engineStoppingSinceMs = System.currentTimeMillis()
                Thread({
                    try {
                        dying.stop()
                        dying.close()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Engine stop failed", t)
                    } finally {
                        engineStoppingSinceMs = 0L
                        Log.i(TAG, "Engine audio released")
                    }
                }, "js8-engine-stop").start()
            }

            broadcastEngineState(STATE_STOPPED)
            Log.i(TAG, "Engine stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping engine", e)
        }
    }

    private inline fun rigTeardownStep(name: String, step: () -> Unit) {
        try {
            step()
        } catch (t: Throwable) {
            Log.e(TAG, "$name failed during shutdown", t)
        }
    }

    /**
     * Keeps the CPU awake while the receiver runs, so decoding (and the Traffic
     * history, Digest and ATAK output fed by it) carries on with the screen off.
     * Held only while the engine is running or starting.
     */
    private var cpuLock: android.os.PowerManager.WakeLock? = null

    /** Since when TX audio has been held waiting for PTT (0 = not waiting). */
    private var pttHoldSinceMs = 0L

    /** When a stopping engine began releasing its audio (0 = none in progress). */
    @Volatile private var engineStoppingSinceMs = 0L

    // Held for as long as the receiver runs (no timeout on purpose) and released by holdCpu(false).
    @SuppressLint("Wakelock", "WakelockTimeout")
    private fun holdCpu(on: Boolean) {
        try {
            if (on) {
                val lock = cpuLock ?: (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "JS8Tactical:receiver")
                    .apply { setReferenceCounted(false) }
                    .also { cpuLock = it }
                if (!lock.isHeld) lock.acquire()
            } else {
                cpuLock?.takeIf { it.isHeld }?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock ${if (on) "acquire" else "release"} failed", e)
        }
    }

    private fun broadcastEngineState(state: String) {
        holdCpu(state == STATE_RUNNING || state == STATE_STARTING)
        engineRunning = state == STATE_RUNNING
        val intent = Intent(ACTION_ENGINE_STATE).apply {
            putExtra(EXTRA_STATE, state)
        }
        AppBroadcasts.send(this, intent)

        // The connected flags are assigned in too many places to broadcast from
        // each one, so poll while the engine runs. Every poll sends, not only
        // changes: local broadcasts are not sticky, and a UI that attaches
        // late or missed one while stopped has no other way to catch up
        if (state == STATE_RUNNING) {
            startRigStatusPolling()
        } else {
            stopRigStatusPolling()
        }
    }

    private val rigStatusHandler = Handler(Looper.getMainLooper())
    private val rigStatusRunnable = object : Runnable {
        override fun run() {
            val connected = isRigControlConnected()
            broadcastRigStatus(connected)
            if (connected && txHeldForRig) {
                txHeldForRig = false
                broadcastProcessTxQueue()
            }
            rigStatusHandler.postDelayed(this, RIG_STATUS_POLL_INTERVAL_MS)
        }
    }
    @Volatile private var txHeldForRig = false

    private fun startRigStatusPolling() {
        rigStatusHandler.removeCallbacks(rigStatusRunnable)
        rigStatusHandler.post(rigStatusRunnable)
    }

    private fun stopRigStatusPolling() {
        rigStatusHandler.removeCallbacks(rigStatusRunnable)
        broadcastRigStatus(false)
    }

    private fun broadcastRigStatus(connected: Boolean) {
        val intent = Intent(ACTION_RIG_STATUS).apply {
            putExtra(EXTRA_RIG_CONNECTED, connected)
        }
        AppBroadcasts.send(this, intent)
    }

    // Shared with injected decodes
    private fun handleDecode(
        utc: Int, snr: Int, dt: Float, freq: Float,
        text: String, type: Int, quality: Float, mode: Int, driftMs: Int
    ) {
        updateHeardCallsign(text)
        broadcastDecode(utc, snr, dt, freq, text, type, quality, mode, driftMs)
        handleRelayFrame(text, snr, mode, freq, type)
        maybeHandleIncomingMessage(text, snr, freq, type, mode)
        trackStoredMessageOffers(text, freq, type, mode)
        maybeHandleAutoReply(text, snr, mode)
    }

    private fun injectDecode(intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            Log.w(TAG, "Injected decode has no text")
            return
        }
        val snr = intent.getIntExtra(EXTRA_SNR, -10)
        val freq = intent.getFloatExtra(EXTRA_FREQ, 1500f)
        val type = intent.getIntExtra(EXTRA_TYPE, 3)
        val mode = intent.getIntExtra(EXTRA_MODE, 0)
        val utc = utcHhmmss(System.currentTimeMillis())
        Log.i(TAG, "Injected decode: '$text' snr=$snr freq=$freq type=$type mode=$mode")
        handleDecode(utc, snr, 0f, freq, text, type, 1f, mode, 0)
    }

    private fun utcHhmmss(millis: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
        return cal.get(Calendar.HOUR_OF_DAY) * 10000 + cal.get(Calendar.MINUTE) * 100 + cal.get(Calendar.SECOND)
    }

    private fun broadcastDecode(
        utc: Int, snr: Int, dt: Float, freq: Float,
        text: String, type: Int, quality: Float, mode: Int, driftMs: Int
    ) {
        val intent = Intent(ACTION_DECODE).apply {
            putExtra(EXTRA_UTC, utc)
            putExtra(EXTRA_SNR, snr)
            putExtra(EXTRA_DT, dt)
            putExtra(EXTRA_FREQ, freq)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_QUALITY, quality)
            putExtra(EXTRA_MODE, mode)
            putExtra(EXTRA_DRIFT_MS, driftMs)
        }
        AppBroadcasts.send(this, intent)
    }

    private var timeSyncOncePending = false
    /** Automatic time sync: each transmitter heard counts once (see [DriftEstimator]). */
    private val driftEstimator = DriftEstimator()
    private var lastAppliedDriftMs: Long? = null

    private fun maybeApplyTimeSync(suggestedDriftMs: Int, freqHz: Float) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val autoSync = prefs.getBoolean(PREF_TIME_SYNC_AUTO, false)
        if (!autoSync && !timeSyncOncePending) return
        val now = System.currentTimeMillis()

        if (timeSyncOncePending) {
            // "Sync once": the operator asked to take this decode's timing as it is.
            timeSyncOncePending = false
            driftEstimator.clear()
            driftEstimator.record(freqHz, suggestedDriftMs.toLong(), now)
            lastAppliedDriftMs = suggestedDriftMs.toLong()
            applyTimeDrift(suggestedDriftMs.toLong())
            return
        }

        driftEstimator.record(freqHz, suggestedDriftMs.toLong(), now)
        val target = driftEstimator.estimate(now, DriftEstimator.MIN_LANES) ?: return
        val drift = DriftEstimator.step(lastAppliedDriftMs ?: (engine?.timeDriftMs() ?: 0L), target)
        // Re-apply (prefs write, heartbeat reschedule) only when the correction actually moves.
        if (lastAppliedDriftMs?.let { kotlin.math.abs(it - drift) < 5 } == true) return
        lastAppliedDriftMs = drift
        applyTimeDrift(drift)
    }

    private fun applyTimeDrift(driftMs: Long) {
        engine?.setTimeDriftMs(driftMs)
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putLong(PREF_TIME_DRIFT_MS, driftMs)
            .apply()
        Log.i(TAG, "Time drift applied: $driftMs ms")
        broadcastTimeDrift(driftMs)
        // TX slots are computed against the drifted clock now; reschedule.
        scheduleHeartbeat(false)
    }

    private fun applyTimeDriftSetting() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val driftMs = prefs.getLong(PREF_TIME_DRIFT_MS, 0L)
        if (driftMs != 0L) {
            engine?.setTimeDriftMs(driftMs)
            Log.i(TAG, "Time drift restored: $driftMs ms")
        }
        broadcastTimeDrift(driftMs)
    }

    private fun broadcastTimeDrift(driftMs: Long) {
        val intent = Intent(ACTION_TIME_DRIFT).apply {
            putExtra(EXTRA_TIME_DRIFT_MS, driftMs)
        }
        AppBroadcasts.send(this, intent)
    }

    private fun broadcastSpectrum(
        bins: FloatArray, binHz: Float,
        powerDb: Float, peakDb: Float
    ) {
        val intent = Intent(ACTION_SPECTRUM).apply {
            putExtra(EXTRA_BINS, bins)
            putExtra(EXTRA_BIN_HZ, binHz)
            putExtra(EXTRA_POWER_DB, powerDb)
            putExtra(EXTRA_PEAK_DB, peakDb)
        }
        AppBroadcasts.send(this, intent)
    }

    private fun broadcastDecodeStarted(submodes: Int) {
        val intent = Intent(ACTION_DECODE_STARTED).apply {
            putExtra(EXTRA_SUBMODES, submodes)
        }
        AppBroadcasts.send(this, intent)
    }

    private fun broadcastDecodeFinished(count: Int) {
        val intent = Intent(ACTION_DECODE_FINISHED).apply {
            putExtra(EXTRA_COUNT, count)
        }
        AppBroadcasts.send(this, intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        AppBroadcasts.send(this, intent)
    }

    private fun broadcastAudioDevice(deviceName: String) {
        val intent = Intent(ACTION_AUDIO_DEVICE).apply {
            putExtra(EXTRA_AUDIO_DEVICE, deviceName)
        }
        AppBroadcasts.send(this, intent)
    }

    private fun broadcastAudioDeviceLost(deviceName: String) {
        val intent = Intent(ACTION_AUDIO_DEVICE_LOST).apply {
            putExtra(EXTRA_AUDIO_DEVICE, deviceName)
        }
        AppBroadcasts.send(this, intent)
    }

    private fun broadcastRadioFrequency(frequencyHz: Long) {
        val intent = Intent(ACTION_RADIO_FREQUENCY).apply {
            putExtra(EXTRA_RADIO_FREQUENCY_HZ, frequencyHz)
        }
        AppBroadcasts.send(this, intent)
    }

    private fun getActiveAudioDevice(): String {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // For Android M (API 23) and above, use AudioDeviceInfo
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        // If a specific device is selected, find it
        if (selectedAudioDeviceId != -1) {
            for (device in devices) {
                if (device.id == selectedAudioDeviceId) {
                    val deviceType = getDeviceName(device)
                    Log.i(TAG, "Using selected audio device: $deviceType (ID: ${device.id})")
                    return deviceType
                }
            }
        }

        // Find the first active input device
        for (device in devices) {
            val deviceType = getDeviceName(device)
            // Return first valid device (Oboe typically uses default)
            Log.i(TAG, "Detected audio device: $deviceType")
            return deviceType
        }

        // Fallback for older Android versions or if no device found
        return "Microphone"
    }

    private fun updateOutputDeviceForInput(inputDeviceId: Int) {
        val outputId = findOutputDeviceId(inputDeviceId)
        selectedOutputDeviceId = outputId
        engine?.setOutputDevice(outputId)
        if (outputId > 0) {
            Log.i(TAG, "Using output device: ${getOutputDeviceName(outputId)} (ID: $outputId)")
        } else {
            Log.i(TAG, "Using default output device")
        }
    }

    private fun findOutputDeviceId(inputDeviceId: Int): Int {
        if (inputDeviceId == -1) return -1

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val inputDevice = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == inputDeviceId } ?: return -1

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputName = inputDevice.productName?.toString()?.takeIf { it.isNotBlank() }
        val inputFamily = deviceFamily(inputDevice.type)

        var output = outputs.firstOrNull { device ->
            device.type == inputDevice.type &&
                (inputName == null || device.productName?.toString() == inputName)
        }

        if (output == null && inputName != null) {
            output = outputs.firstOrNull { device ->
                device.productName?.toString() == inputName &&
                    (inputFamily.isEmpty() || deviceFamily(device.type) == inputFamily)
            }
        }

        if (output == null && inputName != null) {
            output = outputs.firstOrNull { device -> device.productName?.toString() == inputName }
        }

        if (output == null && inputFamily.isNotEmpty()) {
            output = outputs.firstOrNull { device -> deviceFamily(device.type) == inputFamily }
        }

        return output?.id ?: -1
    }

    private fun deviceFamily(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin"
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line"
            else -> ""
        }
    }

    private fun getOutputDeviceName(deviceId: Int): String {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val device = outputs.firstOrNull { it.id == deviceId } ?: return "Default Output"
        return getDeviceName(device)
    }

    private fun getDeviceName(device: AudioDeviceInfo): String {
        return AudioDevices.nameFor(device) ?: "Unknown Device"
    }

    private fun switchAudioDevice(deviceId: Int) {
        try {
            if (engine != null && deviceId == selectedAudioDeviceId) {
                Log.i(TAG, "Audio device already selected (ID: $deviceId); ignoring switch request")
                return
            }
            // Store the selected device ID
            selectedAudioDeviceId = deviceId

            if (rigControlMode == "trusdx_serial") {
                val speakerEnabled = deviceId == TRUSDX_AUDIO_SPEAKER_ID
                val ok = trusdxSerialSession?.setSpeakerEnabled(speakerEnabled) == true
                if (!ok) {
                    broadcastError("Failed to update TruSDX speaker mode")
                }
                val label = if (speakerEnabled) TRUSDX_AUDIO_SPEAKER_NAME else TRUSDX_AUDIO_SERIAL_NAME
                broadcastAudioDevice(label)
                return
            }
            if (rigControlMode == "btech_uvpro") {
                broadcastAudioDevice(UVPRO_AUDIO_NAME)
                return
            }
            if (isSdrMode()) {
                broadcastAudioDevice(sdrSession?.kind?.label ?: "SDR")
                return
            }

            scoRestartAttempts = 0
            scoSourceIndex = 0
            scoSilenceCheckToken++
            scoStartToken++

            if (engine != null) {
                audioHelper?.stopCapture()
                audioHelper?.close()
                audioHelper = null
                if (isScoInputDevice(deviceId)) {
                    startAudioCaptureWithScoWait(engine!!, deviceId)
                } else {
                    startAudioCapture(engine!!, deviceId)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error switching audio device", e)
            broadcastError("Error switching audio device: ${e.message}")
        }
    }

    private fun scheduleScoSilenceCheck() {
        if (!isScoInputDevice(selectedAudioDeviceId)) return
        val token = ++scoSilenceCheckToken
        scheduleScoSilenceCheckInternal(token)
    }

    private fun scheduleScoSilenceCheckInternal(token: Int) {
        mainHandler.postDelayed({
            if (token != scoSilenceCheckToken) return@postDelayed
            val helper = audioHelper ?: return@postDelayed
            val maxAbs = helper.getLastAbsMax()
            if (maxAbs < SCO_SILENCE_THRESHOLD && scoRestartAttempts < SCO_MAX_RESTARTS) {
                scoRestartAttempts++
                scoSourceIndex = (scoSourceIndex + 1) % scoSourceCandidates.size
                Log.w(TAG, "SCO input appears silent (max=$maxAbs); restarting capture (attempt $scoRestartAttempts/$SCO_MAX_RESTARTS)")
                restartAudioCaptureForSco()
                return@postDelayed
            }
            scheduleScoSilenceCheckInternal(token)
        }, SCO_SILENCE_CHECK_DELAY_MS)
    }

    private fun restartAudioCaptureForSco() {
        val deviceId = selectedAudioDeviceId
        val activeEngine = engine ?: return
        scoStartToken++
        audioHelper?.stopCapture()
        audioHelper?.close()
        audioHelper = null
        startAudioCaptureWithScoWait(activeEngine, deviceId)
    }

    private fun startAudioCapture(activeEngine: JS8Engine, deviceId: Int) {
        applyInputRouting(deviceId)
        startAudioCaptureInternal(activeEngine, deviceId)
    }

    private fun startAudioCaptureWithScoWait(activeEngine: JS8Engine, deviceId: Int) {
        val token = scoStartToken
        applyInputRouting(deviceId)
        waitForScoActive(activeEngine, deviceId, token, 0)
    }

    private fun waitForScoActive(
        activeEngine: JS8Engine,
        deviceId: Int,
        token: Int,
        attempt: Int
    ) {
        if (token != scoStartToken) return
        val scoActive = isScoActive(deviceId)
        if (scoActive || attempt >= SCO_START_MAX_ATTEMPTS) {
            if (scoActive) {
                Log.i(TAG, "SCO audio active; starting capture")
            } else {
                Log.w(TAG, "SCO audio not active after $attempt checks; starting capture anyway")
            }
            startAudioCaptureInternal(activeEngine, deviceId)
            return
        }

        mainHandler.postDelayed({
            waitForScoActive(activeEngine, deviceId, token, attempt + 1)
        }, SCO_START_WAIT_INTERVAL_MS)
    }

    /**
     * Field case Nov 19: the receiver was started before the radio's USB audio was plugged in,
     * fell back to the tablet's own microphone and speaker, and the next send keyed the radio
     * with no audio. A prompt (not a block): say so plainly when a radio is set up.
     */
    private fun warnIfTabletMicrophone(deviceId: Int) {
        val radioSetUp = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("rig_control_enabled", false)
        if (!radioSetUp) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val input = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId }
            ?: am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull()
        if (input?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            Log.w(TAG, "Receiver started on the tablet's built-in microphone while a radio is configured")
            // A warning, not an error: the receiver is running (an error would show it as stopped).
            AppBroadcasts.send(
            this,
                Intent(ACTION_WARNING).putExtra(
                    EXTRA_ERROR_MESSAGE,
                    "Listening through the tablet's own microphone, not the radio. Plug in the radio's USB audio, " +
                        "then turn the Receiver off and on. Anything sent now would key the radio with no audio.",
                )
            )
        }
    }

    private fun startAudioCaptureInternal(activeEngine: JS8Engine, deviceId: Int) {
        audioHelper = buildAudioHelper(activeEngine, deviceId)
        if (audioHelper?.startCapture() == true) {
            Log.i(TAG, "Audio capture started with device ID: $deviceId")
            val deviceName = getActiveAudioDevice()
            broadcastAudioDevice(deviceName)
            updateOutputDeviceForInput(deviceId)
            broadcastEngineState(STATE_RUNNING)
            broadcastProcessTxQueue()
            scheduleScoSilenceCheck()
            warnIfTabletMicrophone(deviceId)
        } else {
            Log.e(TAG, "Failed to start audio capture")
            broadcastError("Failed to start audio capture")
        }
    }

    private fun handleTruSdxAudioFrame(samplesU8: ByteArray) {
        if (rigControlMode != "trusdx_serial") return
        if (samplesU8.isEmpty()) return

        trusdxRxFrames += 1
        trusdxRxSamples += samplesU8.size.toLong()
        val now = System.nanoTime()
        trusdxLastRxAudioNs = now
        trusdxRxRateWindowSamples += samplesU8.size.toLong()
        enqueueTruSdxRxFrame(samplesU8)

        if (trusdxRxFrames <= 10) {
            val queued = trusdxRxFrameQueue.size
            Log.i(TAG, "TruSDX RX frame #$trusdxRxFrames size=${samplesU8.size} queued=$queued")
        }
        if (trusdxRxRateWindowStartNs == 0L) {
            trusdxRxRateWindowStartNs = now
        } else {
            val windowNs = now - trusdxRxRateWindowStartNs
            if (windowNs >= 1_000_000_000L) {
                val observedRate = ((trusdxRxRateWindowSamples * 1_000_000_000L) / windowNs).toInt()
                if (isTruSdxDiagnosticsEnabled()) {
                    Log.i(TAG, "TruSDX RX stream: frames=$trusdxRxFrames samples=$trusdxRxSamples chunk=${samplesU8.size} rate=${observedRate}B/s")
                }
                trusdxRxRateWindowStartNs = now
                trusdxRxRateWindowSamples = 0L
            }
        }
    }

    private fun scheduleTruSdxRxKeepAlive() {
        val token = ++trusdxRxKeepaliveToken
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (token != trusdxRxKeepaliveToken) return
                if (rigControlMode != "trusdx_serial") return
                if (!trusdxConnected) {
                    mainHandler.postDelayed(this, TRUSDX_RX_KEEPALIVE_INTERVAL_MS)
                    return
                }
                if (!trusdxTxIntentActive && !isTransmitActive()) {
                    val ok = trusdxSerialSession?.sendRxKeepAlive() == true
                    trusdxRxKeepaliveCount += 1
                    if (!ok) {
                        Log.w(TAG, "TruSDX frequency poll failed")
                    } else if (isTruSdxDiagnosticsEnabled() && trusdxRxKeepaliveCount % 10L == 0L) {
                        Log.i(TAG, "TruSDX frequency poll count=$trusdxRxKeepaliveCount")
                    }
                }
                mainHandler.postDelayed(this, TRUSDX_RX_KEEPALIVE_INTERVAL_MS)
            }
        }, TRUSDX_RX_KEEPALIVE_INTERVAL_MS)
    }

    private fun enqueueTruSdxRxFrame(frame: ByteArray) {
        if (!trusdxRxFrameQueue.offerLast(frame)) {
            trusdxRxFrameQueue.pollFirst()
            trusdxRxFrameDrops += 1
            trusdxRxFrameQueue.offerLast(frame)
        }
    }

    private fun clearTruSdxRxFrameQueue() {
        trusdxRxFrameQueue.clear()
    }

    private fun startTruSdxRxWorker() {
        if (trusdxRxWorkerRunning) return
        trusdxRxWorkerRunning = true
        trusdxRxWorkerThread = Thread({
            while (trusdxRxWorkerRunning) {
                val frame = try {
                    trusdxRxFrameQueue.pollFirst(100L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue

                val activeEngine = engine ?: continue
                val pcm = ShortArray(frame.size)
                var i = 0
                while (i < frame.size) {
                    // TruSDX RX audio arrives as unsigned 8-bit PCM on the serial stream.
                    val sampleU8 = frame[i].toInt() and 0xFF
                    pcm[i] = ((sampleU8 - 128) shl 8).toShort()
                    i++
                }

                val ok = activeEngine.submitAudioRaw(
                    samples = pcm,
                    numSamples = pcm.size,
                    inputSampleRateHz = TRUSDX_RX_SAMPLE_RATE_HZ,
                    timestampNs = System.nanoTime()
                )
                if (!ok) {
                    trusdxRxSubmitDrops += 1
                    if (trusdxRxSubmitDrops <= 10L) {
                        Log.w(TAG, "TruSDX submit drop #$trusdxRxSubmitDrops chunk=${pcm.size}")
                    }
                    if (isTruSdxDiagnosticsEnabled()) {
                        Log.w(TAG, "TruSDX audio submit failed for chunk size=${pcm.size}")
                    }
                }
            }
        }, "TruSdxRxWorker")
        trusdxRxWorkerThread?.isDaemon = true
        trusdxRxWorkerThread?.start()
    }

    private fun stopTruSdxRxWorker() {
        trusdxRxWorkerRunning = false
        val thread = trusdxRxWorkerThread
        trusdxRxWorkerThread = null
        thread?.interrupt()
        if (thread != null && thread.isAlive) {
            try {
                thread.join(300)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun scheduleTruSdxRxWatchdog() {
        val token = ++trusdxWatchdogToken
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (token != trusdxWatchdogToken) return
                if (rigControlMode != "trusdx_serial") return
                if (engine == null) return

                val now = System.nanoTime()
                val last = trusdxLastRxAudioNs
                val stalled = last == 0L || (now - last) > TRUSDX_RX_STALL_REARM_NS
                val rearmDue = trusdxLastRxRearmNs == 0L || (now - trusdxLastRxRearmNs) > TRUSDX_RX_REARM_COOLDOWN_NS
                if (stalled && trusdxConnected && rearmDue) {
                    val ok = trusdxSerialSession?.ensureRxStreaming() == true
                    trusdxLastRxRearmNs = now
                    Log.i(TAG, "TruSDX RX watchdog rearm: ok=$ok lastRxNs=$last")
                }

                mainHandler.postDelayed(this, TRUSDX_RX_WATCHDOG_INTERVAL_MS)
            }
        }, TRUSDX_RX_WATCHDOG_INTERVAL_MS)
    }

    private fun handleTruSdxTxAudio(samplesPcm16: ShortArray, sampleRateHz: Int) {
        if (rigControlMode != "trusdx_serial") return
        if (samplesPcm16.isEmpty()) return
        val serialTxActive = trusdxSerialSession?.isTxActive() == true
        var sumSquares = 0.0
        for (s in samplesPcm16) {
            val v = s.toDouble()
            sumSquares += v * v
        }
        val inRms = kotlin.math.sqrt(sumSquares / samplesPcm16.size)
        val txSamples = if (sampleRateHz == TRUSDX_TX_SAMPLE_RATE_HZ) {
            samplesPcm16
        } else {
            resamplePcmLinear(samplesPcm16, sampleRateHz, TRUSDX_TX_SAMPLE_RATE_HZ)
        }
        if (txSamples.isEmpty()) return

        val ok = trusdxSerialSession?.sendTxAudio(txSamples) == true
        if (!ok) {
            trusdxTxDrops += 1
            return
        }

        if (!serialTxActive) {
            return
        }

        trusdxTxFrames += 1
        trusdxTxSamples += txSamples.size.toLong()
        if (inRms < 1.0) {
            trusdxTxSilentFrames += 1
        }
        if (isTruSdxDiagnosticsEnabled() && (trusdxTxFrames % 200L == 0L)) {
            Log.i(TAG, "TruSDX TX frame #$trusdxTxFrames inRate=$sampleRateHz outSamples=${txSamples.size} inRms=${"%.1f".format(Locale.US, inRms)} silentFrames=$trusdxTxSilentFrames drops=$trusdxTxDrops")
        }
    }

    private fun handleUvProTxAudio(samplesPcm16: ShortArray, sampleRateHz: Int) {
        if (rigControlMode != "btech_uvpro" || samplesPcm16.isEmpty()) return
        val txSamples = if (sampleRateHz == UvProSession.SAMPLE_RATE_HZ) {
            samplesPcm16
        } else {
            resamplePcmLinear(samplesPcm16, sampleRateHz, UvProSession.SAMPLE_RATE_HZ)
        }
        uvProSession?.sendTxAudio(txSamples)
    }

    private fun resamplePcmLinear(samples: ShortArray, inputRateHz: Int, outputRateHz: Int): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        if (inputRateHz <= 0 || outputRateHz <= 0) return samples
        if (inputRateHz == outputRateHz) return samples

        val ratio = outputRateHz.toDouble() / inputRateHz.toDouble()
        val outSize = kotlin.math.max(1, kotlin.math.round(samples.size * ratio).toInt())
        val out = ShortArray(outSize)
        val step = inputRateHz.toDouble() / outputRateHz.toDouble()
        var pos = 0.0
        var i = 0
        while (i < outSize) {
            val idx = pos.toInt().coerceIn(0, samples.size - 1)
            val next = (idx + 1).coerceAtMost(samples.size - 1)
            val frac = pos - idx.toDouble()
            val a = samples[idx].toInt()
            val b = samples[next].toInt()
            val value = (a + ((b - a) * frac)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = value.toShort()
            pos += step
            i++
        }
        return out
    }

    private fun broadcastProcessTxQueue() {
        val intent = Intent(MainActivity.ACTION_PROCESS_TX_QUEUE)
        AppBroadcasts.send(this, intent)
    }

    // The startBluetoothSco/isBluetoothScoOn calls are deprecated from Android 12, where setCommunicationDevice
    // is tried first; they remain the only way on Android 8–11 and the fallback when that fails.
    @Suppress("DEPRECATION")
    private fun isScoActive(deviceId: Int): Boolean {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevice = audioManager.communicationDevice
            if (commDevice != null && commDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                if (deviceId < 0 || commDevice.id == deviceId) {
                    return true
                }
            }
        }
        return audioManager.isBluetoothScoOn
    }

    private fun isScoInputDevice(deviceId: Int): Boolean {
        return findInputDevice(deviceId)?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun buildAudioHelper(engine: JS8Engine, deviceId: Int): JS8AudioHelper {
        val overrideSource = if (isScoInputDevice(deviceId)) {
            scoSourceCandidates[scoSourceIndex]
        } else {
            -1
        }
        return JS8AudioHelper(engine, 12000, deviceId, applicationContext, overrideSource)
    }

    private fun applyInputRouting(inputDeviceId: Int) {
        val inputDevice = findInputDevice(inputDeviceId)
        if (inputDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            enableScoRouting(inputDevice)
        } else {
            disableScoRouting()
        }
    }

    private fun findInputDevice(deviceId: Int): AudioDeviceInfo? {
        if (deviceId < 0) return null
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == deviceId }
    }

    // The startBluetoothSco/isBluetoothScoOn calls are deprecated from Android 12, where setCommunicationDevice
    // is tried first; they remain the only way on Android 8–11 and the fallback when that fails.
    @Suppress("DEPRECATION")
    private fun enableScoRouting(device: AudioDeviceInfo) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val priorMode = audioManager.mode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT permission not granted; SCO routing unavailable")
                broadcastError("Bluetooth permission required for SCO routing")
                return
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!scoRoutingActive) {
                previousAudioMode = priorMode
            }
            val commDevice = findCommunicationDevice(device)
            if (commDevice == null) {
                Log.w(TAG, "No available communication device matches SCO input; falling back to SCO start")
                if (!startBluetoothScoLegacy(audioManager, priorMode)) {
                    broadcastError("Failed to start Bluetooth SCO routing")
                }
                return
            }
            val ok = audioManager.setCommunicationDevice(commDevice)
            if (ok) {
                scoRoutingActive = true
                Log.i(TAG, "SCO routing enabled via communication device: ${commDevice.productName} (ID: ${commDevice.id})")
                if (!audioManager.isBluetoothScoOn) {
                    if (startBluetoothScoLegacy(audioManager, priorMode)) {
                        Log.i(TAG, "SCO audio started after communication device routing")
                    } else {
                        Log.w(TAG, "Failed to start SCO audio after communication device routing")
                    }
                }
            } else {
                audioManager.mode = priorMode
                Log.w(TAG, "Failed to set communication device for SCO; falling back to SCO start")
                if (!startBluetoothScoLegacy(audioManager, priorMode)) {
                    broadcastError("Failed to start Bluetooth SCO routing")
                }
            }
        } else {
            if (!scoRoutingActive) {
                previousAudioMode = priorMode
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            try {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                scoRoutingActive = true
                Log.i(TAG, "SCO routing enabled via startBluetoothSco")
            } catch (e: SecurityException) {
                audioManager.mode = priorMode
                Log.w(TAG, "Failed to start Bluetooth SCO", e)
                broadcastError("Bluetooth permission required for SCO routing")
            }
        }
    }

    // The startBluetoothSco/isBluetoothScoOn calls are deprecated from Android 12, where setCommunicationDevice
    // is tried first; they remain the only way on Android 8–11 and the fallback when that fails.
    @Suppress("DEPRECATION")
    private fun startBluetoothScoLegacy(audioManager: AudioManager, priorMode: Int): Boolean {
        return try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            scoRoutingActive = true
            Log.i(TAG, "SCO routing enabled via legacy startBluetoothSco")
            true
        } catch (e: SecurityException) {
            audioManager.mode = priorMode
            Log.w(TAG, "Failed to start Bluetooth SCO (legacy)", e)
            false
        }
    }

    private fun findCommunicationDevice(inputDevice: AudioDeviceInfo): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val candidates = audioManager.availableCommunicationDevices
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.id == inputDevice.id }?.let { return it }
        val inputName = inputDevice.productName?.toString()
        candidates.firstOrNull {
            it.type == inputDevice.type &&
                (inputName == null || it.productName?.toString() == inputName)
        }?.let { return it }
        return candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    // The startBluetoothSco/isBluetoothScoOn calls are deprecated from Android 12, where setCommunicationDevice
    // is tried first; they remain the only way on Android 8–11 and the fallback when that fails.
    @Suppress("DEPRECATION")
    private fun disableScoRouting() {
        if (!scoRoutingActive) return
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to clear communication device", e)
            }
            if (audioManager.isBluetoothScoOn) {
                try {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                } catch (e: SecurityException) {
                    Log.w(TAG, "Failed to stop Bluetooth SCO", e)
                }
            }
        } else {
            try {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to stop Bluetooth SCO", e)
            }
        }
        audioManager.mode = previousAudioMode
        scoRoutingActive = false
        Log.i(TAG, "SCO routing disabled")
    }

    private fun checkHeartbeat() {
        // This function is now just the runnable target, actual scheduling is handled by postDelayed in scheduleHeartbeat
        sendHeartbeat()
    }

    private fun scheduleHeartbeat(first: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalStr = prefs.getString(PREF_HEARTBEAT_INTERVAL, "0") ?: "0"
        val intervalMinutes = intervalStr.toIntOrNull() ?: 0

        if (intervalMinutes <= 0) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable)
            Log.i(TAG, "Heartbeat disabled")
            return
        }

        // Drifted timeline, so slots match the engine's cycle boundaries.
        val now = System.currentTimeMillis() + (engine?.timeDriftMs() ?: 0L)
        val frameDuration = framePeriodMs(getPreferredTxSubmode())

        // Base delay
        var delay = if (first) {
            // If first run, schedule for the next available slot (plus a small random wait to avoid instant blast?)
            // actually, let's just wait one interval to be polite, or at least a few frames.
            // Desktop does "first ? 0 : interval". 
            // If we use 0, we transmit immediately on app start if enabled. 
            // Let's use 10 seconds min for first run to allow system to settle.
            10000L 
        } else {
            intervalMinutes * 60 * 1000L
        }
        
        // Jitter: 25% chance to skip a frame (Desktop style)
        if (Math.random() < 0.25) {
            delay += frameDuration
        }
        
        var targetTime = now + delay
        
        // Align to next frame boundary (UTC epoch)
        // We want the trigger to happen slightly BEFORE the boundary (e.g. 2000ms)
        // so the engine has time to pick it up for the immediate slot.
        val remainder = targetTime % frameDuration
        val timeToBoundary = frameDuration - remainder
        targetTime += timeToBoundary
        
        // Schedule 2 seconds before the slot starts
        targetTime -= 2000L
        
        // Ensure strictly future
        if (targetTime <= now) {
            targetTime += frameDuration
        }
        
        nextHeartbeatTime = targetTime
        
        Log.i(TAG, "Heartbeat scheduled for ${java.util.Date(nextHeartbeatTime)} (interval=$intervalMinutes min, frame=${frameDuration/1000}s)")
        
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        val waitMs = nextHeartbeatTime - now
        heartbeatHandler.postDelayed(heartbeatRunnable, waitMs)
    }

    private fun framePeriodMs(submode: Int): Long {
        return when (submode) {
            SUBMODE_SLOW -> 30000L
            SUBMODE_NORMAL -> 15000L
            SUBMODE_FAST -> 10000L
            SUBMODE_TURBO -> 6000L
            else -> 15000L
        }
    }

    /**
     * True for messages that belong in the heartbeat sub-band: heartbeats
     * themselves and heartbeat SNR acknowledgements.
     */
    private fun isHeartbeatTraffic(text: String): Boolean {
        if (TxMessageClassifier.isHeartbeatMessage(text)) return true
        return heartbeatAckTxRegex.containsMatchIn(text)
    }

    /**
     * Random audio offset inside the heartbeat sub-band. The upper bound backs
     * off by the submode bandwidth so the whole signal stays under 1000 Hz.
     */
    private fun heartbeatOffsetHz(submode: Int): Float = TxOffsets.heartbeatFor(this, submode)

    private fun sendHeartbeat() {
        if (isSdrMode()) {
            Log.i(TAG, "Skipping heartbeat: the SDR is receive only")
            scheduleHeartbeat(false)
            return
        }
        // Don't key up over a long message that's still coming in (we'd lose its frames).
        if (runCatching { com.js8call.example.ui.traffic.TrafficViewModel.of(applicationContext).messageInProgress(System.currentTimeMillis()) }.getOrDefault(false)) {
            Log.i(TAG, "Skipping heartbeat: a message is coming in")
            scheduleHeartbeat(false)
            return
        }
        if (System.currentTimeMillis() < netHoldUntilMs) {
            Log.i(TAG, "Skipping heartbeat: held after the Net Switcher changed band")
            scheduleHeartbeat(false)
            return
        }
        if (System.currentTimeMillis() < holdTxUntilMs) {
            Log.i(TAG, "Skipping heartbeat: radio connection just came back")
            scheduleHeartbeat(false)
            return
        }
        if (isTransmitActive()) {
            Log.i(TAG, "Skipping heartbeat due to active transmission, rescheduling")
            scheduleHeartbeat(false)
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign() ?: return
        val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()

        val freq = heartbeatOffsetHz(getPreferredTxSubmode())
        
        // Message format: CALL: @HB HEARTBEAT GRID
        // Note: JS8Call desktop constructs it as: CALL: @HB HEARTBEAT GRID
        // My buildTxMessage handles adding the "CALL: " part if we pass "@HB HEARTBEAT GRID" as text and directedCall as ""?
        // Wait, handleTransmitMessage uses:
        // text = payloadText
        // myCall = callsign
        // selectedCall = directed
        
        // Let's use transmitMessage directly
        
        val message = "@HB HEARTBEAT $grid"
        // If I pass selectedCall as empty, buildTxMessage logic:
        // if directedCall is empty, return text.
        // So I need to construct the full line myself?
        // nativeTransmitMessage expects "text" (payload) and "selectedCall".
        // The engine likely frames it.
        // Let's look at desktop:
        // lines.append(QString("%1: HEARTBEAT %2").arg(mycall).arg(mygrid));
        // So "CALL: HEARTBEAT GRID"
        
        // But wait, the standard heartbeat format is often directed to @HB?
        // Desktop varicode.cpp:
        // QRegularExpression heartbeat_re(R"(^\s*(?<callsign>[@](?:ALLCALL|HB)\s+)?(?<type>CQ CQ CQ|CQ DX|CQ QRP|CQ CONTEST|CQ FIELD|CQ FD|CQ CQ|CQ|HB|HEARTBEAT(?!\s+SNR))(?:\s(?<grid>[A-R]{2}[0-9]{2}))?\b)");
        
        // Let's stick to what the JS8Call guide says or what works.
        // Usually: "CALL: @HB HEARTBEAT GRID4"
        
        val payload = "@HB HEARTBEAT ${grid.take(4)}"
        
        Log.i(TAG, "Sending Heartbeat: $payload at ${freq}Hz")

        val activeEngine = engine
        if (activeEngine != null) {
            val submode = getPreferredTxSubmode()
            val ok = transmitIfIdle(activeEngine) {
                activeEngine.transmitMessage(
                    text = payload,
                    myCall = callsign,
                    myGrid = grid,
                    selectedCall = "", // Broadcast-ish
                    submode = submode,
                    audioFrequencyHz = freq.toDouble(),
                    txDelaySec = 0.0,
                    forceIdentify = true, // Force ID to ensure callsign is sent
                    forceData = false
                )
            }

            when (ok) {
                true -> {
                    TxOffsets.heartbeatSent(this, submode)
                    updateLastTxMessage(payload, "", submode, freq.toDouble())
                    broadcastTxState(TX_STATE_QUEUED)
                    startTxMonitor(frameTextsFor(payload, callsign, grid, "", submode, forceIdentify = true, forceData = false))
                }
                false -> Log.e(TAG, "Failed to send heartbeat")
                null -> Log.i(TAG, "Skipping heartbeat due to active transmission, rescheduling")
            }
        }

        scheduleHeartbeat(false)
    }

    private fun disableHeartbeat() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val current = prefs.getString(PREF_HEARTBEAT_INTERVAL, "0")
        if (current != "0") {
            Log.i(TAG, "Disabling heartbeat due to manual transmission")
            prefs.edit().putString(PREF_HEARTBEAT_INTERVAL, "0").apply()
            // The listener will trigger and stop the handler
        }
    }

    private fun handleTransmitMessage(intent: Intent) {
        val activeEngine = engine ?: return handBack("because monitoring stopped")
        // A heartbeat or relay the service started can be waiting for its slot
        if (activeEngine.isTransmitting()) return handBack("until the current transmission ends")

        val text = intent.getStringExtra(EXTRA_TX_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) {
            broadcastError("Empty TX message")
            broadcastTxState(TX_STATE_FAILED, refused = true)
            return
        }
        if (isSdrMode()) {
            // No transmitter: the send is dropped, not held (it could never go).
            if (intent.getBooleanExtra(EXTRA_TX_AUTOMATIC, false)) {
                Log.i(TAG, "Automatic send dropped: the SDR is receive only")
            } else {
                broadcastError(getString(R.string.rtlsdr_receive_only))
            }
            broadcastTxState(TX_STATE_FAILED, refused = true)
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign()
        if (callsign == null) {
            warnMissingCallsign()
            broadcastTxState(TX_STATE_FAILED)
            return
        }
        if (System.currentTimeMillis() < holdTxUntilMs) {
            handBack("until the radio connection has been stable for a while")
            return
        }
        // The Net Switcher's hold after a band change protects an antenna that may not be tuned
        // yet. It applies to every automatic send (heartbeat ACKs, query answers, relays and
        // relay ACKs), held here in one place; the operator's own sends go when asked.
        if (intent.getBooleanExtra(EXTRA_TX_AUTOMATIC, false) && System.currentTimeMillis() < netHoldUntilMs) {
            handBack("until the Net Switcher's hold after a band change ends")
            return
        }
        if (isRigPttRequired() && !isRigControlConnected()) {
            handBack("until the rig link is up")
            // Set after the hand-back so the poller's kick lands behind it
            txHeldForRig = true
            return
        }
        val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()

        val directed = intent.getStringExtra(EXTRA_TX_DIRECTED)?.trim().orEmpty()
        val submode = if (intent.hasExtra(EXTRA_TX_SUBMODE)) {
            intent.getIntExtra(EXTRA_TX_SUBMODE, SUBMODE_NORMAL)
        } else {
            prefs.getInt(PREF_TX_SUBMODE, SUBMODE_NORMAL)
        }
        // Heartbeat traffic belongs in the 500-1000 Hz sub-band, away from QSOs.
        // This covers manual HB sends and auto HB ACKs; the scheduled heartbeat
        // picks its own offset in sendHeartbeat().
        val requestedFrequencyHz = intent.getDoubleExtra(EXTRA_TX_FREQ_HZ, currentTxOffsetHz.toDouble())
        val audioFrequencyHz = if (isHeartbeatTraffic(text)) {
            heartbeatOffsetHz(submode).toDouble()
        } else {
            requestedFrequencyHz
        }
        val txDelaySec = intent.getDoubleExtra(EXTRA_TX_DELAY_S, 0.0)
        val forceIdentify = intent.getBooleanExtra(EXTRA_TX_FORCE_IDENTIFY, false)
        val forceData = intent.getBooleanExtra(EXTRA_TX_FORCE_DATA, false)
        val automatic = intent.getBooleanExtra(EXTRA_TX_AUTOMATIC, false)
        val effectiveForceIdentify = forceIdentify || callsign.isNotBlank()
        val payloadText = applyGridIfHeartbeat(text, grid)

        // Set transmit mode if configured (before queuing TX)
        val modeSet = setTransmitMode()
        if (!modeSet) {
            Log.w(TAG, "Failed to set transmit mode (rig control might not be connected)")
        }

        Log.i(
            TAG,
            "TX request: text='$payloadText', directed='${directed}', submode=$submode, freq=$audioFrequencyHz, delay=$txDelaySec, identify=$effectiveForceIdentify"
        )

        val ok = try {
            transmitIfIdle(activeEngine) {
                activeEngine.transmitMessage(
                    text = payloadText,
                    myCall = callsign,
                    myGrid = grid,
                    selectedCall = directed,
                    submode = submode,
                    audioFrequencyHz = audioFrequencyHz,
                    txDelaySec = txDelaySec,
                    forceIdentify = effectiveForceIdentify,
                    forceData = forceData
                )
            } ?: return handBack("until the current transmission ends")
        } catch (e: IllegalStateException) {
            // A stop can close the engine while the rig mode is being set
            return handBack("because the engine closed")
        }

        if (ok) {
            Log.i(TAG, "TX request accepted")
            // Upstream turned the heartbeat setting off on any other send, auto-replies included,
            // without telling anyone. Instead restart the heartbeat timer: the next scheduled
            // heartbeat comes a full interval after this send.
            if (!isHeartbeatTraffic(text)) heartbeatHandler.post { scheduleHeartbeat(false) }
            else TxOffsets.heartbeatSent(this, submode)
            updateLastTxMessage(payloadText, directed, submode, audioFrequencyHz)
            // AGN? replays what the operator sent, never a relay forward or another auto-reply.
            if (!automatic && !isHeartbeatTraffic(text)) lastOperatorTxMessage = lastTxMessage
            broadcastTxState(TX_STATE_QUEUED)
            startTxMonitor(frameTextsFor(payloadText, callsign, grid, directed, submode, effectiveForceIdentify, forceData))
        } else {
            Log.e(TAG, "TX request rejected")
            broadcastError("Failed to start transmit")
            // The engine also turns sends down while it stops or starts
            broadcastTxState(TX_STATE_FAILED, refused = !TxMessageClassifier.isEncodable(text))
        }
    }

    private fun handBack(reason: String) {
        Log.i(TAG, "TX request handed back $reason")
        broadcastTxState(TX_STATE_HANDED_BACK)
    }

    private fun setTransmitMode(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (rigControlMode == "trusdx_serial") {
            Log.d(TAG, "Setting transmit mode for TruSDX: USB")
            return sendRigModeCommand(RIG_MODE_USB, 0)
        }
        if (rigControlMode == "qmx_serial") {
            Log.d(TAG, "Setting transmit mode for QMX: Digi")
            return qmxCatSession?.setDigiMode() == true
        }
        if (rigControlMode == "btech_uvpro") {
            // FM only; there is no mode to set.
            return true
        }
        val txMode = prefs.getString(PREF_TRANSMIT_MODE, "none") ?: "none"
        
        Log.d(TAG, "Setting transmit mode: $txMode")
        
        return when (txMode) {
            "none" -> true
            "usb" -> sendRigModeCommand(RIG_MODE_USB, 0)  // 0 = rig default passband
            "usb_data" -> sendRigModeCommand(RIG_MODE_PKTUSB, 0)
            else -> true
        }
    }
    
    private fun sendRigModeCommand(mode: String, passband: Int = 0): Boolean {
        return when (rigControlMode) {
            "hamlib_usb" -> hamlibRigControl?.setMode(mode, passband) == true
            "trusdx_serial" -> trusdxSerialSession?.setUsbMode() == true
            "qmx_serial" -> qmxCatSession?.setDigiMode() == true
            "rts_ptt" -> false
            // Network rig control mode setting not requested yet
            else -> false
        }
    }

    /** A [refused] send counts as failed even though it never reached the air. */
    private fun broadcastTxState(state: String, refused: Boolean = false) {
        if (state == TX_STATE_STARTED || state == TX_STATE_FINISHED || state == TX_STATE_FAILED) {
            lastTxActivityMs = System.currentTimeMillis()
        }
        val intent = Intent(ACTION_TX_STATE).apply {
            putExtra(EXTRA_TX_STATE, state)
            putExtra(EXTRA_TX_REFUSED, refused)
            // Where this send is going out, exactly as the engine was given it (waterfall "SENDING").
            putExtra(EXTRA_TX_ON_AIR_HZ, lastTxFrequencyHz.toFloat())
            putExtra(EXTRA_TX_ON_AIR_BW_HZ, TxOffsets.bandwidthHz(lastTxSubmode))
        }
        AppBroadcasts.send(this, intent)
    }

    private fun broadcastTxSent() {
        val startMs = txSentStartMs
        if (startMs == 0L) return
        txSentStartMs = 0L
        // The bubble already says who sent it
        val own = getConfiguredCallsign()?.let { "$it:" }
        var text = lastTxMessage.trim()
        if (own != null && text.startsWith(own, ignoreCase = true)) text = text.drop(own.length).trim()
        if (text.isBlank()) return
        val intent = Intent(ACTION_TX_SENT).apply {
            putExtra(EXTRA_TX_SENT_TEXT, text.uppercase())
            putExtra(EXTRA_TX_SENT_FREQ, lastTxFrequencyHz.toFloat())
            putExtra(EXTRA_UTC, utcHhmmss(startMs))
        }
        AppBroadcasts.send(this, intent)
    }

    private fun persistIncomingMessage(
        from: String,
        text: String,
        snr: Int,
        freq: Float,
        relayPath: String?,
        conversationId: String = from
    ) {
        messageScope.launch {
            try {
                messageRepository.insertIncomingMessage(conversationId, from, text, snr, freq, relayPath)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist incoming MSG from $from", t)
                return@launch
            }

            mainHandler.post {
                val intent = Intent(ACTION_MESSAGE_RECEIVED).apply {
                    putExtra(EXTRA_MESSAGE_FROM, from)
                    putExtra(EXTRA_MESSAGE_TEXT, text)
                    putExtra(EXTRA_MESSAGE_SNR, snr)
                    putExtra(EXTRA_MESSAGE_FREQ, freq)
                    putExtra(EXTRA_MESSAGE_CONVERSATION_ID, conversationId)
                    relayPath?.let { putExtra(EXTRA_MESSAGE_RELAY_PATH, it) }
                }
                AppBroadcasts.send(this@JS8EngineService, intent)
                showMessageNotification(from, text)
            }
        }
    }
    
    /**
     * Broadcast a request to queue a TX message.
     * The UI layer (TransmitViewModel) will handle adding it to the TX queue.
     */
    private fun broadcastQueueTx(text: String, directed: String?, priority: Int = 0, submode: Int? = null) {
        val intent = Intent(ACTION_QUEUE_TX).apply {
            putExtra(EXTRA_QUEUE_TX_TEXT, text)
            directed?.let { putExtra(EXTRA_QUEUE_TX_DIRECTED, it) }
            putExtra(EXTRA_QUEUE_TX_PRIORITY, priority)
            submode?.let { putExtra(EXTRA_QUEUE_TX_SUBMODE, it) }
        }
        AppBroadcasts.send(this, intent)
        Log.d(TAG, "Broadcast queue TX: text='$text' directed=$directed priority=$priority")
    }

    private fun showReceiverRestartNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_MESSAGES, getString(R.string.notification_message_channel_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = getString(R.string.notification_message_channel_desc) },
        )
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID_RECEIVER_RESTART,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_RESTART_RECEIVER, true)
                putExtra(MainActivity.EXTRA_RESTART_TOKEN, MainActivity.newRestartToken(this@JS8EngineService))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIFICATION_ID_RECEIVER_RESTART,
            NotificationCompat.Builder(this, CHANNEL_ID_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_js8t)
                .setContentTitle(getString(R.string.notification_receiver_closed_title))
                .setContentText(getString(R.string.notification_receiver_closed_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notification_receiver_closed_text)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun showMessageNotification(from: String, text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // Create message notification channel if it doesn't exist
        val channel = NotificationChannel(
            CHANNEL_ID_MESSAGES,
            getString(R.string.notification_message_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_message_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)
        
        // Create intent to open the app
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_messages", true)
            putExtra("callsign", from)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, from.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_js8t)
            .setContentTitle(getString(R.string.notification_new_message_title, from))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_MESSAGE_BASE + from.hashCode(), notification)
    }

    /**
     * The text each frame of a send carries, framed exactly as the engine frames
     * it, for the "Sending" bar. Null (no bar) if it can't be worked out; the
     * send itself doesn't depend on it.
     */
    private fun frameTextsFor(
        text: String, myCall: String, myGrid: String, selected: String,
        submode: Int, forceIdentify: Boolean, forceData: Boolean,
    ): List<String>? = runCatching {
        JS8Engine.frameTexts(myCall, myGrid, selected, text, submode, forceIdentify, forceData)
    }.onFailure { Log.w(TAG, "frame texts unavailable", it) }.getOrNull()
        ?.map { it.uppercase(java.util.Locale.ROOT) }
        ?.let { TxProgress.forDisplay(it, myCall) }

    /**
     * Where the send in progress has got, frame by frame. [stopped] marks a send
     * cut short (operator stop or PTT failure); a frame on air then was cut.
     */
    private fun broadcastTxProgress(onAir: Boolean, stopped: Boolean = false) {
        val frames = txFrameTexts
        if (frames.isEmpty()) return
        val intent = Intent(ACTION_TX_PROGRESS).apply {
            putExtra(EXTRA_TX_FRAME_TEXTS, frames.toTypedArray())
            putExtra(EXTRA_TX_FRAMES_STARTED, txFramesStarted)
            putExtra(EXTRA_TX_FRAME_ON_AIR, onAir)
            putExtra(EXTRA_TX_STOPPED, stopped)
            putExtra(EXTRA_TX_SENT_FREQ, lastTxFrequencyHz.toFloat())
        }
        AppBroadcasts.send(this, intent)
    }

    /** A send ended early: say how much of it went out, then forget it. */
    private fun endTxProgressStopped(frameWasOnAir: Boolean) {
        broadcastTxProgress(onAir = frameWasOnAir, stopped = true)
        txFrameTexts = emptyList()
        txFramesStarted = 0
    }

    private fun startTxMonitor(frameTexts: List<String>? = null) {
        txMonitorHandler.removeCallbacks(txMonitorRunnable)
        pttHoldSinceMs = 0L
        txMonitorActive = true
        txMonitorWasAudioActive = false
        txSentStartMs = 0L
        txFrameTexts = frameTexts.orEmpty()
        txFramesStarted = 0
        broadcastTxProgress(onAir = false)
        synchronized(pttStateLock) {
            txPttGeneration++
            rigPttDesired = rigPttAsserted
            rigPttDesiredGeneration = txPttGeneration
            rigPttFailureCount = 0
            rigPttCompletion = null
            pttShuttingDown = false
            txPttFailed = false
        }
        if (rigControlMode == "trusdx_serial") {
            trusdxTxIntentActive = true
        }
        txMonitorHandler.post(txMonitorRunnable)
    }

    private fun stopTxMonitor() {
        if (!txMonitorActive) return
        txMonitorActive = false
        trusdxTxIntentActive = false
        txMonitorHandler.removeCallbacks(txMonitorRunnable)
    }

    private fun isRigPttReady(generation: Int): Boolean {
        return synchronized(pttStateLock) {
            generation == txPttGeneration &&
                rigPttAsserted &&
                rigPttDesired &&
                !rigPttCommandPending
        }
    }

    private fun isCurrentTxGeneration(generation: Int): Boolean {
        return synchronized(pttStateLock) { generation == txPttGeneration }
    }

    private fun requestRigPtt(
        enabled: Boolean,
        generation: Int,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (!enabled) engine?.setTransmitReady(false)

        var completion: ((Boolean) -> Unit)? = null
        var scheduleCommand = false
        var openTransmitGate = false
        synchronized(pttStateLock) {
            if (generation != txPttGeneration || (txPttFailed && enabled)) return
            if (rigPttDesired != enabled || rigPttDesiredGeneration != generation) {
                rigPttDesired = enabled
                rigPttDesiredGeneration = generation
                rigPttFailureCount = 0
            }
            if (onComplete != null) rigPttCompletion = onComplete

            if (!rigPttCommandPending) {
                if (rigPttAsserted == rigPttDesired) {
                    completion = rigPttCompletion
                    rigPttCompletion = null
                    openTransmitGate = enabled && generation == txPttGeneration
                } else {
                    rigPttCommandPending = true
                    scheduleCommand = true
                }
            }
        }

        if (openTransmitGate) engine?.setTransmitReady(true)
        completion?.invoke(true)
        if (scheduleCommand) postTxWork { runRigPttCommand() }
    }

    private fun postTxWork(work: Runnable) {
        txHandler.postAtTime(work, txWorkToken, SystemClock.uptimeMillis())
    }

    private fun runRigPttCommand() {
        val target: Boolean
        val generation: Int
        synchronized(pttStateLock) {
            target = rigPttDesired
            generation = rigPttDesiredGeneration
            if (target) rigPttMaybeKeyed = true
        }

        val success = setRigPtt(target)
        var scheduleNext = false
        var completion: ((Boolean) -> Unit)? = null
        var failEnable = false
        var transmitReady: Boolean? = null
        var completionResult = success
        var retryDelayMs = 0L
        var stuckWarning: Int? = null
        var stuckCleared = false
        synchronized(pttStateLock) {
            rigPttCommandPending = false
            if (success) {
                rigPttAsserted = target
                rigPttFailureCount = 0
                if (!target) {
                    rigPttMaybeKeyed = false
                    if (rigPttReleaseFailures >= PTT_RELEASE_WARN_AFTER) stuckCleared = true
                    rigPttReleaseFailures = 0
                }
                transmitReady = if (target) {
                    target == rigPttDesired && generation == txPttGeneration
                } else {
                    false
                }
            }

            if (pttShuttingDown) {
                transmitReady = false
                rigPttCompletion = null
                return@synchronized
            }

            val targetStillDesired = target == rigPttDesired &&
                generation == rigPttDesiredGeneration
            if (!success && !target && targetStillDesired) {
                // A radio left keyed is the worst failure this app can have, and unkeying is
                // always safe to repeat: keep trying, backing off, until it takes or a new send
                // or Stop takes over the PTT.
                rigPttReleaseFailures++
                rigPttCommandPending = true
                scheduleNext = true
                retryDelayMs = pttReleaseRetryDelayMs(rigPttReleaseFailures)
                if (rigPttReleaseFailures == PTT_RELEASE_WARN_AFTER) stuckWarning = rigPttReleaseFailures
            } else if (!success && targetStillDesired) {
                rigPttFailureCount++
                if (rigPttFailureCount <= PTT_COMMAND_RETRIES) {
                    rigPttCommandPending = true
                    scheduleNext = true
                } else {
                    completion = rigPttCompletion
                    rigPttCompletion = null
                    failEnable = target
                    rigPttDesired = rigPttAsserted
                }
            } else if (rigPttAsserted != rigPttDesired) {
                rigPttCommandPending = true
                scheduleNext = true
            } else if (rigPttDesiredGeneration == txPttGeneration) {
                completion = rigPttCompletion
                rigPttCompletion = null
                completionResult = true
            }
        }

        transmitReady?.let { engine?.setTransmitReady(it) }

        mainHandler.post {
            if (success) {
                Log.i(TAG, "PTT ${if (target) "enabled" else "released"} generation=$generation")
            } else {
                Log.w(TAG, "PTT ${if (target) "enable" else "release"} failed generation=$generation")
            }
            completion?.invoke(completionResult)
            if (failEnable) {
                failTransmitForPtt(generation, "Failed to enable PTT")
            }
            stuckWarning?.let { showPttStuckWarning(it) }
            if (stuckCleared) clearPttStuckWarning()
        }
        if (scheduleNext) {
            if (retryDelayMs > 0) {
                Log.w(TAG, "PTT release failed; retrying in $retryDelayMs ms")
                txHandler.postAtTime({ runRigPttCommand() }, txWorkToken, SystemClock.uptimeMillis() + retryDelayMs)
            } else {
                postTxWork { runRigPttCommand() }
            }
        }
    }

    /** 0.25 s, 0.5 s, 1 s, 2 s, 4 s, then every 5 s. */
    private fun pttReleaseRetryDelayMs(failures: Int): Long =
        (250L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(5_000L)

    /** Sticky warning: the app couldn't confirm the radio unkeyed (it keeps trying). */
    private fun showPttStuckWarning(failures: Int, stillTrying: Boolean = true) {
        val text = getString(if (stillTrying) R.string.ptt_stuck_text else R.string.ptt_stuck_text_stopped, failures)
        Log.e(TAG, "PTT release failed $failures times in a row: the radio may still be transmitting")
        pttStuckWarningShown = true
        broadcastError(text)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_MESSAGES, getString(R.string.notification_message_channel_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = getString(R.string.notification_message_channel_desc) },
        )
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID_PTT_STUCK,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIFICATION_ID_PTT_STUCK,
            NotificationCompat.Builder(this, CHANNEL_ID_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_js8t)
                .setContentTitle(getString(R.string.ptt_stuck_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(open)
                .build(),
        )
    }

    private fun clearPttStuckWarning() {
        // Every confirmed shutdown release comes here; only say so when a warning was up.
        if (pttStuckWarningShown) Log.i(TAG, "PTT release confirmed after earlier failures")
        pttStuckWarningShown = false
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_PTT_STUCK)
    }

    /** The "radio may still be transmitting" warning is showing. */
    @Volatile private var pttStuckWarningShown = false

    /**
     * Operator pressed Stop: end the send now and unkey, whatever state TX is in.
     * Reported as refused so the queue drops it instead of retrying.
     */
    private fun haltTransmit() {
        val generation = synchronized(pttStateLock) {
            rigPttDesired = false
            rigPttDesiredGeneration = txPttGeneration
            rigPttCompletion = null
            if (!rigPttCommandPending && (rigPttAsserted || rigPttMaybeKeyed)) {
                rigPttCommandPending = true
                postTxWork { runRigPttCommand() }
            }
            txPttGeneration
        }
        Log.w(TAG, "Transmit halted by operator (generation $generation)")
        endTxProgressStopped(frameWasOnAir = txMonitorWasAudioActive)
        txMonitorActive = false
        txMonitorHandler.removeCallbacks(txMonitorRunnable)
        pttHoldSinceMs = 0L
        engine?.setTransmitReady(false)
        // Stopping the audio can wait on the audio thread; never on the main thread (an ANR here
        // would leave the app frozen). PTT has already been dropped above on the TX thread.
        engine?.let { e -> Thread({ runCatching { e.stopTransmit() }.onFailure { Log.e(TAG, "stopTransmit failed", it) } }, "tx-halt").start() }
        txSessionActive = false
        txAudioActive = false
        txMonitorWasAudioActive = false
        trusdxTxIntentActive = false
        broadcastTxState(TX_STATE_FAILED, refused = true)
    }

    private fun failTransmitForPtt(generation: Int, message: String) {
        synchronized(pttStateLock) {
            if (generation != txPttGeneration || txPttFailed) return
            txPttFailed = true
            rigPttDesired = false
            rigPttDesiredGeneration = generation
            rigPttFailureCount = 0
            rigPttCompletion = null
            if (!rigPttCommandPending && (rigPttAsserted || rigPttMaybeKeyed)) {
                rigPttCommandPending = true
                postTxWork { runRigPttCommand() }
            }
        }

        Log.e(TAG, message)
        endTxProgressStopped(frameWasOnAir = txMonitorWasAudioActive)
        txMonitorActive = false
        txMonitorHandler.removeCallbacks(txMonitorRunnable)
        engine?.stopTransmit()
        txSessionActive = false
        txAudioActive = false
        txMonitorWasAudioActive = false
        trusdxTxIntentActive = false
        engine?.setTransmitReady(false)
        broadcastError(message)
        broadcastTxState(TX_STATE_FAILED)
    }

    /**
     * Handle incoming MSG commands - always runs regardless of autoreply setting.
     * This ensures messages are saved to inbox even if auto-ACK is disabled.
     * 
     * JS8Call MSG format can be:
     *   FROM: TO MSG payload    (with space, single frame)
     *   FROM: TO MSGpayload     (without space, bandwidth optimization)
     *   FROM: TO MSG            (multi-frame: command frame)
     *   payload...              (multi-frame: data frames follow)
     */
    private fun maybeHandleIncomingMessage(text: String, snr: Int, freq: Float, type: Int, submode: Int) {
        val callsign = getConfiguredCallsign() ?: return
        val msg = msgAssembler.onFrame(
            text, snr, freq, type, System.currentTimeMillis(), submode, framePeriodMs(submode)
        ) { command, to ->
            val forMe = isSelfCallsign(callsign, to)
            // A QUERY only ever asks us; MSG and MSG TO: can come to a group we follow.
            forMe || (command != Js8Commands.CMD_QUERY && isSubscribedGroup(to))
        } ?: return
        processMsg(msg, callsign)
    }

    // ------------------------------------------------------------------ store and forward

    /** An incoming MSG to us: a stored message we asked for reads "text FROM AUTHOR [NEXT MSG ID n]". */
    private fun persistStoredAwareMessage(from: String, text: String, snr: Int, freq: Float, conversationId: String) {
        val sf = com.js8call.example.storeforward.SfParse.delivered(text)
        val station = from.trim().uppercase()
        val asked = heldStore.waiting().any { com.js8call.example.storeforward.Held.base(it.station) == com.js8call.example.storeforward.Held.base(station) && it.askedMs != null }
        val shown = when {
            asked && sf.author != null -> "${sf.author}: ${sf.text}"
            sf.next != null -> sf.text + (sf.author?.let { " FROM $it" } ?: "")
            else -> text
        }
        persistIncomingMessage(from, shown, snr, freq, null, conversationId)
        if (asked || sf.next != null) heldStore.gotFrom(station, null)
        sf.next?.let { heldStore.addWaiting(station, it, System.currentTimeMillis()) }
    }

    /** MSG TO: (hold this for someone) and QUERY MSG n (send me message n), once complete. */
    private fun handleStoreForwardCommand(command: String, from: String, to: String, payload: String, snr: Int, freq: Float, submode: Int) {
        val me = getConfiguredCallsign() ?: return
        val now = System.currentTimeMillis()
        when (command) {
            Js8Commands.CMD_MSG_TO -> {
                val (target, text) = com.js8call.example.storeforward.SfParse.msgTo(payload) ?: return
                if (isSelfCallsign(me, target)) {
                    // Addressed to us after all: straight to the Inbox.
                    persistStoredAwareMessage(from, text, snr, freq, from)
                } else {
                    if (!heldStore.holdEnabled) { Log.i(TAG, "MSG TO:$target from $from not held (holding is off)"); return }
                    val base = com.js8call.example.storeforward.Held.base(target)
                    if (!heldStore.duplicate(base, from, text)) {
                        val h = heldStore.add(com.js8call.example.storeforward.Held(
                            id = 0, to = base, from = from, text = text, receivedMs = now,
                            kind = com.js8call.example.storeforward.Held.Kind.RELAY,
                        ))
                        Log.i(TAG, "Holding message ${h.id} for $base from $from")
                    }
                }
                if (isAutoreplyEnabled()) broadcastQueueTx("$me: $from ACK", null, priority = 2)
            }
            Js8Commands.CMD_QUERY -> {
                val id = com.js8call.example.storeforward.SfParse.queryMsgId(payload) ?: return
                if (!isAutoreplyEnabled() || !heldStore.serveEnabled) { Log.i(TAG, "QUERY MSG $id from $from not answered (auto-reply or serving off)"); return }
                val (reply, h) = com.js8call.example.storeforward.HeldLogic.answerQueryMsg(heldStore.all(), id, from, now) ?: run {
                    Log.i(TAG, "QUERY MSG $id from $from: nothing it may fetch"); return
                }
                Log.i(TAG, "Sending held message ${h.id} to $from")
                broadcastQueueTx("$me: $reply", null, priority = 2, submode = h.submode)
                heldStore.markSent(h.id, from, now)
            }
        }
    }

    /** QUERY MSGS to us, @ALLCALL or a group: say whether something is held for the asker. */
    private fun answerQueryMsgs(me: String, d: DirectedCommand) {
        if (!heldStore.serveEnabled) return
        val from = d.from.trim().uppercase()
        if (from.isBlank() || isSelfCallsign(me, from)) return
        val to = d.to.trim().uppercase()
        val toMe = isSelfCallsign(me, to)
        val held = heldStore.all()
        // A group we are in, or one we hold messages for (posted reports to @GHOSTNET answer @GHOSTNET).
        val groupOk = to == "@ALLCALL" || isSubscribedGroup(to) || held.any { it.to.equals(to, true) }
        if (!toMe && !(to.startsWith("@") && groupOk)) return
        val reply = com.js8call.example.storeforward.HeldLogic.answerQueryMsgs(held, from, to, toMe, System.currentTimeMillis()) ?: return
        Log.i(TAG, "QUERY MSGS from $from to $to: $reply")
        broadcastQueueTx("$me: $reply", null, priority = 1)
    }

    /** Notes "MSG ID n" offers addressed to us, whole or split over a directed frame and a data frame. */
    private fun trackStoredMessageOffers(text: String, freq: Float, type: Int, submode: Int) {
        val me = getConfiguredCallsign() ?: return
        val now = System.currentTimeMillis()
        val key = Math.round(freq / 10f)
        com.js8call.example.storeforward.SfParse.offer(text, me)?.let {
            if (!isSelfCallsign(me, it.station)) heldStore.addWaiting(it.station.uppercase(), it.id, now)
            return
        }
        val d = RelayProtocol.parseDirectedCommand(text)
        if (d != null) {
            val c = d.command.uppercase()
            if (isSelfCallsign(me, d.to) && !isSelfCallsign(me, d.from) && (c == Js8Commands.CMD_YES || c.startsWith("HEARTBEAT SNR")) && !isLastFrame(type)) {
                synchronized(offerStarts) { offerStarts[key] = d.from.trim().uppercase() to now }
            }
            return
        }
        if (!DecodedMessage.isDataFrame(type)) return
        val start = synchronized(offerStarts) {
            offerStarts.entries.removeIf { now - it.value.second > 3 * framePeriodMs(submode) }
            offerStarts.keys.firstOrNull { kotlin.math.abs(it - key) <= 1 }?.let { offerStarts.remove(it) }
        } ?: return
        com.js8call.example.storeforward.SfParse.offerTail(text)?.let { heldStore.addWaiting(start.first, it, now) }
    }

    /** Stored-message commands to us or our groups, collected frame by frame (see [MsgAssembler]). */
    private val msgAssembler = MsgAssembler()

    /**
     * A stored-message command heard in full. A MSG goes to the Inbox either way, marked when its
     * checksum didn't match; only an intact one is acknowledged automatically, so a sender never
     * hears "ACK" for a message that arrived garbled or with frames missing (JS8Call discards
     * those). MSG TO: and QUERY drive automatic store-and-forward replies, so they are acted on
     * only when intact, as in JS8Call.
     */
    private fun processMsg(msg: ReceivedMsg, myCallsign: String) {
        if (msg.command != Js8Commands.CMD_MSG) {
            if (!msg.intact) {
                Log.w(TAG, "${msg.command} from ${msg.from} failed its checksum; ignored")
                return
            }
            handleStoreForwardCommand(msg.command, msg.from, msg.to, msg.text, msg.snr, msg.freq, msg.submode)
            return
        }
        val conversationId = if (isSubscribedGroup(msg.to)) msg.to else msg.from
        val shown = if (msg.intact || msg.gaps > 0) msg.text else "${msg.text} $MSG_CHECKSUM_FAILED_NOTE"
        Log.i(TAG, "MSG received: from=${msg.from} to=${msg.to} intact=${msg.intact} gaps=${msg.gaps} conversationId=$conversationId")
        persistStoredAwareMessage(msg.from, shown, msg.snr, msg.freq, conversationId)

        if (!isAutoreplyEnabled()) return
        if (!msg.intact) {
            Log.i(TAG, "No auto ACK for MSG from=${msg.from}: it didn't arrive intact")
            return
        }
        Log.i(TAG, "Auto ACK for MSG from=${msg.from}")
        broadcastQueueTx("$myCallsign: ${msg.from} ACK", null, priority = 2)
    }

    private fun isSubscribedGroup(target: String): Boolean {
        if (!target.startsWith("@")) return false
        return myGroups(PreferenceManager.getDefaultSharedPreferences(this)).contains(target.uppercase())
    }

    /** The groups the operator joined (Settings → My Groups), upper-case. */
    private fun myGroups(prefs: android.content.SharedPreferences): List<String> =
        (prefs.getString("my_groups", "") ?: "").split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    private fun maybeHandleAutoReply(text: String, snr: Int, mode: Int) {
        if (!isAutoreplyEnabled()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign()
        if (callsign == null) {
            warnMissingCallsign()
            return
        }
        if (isTransmitActive()) return

        val me = autoReplyStation(callsign, prefs)
        val ack = AutoReply.heartbeatAck(text, snr, me) { target ->
            // Desktop JS8Call adds " MSG ID n" when it holds a message for the station heartbeating.
            if (heldStore.serveEnabled)
                com.js8call.example.storeforward.HeldLogic.heartbeatExtra(heldStore.all(), target, "@HB", System.currentTimeMillis()) else ""
        }
        if (ack != null) {
            Log.i(TAG, "Auto HB ACK: '$ack'")
            broadcastQueueTx(ack, null, priority = 1)
            return
        }
        if (RelayProtocol.heartbeatSender(text) != null) return

        val directed = RelayProtocol.parseDirectedCommand(text) ?: return
        if (directed.command.uppercase().startsWith(Js8Commands.CMD_QUERY_MSGS)) {
            answerQueryMsgs(callsign, directed)
            return
        }
        // MSG auto-ACK is handled in maybeHandleIncomingMessage after the full message is received.
        val heard = AutoReply.Heard { exclude -> getRecentHeardCallsigns(exclude, HEARD_LIMIT) }
        val groupReply = AutoReply.groupReply(directed, snr, me, myGroups(prefs), heard)
        if (groupReply != null) {
            // Desktop answers group queries with no limit; with the safeguard on we answer each
            // asker's same query to the same group once per 15 min (desktop's @ALLCALL spacing).
            val safeguard = prefs.getBoolean(com.js8call.example.ui.TransmitViewModel.PREF_AUTO_REPLY_SAFEGUARD, true)
            if (safeguard && !groupReplyGuard.allow(AutoReply.groupReplyKey(directed), System.currentTimeMillis())) {
                Log.i(TAG, "Group ${directed.command.uppercase()} from ${directed.from} to ${directed.to} again within 15 min: not answered")
                return
            }
            Log.i(TAG, "Auto ${directed.command.uppercase()} reply to ${directed.from} (asked ${directed.to})")
            broadcastQueueTx(groupReply, null, priority = 1)
            return
        }
        val reply = AutoReply.directedReply(directed, snr, me, heard) ?: return
        if (directed.command.uppercase() == "AGN?" && !agnReplayGuard.allow(directed.from, System.currentTimeMillis())) {
            Log.i(TAG, "AGN? from ${directed.from} again within 10 min: not replayed automatically (it shows in Traffic)")
            return
        }
        Log.i(TAG, "Auto ${directed.command.uppercase()} reply to ${directed.from}")
        broadcastQueueTx(reply, null, priority = 1)
    }

    /** What auto-replies may say about this station, from Settings. */
    private fun autoReplyStation(callsign: String, prefs: android.content.SharedPreferences) = AutoReply.Station(
        callsign = callsign,
        info = prefs.getString(PREF_MY_INFO, "").orEmpty(),
        status = prefs.getString(PREF_MY_STATUS, "").orEmpty(),
        grid = prefs.getString("grid", "").orEmpty(),
        lastTx = lastOperatorTxMessage,
    )

    private fun handleRelayFrame(text: String, snr: Int, mode: Int, freq: Float, type: Int) {
        if (!isRelayEnabled()) return
        val callsign = getConfiguredCallsign() ?: return
        val relay = relayAssembler.onFrame(
            text, snr, freq, type, System.currentTimeMillis(), callsign, getPreferredTxSubmode()
        ) ?: return
        Log.i(TAG, "Relay heard in full from=${relay.from} freq=$freq")
        processRelay(relay)
    }

    private fun isAutoreplyEnabled(): Boolean {
        // Held for a few minutes after the Net Switcher changed band (setting), so an untuned antenna isn't keyed.
        if (System.currentTimeMillis() < netHoldUntilMs) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(PREF_AUTOREPLY_ENABLED, false)
    }

    private fun isTruSdxDiagnosticsEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(PREF_TRUSDX_DIAGNOSTICS_ENABLED, false)
    }

    private fun isRelayEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(PREF_RELAY_ENABLED, false)
    }

    private fun getPreferredTxSubmode(): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val submode = prefs.getInt(PREF_TX_SUBMODE, SUBMODE_NORMAL)
        return when (submode) {
            SUBMODE_NORMAL,
            SUBMODE_FAST,
            SUBMODE_TURBO,
            SUBMODE_SLOW -> submode
            else -> SUBMODE_NORMAL
        }
    }

    private fun getConfiguredCallsign(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = prefs.getString("callsign", "")?.trim().orEmpty().uppercase()
        if (callsign.isBlank()) return null
        callsignWarningShown = false
        return callsign
    }

    private fun warnMissingCallsign() {
        if (callsignWarningShown) return
        callsignWarningShown = true
        broadcastError(getString(R.string.error_callsign_required))
    }

    private fun initPskReporter() {
        if (pskReporterClient != null) return
        val programInfo = "JS8-Tactical ${BuildConfig.VERSION_NAME}"
        pskReporterClient = PskReporterClient(programInfo)
        updatePskReporterState()
    }

    private fun updatePskReporterState() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = prefs.getBoolean(PREF_PSK_REPORTER, false)
        val callsign = prefs.getString("callsign", "")?.trim().orEmpty().uppercase()
        val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()
        Log.d(TAG, "PSKReporter state: enabled=$enabled callsign='$callsign' grid='$grid'")
        currentCallsign = callsign
        currentGrid = grid
        if (!enabled || callsign.isBlank() || grid.isBlank()) {
            pskReporterClient?.stop(flush = false, discardPending = true)
            pskReporterEnabled = false
            return
        }
        pskReporterClient?.start()
        pskReporterClient?.setLocalStation(callsign, grid, "")
        pskReporterEnabled = true
    }

    private fun updateDialFromPrefs() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val dial = prefs.getString("last_frequency", "")?.trim().orEmpty()
        currentDialHz = dial.toLongOrNull() ?: currentDialHz
    }

    private fun maybeReportToPskReporter(utc: Int, snr: Int, freq: Float, text: String) {
        if (!pskReporterEnabled) return
        if (currentDialHz <= 0) {
            Log.d(TAG, "PSKReporter skip: dialHz=$currentDialHz")
            return
        }
        val call = extractHeardCallsign(text)
        if (call == null) {
            Log.d(TAG, "PSKReporter skip: no callsign in '$text'")
            return
        }
        if (currentCallsign.isNotBlank() && isSelfCallsign(currentCallsign, call)) {
            Log.d(TAG, "PSKReporter skip: self call=$call")
            return
        }
        val grid = extractGrid(text)
        if (grid == null) {
            Log.d(TAG, "PSKReporter skip: no grid in '$text'")
            return
        }
        val rfHz = currentDialHz + freq.toInt()
        if (rfHz <= 0) {
            Log.d(TAG, "PSKReporter skip: rfHz=$rfHz dialHz=$currentDialHz freq=$freq")
            return
        }
        val timestamp = decodeUtcToEpochSeconds(utc)
        Log.d(TAG, "PSKReporter spot: call=$call grid=$grid snr=$snr rfHz=$rfHz ts=$timestamp")
        pskReporterClient?.addSpot(call, grid, snr, rfHz, "JS8", timestamp)
    }

    private fun extractGrid(text: String): String? {
        val match = gridRegex.find(text) ?: return null
        return match.value.uppercase(Locale.US)
    }

    private fun decodeUtcToEpochSeconds(utc: Int): Long {
        val hours = utc / 10000
        val minutes = (utc / 100) % 100
        val seconds = utc % 100
        val nowMillis = System.currentTimeMillis()
        val nowUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = nowMillis
        }
        val candidate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, nowUtc.get(Calendar.YEAR))
            set(Calendar.MONTH, nowUtc.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, nowUtc.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, hours)
            set(Calendar.MINUTE, minutes)
            set(Calendar.SECOND, seconds)
            set(Calendar.MILLISECOND, 0)
        }
        var candidateMillis = candidate.timeInMillis
        val diff = candidateMillis - nowMillis
        val twelveHoursMs = 12L * 60L * 60L * 1000L
        if (diff > twelveHoursMs) {
            candidateMillis -= 24L * 60L * 60L * 1000L
        } else if (diff < -twelveHoursMs) {
            candidateMillis += 24L * 60L * 60L * 1000L
        }
        return candidateMillis / 1000L
    }

    private fun isTransmitActive(): Boolean {
        return txSessionActive || txAudioActive
    }

    private fun isSelfCallsign(myCall: String, from: String): Boolean {
        return CallsignValidator.matches(myCall, from)
    }


    private fun isLastFrame(type: Int): Boolean = RelayAssembler.isLastFrame(type)

    private fun updateMessageLogging() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = prefs.getBoolean(PREF_LOG_MESSAGES, false)
        messageLogger?.setEnabled(enabled)
    }

    private fun logRxDecode(text: String, snr: Int, freq: Float, mode: Int) {
        val logger = messageLogger ?: return
        logger.logRx(text, snr, freq, mode, extractHeardCallsign(text))
    }

    private fun logTxStarted() {
        val logger = messageLogger ?: return
        val message = lastTxMessage.trim()
        if (message.isBlank()) return
        val directed = lastTxDirected.trim().ifEmpty { null }
        logger.logTx(message, directed, lastTxFrequencyHz, lastTxSubmode)
    }

    /** Acts on a relay heard in full: pass it on, answer it, or acknowledge it. */
    private fun processRelay(relay: ReceivedRelay) {
        val myCall = getConfiguredCallsign() ?: return
        when (val action = RelayAssembler.decide(relay, myCall)) {
            null -> Log.i(TAG, "Relay from ${relay.from} needs nothing from us (blank, group, ACK, failed checksum or a loop)")
            is RelayAction.Forward -> sendRelayMessage(action.text, relay.submode)
            is RelayAction.Deliver -> {
                val handled = maybeHandleRelayedAutoreply(action.payload, action.path, relay.snr, relay.submode)
                if (!handled) sendRelayMessage("${action.path} ACK", relay.submode)
            }
        }
    }

    private fun maybeHandleRelayedAutoreply(
        payload: String,
        relayPath: String,
        snr: Int,
        submode: Int
    ): Boolean {
        if (!isAutoreplyEnabled()) return false
        // No check for a transmission in progress: the queue waits for a free slot.
        val callsign = getConfiguredCallsign() ?: return false
        val me = autoReplyStation(callsign, PreferenceManager.getDefaultSharedPreferences(this))
        val asksAgain = payload.trimStart().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty().uppercase() == "AGN?"
        if (asksAgain && !agnReplayGuard.allow(relayPath.substringAfterLast('>'), System.currentTimeMillis())) {
            Log.i(TAG, "Relayed AGN? from $relayPath again within 10 min: not replayed automatically")
            return true
        }
        val reply = AutoReply.relayedReply(payload, relayPath, snr, me) { exclude -> getRecentHeardCallsigns(exclude, HEARD_LIMIT) }
            ?: return false
        return sendRelayMessage(reply, submode)
    }

    /**
     * A relay forward, relay ACK or relayed auto-reply goes through the transmit queue like every
     * other automatic reply, so it waits for a free slot and honours the same holds: a message
     * still coming in, the Net Switcher's hold after a band change, a radio link that just came
     * back, rig control not yet up. (Sending straight to the engine skipped all of those.)
     */
    private fun sendRelayMessage(text: String, submode: Int): Boolean {
        val payload = text.trim()
        if (payload.isEmpty() || getConfiguredCallsign() == null) return false
        broadcastQueueTx(payload, null, priority = 2, submode = submode)
        return true
    }

    private fun updateHeardCallsign(text: String) {
        val callsign = extractHeardCallsign(text) ?: return
        val now = System.currentTimeMillis()
        synchronized(heardLock) {
            heardCallsigns[callsign] = now
            pruneHeardEntries(now)
        }
    }

    private fun getRecentHeardCallsigns(exclude: Set<String>, limit: Int): List<String> {
        val now = System.currentTimeMillis()
        synchronized(heardLock) {
            pruneHeardEntries(now)
            return heardCallsigns.entries
                .asSequence()
                .filter { !exclude.contains(it.key) }
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
                .toList()
        }
    }

    private fun pruneHeardEntries(now: Long) {
        val iter = heardCallsigns.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value > HEARD_WINDOW_MS) {
                iter.remove()
            }
        }
    }

    private fun extractHeardCallsign(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        val firstToken = trimmed.split(Regex("\\s+"), limit = 2)[0]
        var token = firstToken.trimEnd(':').uppercase()
        if (token.contains(">")) {
            token = token.substringBefore(">").trimEnd(':')
        }
        if (token.startsWith("@")) return null
        if (token in HEARD_EXCLUDE_TOKENS) return null
        return token.takeIf(CallsignValidator::isAmateurCallsign)
    }

    private fun updateLastTxMessage(
        text: String,
        directedCall: String,
        submode: Int,
        frequencyHz: Double
    ) {
        val built = buildTxMessage(text, directedCall)
        if (built.isNotBlank()) {
            lastTxMessage = built
            lastTxDirected = directedCall.trim().uppercase()
            lastTxSubmode = submode
            lastTxFrequencyHz = frequencyHz
        }
    }

    private fun buildTxMessage(text: String, directedCall: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val selected = directedCall.trim().uppercase()
        if (selected.isEmpty()) return trimmed
        if (trimmed.startsWith("`")) return trimmed

        if (TxMessageClassifier.isBaseMessage(trimmed)) return trimmed
        if (trimmed.startsWith(selected, ignoreCase = true)) return trimmed

        val sep = if (trimmed.startsWith(" ")) "" else " "
        return selected + sep + trimmed
    }

    private fun applyGridIfHeartbeat(text: String, grid: String): String {
        val trimmed = text.trim()
        if (grid.length < 4) return trimmed
        val grid4 = grid.substring(0, 4).uppercase()
        if (!TxMessageClassifier.shouldAppendGrid(trimmed)) return trimmed
        val gridRegex = Regex("\\b[A-R]{2}[0-9]{2}\\b", RegexOption.IGNORE_CASE)
        if (gridRegex.containsMatchIn(trimmed)) return trimmed
        return "$trimmed $grid4".trim()
    }

    private fun isRigControlConnected(): Boolean {
        return when (rigControlMode) {
            "network" -> rigCtlConnected
            "hamlib_usb" -> hamlibRigConnected
            "rts_ptt" -> rtsPttConnected
            "trusdx_serial" -> trusdxConnected
            "qmx_serial" -> qmxConnected
            "btech_uvpro" -> uvproConnected
            "rtl_sdr", "airspy_hf" -> sdrConnected
            else -> false
        }
    }

    private fun isRigPttRequired(): Boolean = rigControlMode != "none"

    // transmitMessage() replaces a send the engine already queued
    private fun transmitIfIdle(activeEngine: JS8Engine, submit: () -> Boolean): Boolean? =
        synchronized(txSubmitLock) {
            if (activeEngine.isTransmitting()) {
                null
            } else {
                prepareEngineForTransmit(activeEngine)
                submit()
            }
        }

    private fun prepareEngineForTransmit(activeEngine: JS8Engine) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val seconds = prefs.getString("vox_lead_seconds", "0")?.toIntOrNull() ?: 0
        activeEngine.setVoxLeadMs(voxPreambleDurationMs(rigControlMode, seconds, prefs.getBoolean("rig_control_enabled", false)))
        activeEngine.setTransmitReady(!isRigPttRequired())
    }

    private fun setRigPtt(enabled: Boolean): Boolean {
        if (enabled) pttLedger.keySent()
        val ok = when (rigControlMode) {
            "network" -> rigCtlClient?.setPtt(enabled) == true
            "hamlib_usb" -> hamlibRigControl?.setPtt(enabled) == true
            "rts_ptt" -> setRtsPtt(enabled)
            "trusdx_serial" -> trusdxSerialSession?.setPtt(enabled) == true
            "qmx_serial" -> qmxCatSession?.setPtt(enabled) == true
            "btech_uvpro" -> uvProSession?.setPtt(enabled) == true
            "rtl_sdr", "airspy_hf" -> !enabled
            else -> false
        }
        if (!enabled && ok) pttLedger.releaseConfirmed()
        return ok
    }

    private fun setRtsPtt(enabled: Boolean): Boolean {
        val transport = rtsPttTransport
        val result = when (transport) {
            SerialTransport.USB -> usbSerialBridge?.setRts(enabled) == true
            SerialTransport.BLUETOOTH -> bluetoothSerialBridge?.setRts(enabled) == true
            else -> false
        }
        if (!result) {
            Log.w(TAG, "RTS PTT toggle failed (transport=$transport)")
        }
        return result
    }

    private fun setFrequency(frequencyHz: Long) {
        if (!isRigControlConnected()) {
            Log.d(TAG, "Cannot set frequency: rig control not connected")
            // A configured rig with no link is a failure to report; with rig
            // control off the pick only stores the dial value, so stay quiet
            if (!rigCtlErrorShown && rigControlMode != "none" && rigControlMode != "rts_ptt") {
                broadcastError("Rig control is not connected")
                rigCtlErrorShown = true
            }
            return
        }

        // Run on background thread
        Thread {
            val success = when (rigControlMode) {
                "network" -> rigCtlClient?.setFrequency(frequencyHz) == true
                "hamlib_usb" -> hamlibRigControl?.setFrequency(frequencyHz) == true
                "rts_ptt" -> false
                "trusdx_serial" -> trusdxSerialSession?.setFrequency(frequencyHz) == true
                "qmx_serial" -> qmxCatSession?.setFrequency(frequencyHz) == true
                "btech_uvpro" -> false
                "rtl_sdr", "airspy_hf" -> sdrSession?.tune(frequencyHz, sdrSettings()) == true
                else -> false
            }

            mainHandler.post {
                if (success && isSdrMode()) {
                    // No CAT to report the dial back: the tuned frequency is the dial.
                    currentDialHz = frequencyHz
                    broadcastRadioFrequency(frequencyHz)
                }
                if (success) {
                    Log.i(TAG, "Frequency set to $frequencyHz Hz")
                } else {
                    val detail = when (rigControlMode) {
                        "hamlib_usb" -> hamlibRigControl?.getLastError().orEmpty()
                        "rts_ptt" -> "RTS PTT does not support frequency control"
                        "trusdx_serial" -> "TruSDX serial CAT command failed"
                        "qmx_serial" -> "QMX CAT command failed"
                        "btech_uvpro" -> "UV-Pro frequency is set on the radio"
                        "rtl_sdr", "airspy_hf" -> "the SDR can't tune to ${frequencyHz / 1000} kHz"
                        else -> ""
                    }
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "Failed to set frequency to $frequencyHz Hz: $detail")
                    } else {
                        Log.w(TAG, "Failed to set frequency to $frequencyHz Hz")
                    }
                    if (!rigCtlErrorShown && rigControlMode != "rts_ptt" && rigControlMode != "btech_uvpro") {
                        if (detail.isNotBlank()) {
                            broadcastError("Rig control failed: $detail")
                        } else {
                            broadcastError("Rig control communication failed")
                        }
                        rigCtlErrorShown = true
                    }
                }
            }
        }.start()
    }

    companion object {
        private const val REPORT_TICK_MS = 15_000L
        /** True while the engine is running (process-wide; read by the send queue). */
        @Volatile var engineRunning: Boolean = false
            private set

        @Volatile private var emergencyUnkeyHook: (() -> Boolean)? = null

        /**
         * Last resort from the crash handler: if the radio may be keyed, try once to unkey it,
         * waiting at most [timeoutMs]. A crash while transmitting would otherwise leave a CAT- or
         * RTS-keyed radio transmitting until its own time-out (second audit, N9).
         */
        fun emergencyUnkey(timeoutMs: Long) {
            val hook = emergencyUnkeyHook ?: return
            val t = Thread({ runCatching { hook() } }, "emergency-unkey").apply { isDaemon = true }
            t.start()
            runCatching { t.join(timeoutMs) }
        }

        // Shared by service instances: stopping monitoring destroys the service
        // while a UV-Pro worker may still hold the radio's sockets.
        private val uvproWorkersActive = AtomicInteger(0)

        private const val TAG = "JS8EngineService"
        // The teardown outlives the service instance that posted it
        @Volatile private var rigTeardownPending = false
        private const val PREF_AUTOREPLY_ENABLED = "autoreply_enabled"
        private const val PREF_RELAY_ENABLED = "relay_enabled"
        const val PREF_TX_SUBMODE = "tx_submode"
        private const val PREF_MY_INFO = "my_info"
        private const val PREF_MY_STATUS = "my_status"
        private const val PREF_PSK_REPORTER = "psk_reporter"
        private const val RIG_STATUS_POLL_INTERVAL_MS = 2000L
        private const val PREF_TRUSDX_DIAGNOSTICS_ENABLED = "trusdx_diagnostics_enabled"
        private const val HEARD_LIMIT = 4
        private const val HEARD_WINDOW_MS = 15 * 60 * 1000L
        private val HEARD_EXCLUDE_TOKENS = setOf("CQ", "HB", "HEARTBEAT", "ALLCALL", "@ALLCALL")
        const val SUBMODE_NORMAL = 0
        const val SUBMODE_FAST = 1
        const val SUBMODE_TURBO = 2
        const val SUBMODE_SLOW = 4

        // Heartbeat sub-band, matching the desktop app
        private const val HB_SUBBAND_LOW_HZ = 500
        private const val HB_SUBBAND_HIGH_HZ = 1000

        // Outgoing HB ACK: "MYCALL: TARGET HEARTBEAT SNR +NN"
        private val heartbeatAckTxRegex =
            Regex("^\\s*[A-Z0-9/]+:\\s+\\S+\\s+HEARTBEAT\\s+SNR\\b", RegexOption.IGNORE_CASE)

        // Actions
        /** Settings that decide how the radio is reached; changing one restarts the receiver. */
        private val RIG_SETTING_KEYS = setOf(
            "rig_control_enabled", "rig_type", "rig_hamlib_model", "rig_hamlib_usb_port",
            "rig_serial_baud", "rig_serial_data_bits", "rig_serial_stop_bits", "rig_serial_parity",
            "rigctld_host", "rigctld_port", "qmx_cat_baud",
        )
        private const val RIG_SETTINGS_SETTLE_MS = 3_000L
        const val ACTION_START = "com.js8call.example.ACTION_START"
        const val ACTION_STOP = "com.js8call.example.ACTION_STOP"
        /** Stop everything and close the app (notification Exit). */
        const val ACTION_EXIT = "com.js8call.example.ACTION_EXIT"
        const val ACTION_SWITCH_AUDIO_DEVICE = "com.js8call.example.ACTION_SWITCH_AUDIO_DEVICE"
        const val ACTION_SET_FREQUENCY = "com.js8call.example.ACTION_SET_FREQUENCY"
        const val ACTION_SET_TX_OFFSET = "com.js8call.example.ACTION_SET_TX_OFFSET"
        /** Stop the current send immediately and release PTT. */
        const val ACTION_HALT_TX = "com.js8call.example.ACTION_HALT_TX"
        const val ACTION_ENGINE_STATE = "com.js8call.example.ACTION_ENGINE_STATE"
        const val ACTION_DECODE = "com.js8call.example.ACTION_DECODE"
        const val ACTION_SPECTRUM = "com.js8call.example.ACTION_SPECTRUM"
        const val ACTION_DECODE_STARTED = "com.js8call.example.ACTION_DECODE_STARTED"
        const val ACTION_DECODE_FINISHED = "com.js8call.example.ACTION_DECODE_FINISHED"
        const val ACTION_AUDIO_DEVICE = "com.js8call.example.ACTION_AUDIO_DEVICE"
        const val ACTION_AUDIO_DEVICE_LOST = "com.js8call.example.ACTION_AUDIO_DEVICE_LOST"
        const val ACTION_ERROR = "com.js8call.example.ACTION_ERROR"
        /** With ACTION_WARNING: a brief notice (toast) instead of a dialog. */
        const val EXTRA_WARNING_QUIET = "warning_quiet"
        /** Something the operator must know while the engine keeps running. */
        const val ACTION_WARNING = "com.js8call.example.ACTION_WARNING"
        const val ACTION_TRANSMIT_MESSAGE = "com.js8call.example.ACTION_TRANSMIT_MESSAGE"
        const val ACTION_TX_STATE = "com.js8call.example.ACTION_TX_STATE"
        const val ACTION_TX_SENT = "com.js8call.example.ACTION_TX_SENT"
        /** Where the send in progress has got, frame by frame (see [TxProgress]). */
        /** Added to a MSG whose checksum didn't match (no frames known missing). */
        private const val MSG_CHECKSUM_FAILED_NOTE = "[… checksum didn't match: some text may be wrong …]"
        const val ACTION_TX_PROGRESS = "com.js8call.example.ACTION_TX_PROGRESS"
        const val EXTRA_TX_FRAME_TEXTS = "tx_frame_texts"
        const val EXTRA_TX_FRAMES_STARTED = "tx_frames_started"
        const val EXTRA_TX_FRAME_ON_AIR = "tx_frame_on_air"
        const val EXTRA_TX_STOPPED = "tx_stopped"
        const val ACTION_RADIO_FREQUENCY = "com.js8call.example.ACTION_RADIO_FREQUENCY"
        const val ACTION_MESSAGE_RECEIVED = "com.js8call.example.ACTION_MESSAGE_RECEIVED"
        const val ACTION_QUEUE_TX = "com.js8call.example.ACTION_QUEUE_TX"
        const val ACTION_TIME_SYNC_ONCE = "com.js8call.example.ACTION_TIME_SYNC_ONCE"
        const val ACTION_SET_TIME_DRIFT = "com.js8call.example.ACTION_SET_TIME_DRIFT"
        const val ACTION_TIME_DRIFT = "com.js8call.example.ACTION_TIME_DRIFT"
        const val ACTION_RIG_STATUS = "com.js8call.example.ACTION_RIG_STATUS"
        const val ACTION_DEBUG_INJECT_DECODE = "com.js8call.example.ACTION_DEBUG_INJECT_DECODE"

        // Engine states
        const val STATE_STOPPED = "stopped"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_ERROR = "error"

        // Extras
        const val EXTRA_STATE = "state"
        const val EXTRA_RIG_CONNECTED = "rig_connected"
        const val EXTRA_UTC = "utc"
        const val EXTRA_SNR = "snr"
        const val EXTRA_DT = "dt"
        const val EXTRA_FREQ = "freq"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TYPE = "type"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DRIFT_MS = "drift_ms"
        const val EXTRA_TIME_DRIFT_MS = "time_drift_ms"
        const val EXTRA_BINS = "bins"
        const val EXTRA_BIN_HZ = "bin_hz"
        const val EXTRA_POWER_DB = "power_db"
        const val EXTRA_PEAK_DB = "peak_db"
        const val EXTRA_SUBMODES = "submodes"
        const val EXTRA_COUNT = "count"
        const val EXTRA_AUDIO_DEVICE = "audio_device"
        const val EXTRA_AUDIO_DEVICE_ID = "audio_device_id"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_FREQUENCY_HZ = "frequency_hz"
        const val EXTRA_TX_TEXT = "tx_text"
        const val EXTRA_TX_DIRECTED = "tx_directed"
        const val EXTRA_TX_SUBMODE = "tx_submode"
        const val EXTRA_TX_FREQ_HZ = "tx_freq_hz"
        const val EXTRA_TX_OFFSET_HZ = "tx_offset_hz"
        const val EXTRA_TX_DELAY_S = "tx_delay_s"
        const val EXTRA_TX_FORCE_IDENTIFY = "tx_force_identify"
        const val EXTRA_TX_FORCE_DATA = "tx_force_data"
        /** The send is an automatic reply (queued by the engine, not the operator). */
        const val EXTRA_TX_AUTOMATIC = "tx_automatic"
        const val EXTRA_TX_STATE = "tx_state"
        const val EXTRA_TX_REFUSED = "tx_refused"
        const val EXTRA_TX_ON_AIR_HZ = "tx_on_air_hz"
        const val EXTRA_TX_ON_AIR_BW_HZ = "tx_on_air_bw_hz"
        const val EXTRA_TX_SENT_TEXT = "tx_sent_text"
        const val EXTRA_TX_SENT_FREQ = "tx_sent_freq"
        const val EXTRA_RADIO_FREQUENCY_HZ = "radio_frequency_hz"
        const val EXTRA_MESSAGE_FROM = "message_from"
        const val EXTRA_MESSAGE_TEXT = "message_text"
        const val EXTRA_MESSAGE_SNR = "message_snr"
        const val EXTRA_MESSAGE_FREQ = "message_freq"
        const val EXTRA_MESSAGE_RELAY_PATH = "message_relay_path"
        const val EXTRA_MESSAGE_CONVERSATION_ID = "message_conversation_id"
        const val EXTRA_QUEUE_TX_TEXT = "queue_tx_text"
        const val EXTRA_QUEUE_TX_DIRECTED = "queue_tx_directed"
        const val EXTRA_QUEUE_TX_SUBMODE = "com.js8call.example.QUEUE_TX_SUBMODE"
        const val EXTRA_QUEUE_TX_PRIORITY = "queue_tx_priority"
        const val PREF_TRANSMIT_MODE = "transmit_mode"
        const val PREF_HEARTBEAT_INTERVAL = "heartbeat_interval"
        const val PREF_TIME_SYNC_AUTO = "time_sync_auto"
        const val PREF_TIME_DRIFT_MS = "time_drift_ms"
        const val PREF_RTLSDR_DIRECT_SAMPLING = "rtlsdr_direct_sampling"
        const val PREF_RTLSDR_PPM = "rtlsdr_ppm"
        const val PREF_RTLSDR_GAIN = "rtlsdr_gain"
        const val PREF_LOG_MESSAGES = "log_messages_to_file"
        const val RIG_MODE_USB = "USB"
        const val RIG_MODE_PKTUSB = "PKTUSB"

        const val TX_STATE_QUEUED = "queued"
        const val TX_STATE_STARTED = "started"
        const val TX_STATE_FINISHED = "finished"
        const val TX_STATE_FAILED = "failed"
        const val TX_STATE_HANDED_BACK = "handed_back"

        const val DEFAULT_AUDIO_FREQUENCY_HZ = 1500.0
        const val TRUSDX_RX_SAMPLE_RATE_HZ = 7812
        const val TRUSDX_TX_SAMPLE_RATE_HZ = 11520
        const val TRUSDX_AUDIO_SERIAL_ID = -2001
        const val TRUSDX_AUDIO_SPEAKER_ID = -2002
        const val TRUSDX_AUDIO_SERIAL_NAME = "TruSDX Serial"
        const val TRUSDX_AUDIO_SPEAKER_NAME = "TruSDX Speaker"
        const val UVPRO_AUDIO_ID = -2003
        const val RTLSDR_AUDIO_ID = -2004
        const val UVPRO_AUDIO_NAME = "UV-Pro Bluetooth"
        private const val TRUSDX_RX_FRAME_QUEUE_MAX = 512
        private const val TRUSDX_RX_WATCHDOG_INTERVAL_MS = 1200L
        private const val TRUSDX_RX_STALL_REARM_NS = 2_000_000_000L
        private const val TRUSDX_RX_REARM_COOLDOWN_NS = 2_500_000_000L
        private const val TRUSDX_RX_KEEPALIVE_INTERVAL_MS = 2000L
        private const val PTT_LEAD_TIME_MS = 500
        private const val PTT_COMMAND_RETRIES = 1
        /** Longest wait for a timed-out rig open to finish before reopening the serial port. */
        private const val PENDING_RIG_OPEN_WAIT_MS = 30_000L
        /** Failed releases in a row before the "radio may still be transmitting" warning. */
        private const val PTT_RELEASE_WARN_AFTER = 3
        private const val NOTIFICATION_ID_PTT_STUCK = 992
        private const val TX_PREKEY_MONITOR_INTERVAL_MS = 25L
        /** Longest the radio may sit keyed with audio held back before the send is abandoned. */
        private const val PTT_HOLD_LIMIT_MS = 4_000L
        /** How long to wait for a dropped radio sound card to come back before giving up. */
        private const val RECONNECT_WINDOW_MS = 10 * 60_000L
        /** Pause after it reappears, so the USB device (and its serial port) finish enumerating. */
        private const val RECONNECT_SETTLE_MS = 3_000L
        /** Sends wait this long after an automatic restart. */
        private const val RECONNECT_TX_HOLD_MS = 30_000L
        private const val TX_MONITOR_INTERVAL_MS = 250L
        private const val SCO_START_WAIT_INTERVAL_MS = 200L
        private const val SCO_START_MAX_ATTEMPTS = 10
        private const val SCO_SILENCE_CHECK_DELAY_MS = 2000L
        private const val SCO_SILENCE_THRESHOLD = 5
        private const val SCO_MAX_RESTARTS = 3

        private const val CHANNEL_ID = "js8call_service"
        private const val CHANNEL_ID_MESSAGES = "js8call_messages"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_ID_MESSAGE_BASE = 1000
        private const val NOTIFICATION_ID_NET = 990
        private const val NOTIFICATION_ID_RECEIVER_RESTART = 990
        /** The user had the receiver on (set by Start, cleared by Stop). */
        const val PREF_RECEIVER_WANTED = "receiver_wanted"
        /** Keep the receiver running when the app is swiped away from recent apps (default on). */
        const val PREF_KEEP_RECEIVING = "keep_receiving_when_closed"
    }
}
