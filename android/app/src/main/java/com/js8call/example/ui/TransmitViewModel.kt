package com.js8call.example.ui

import android.app.Application
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.js8call.example.AppBroadcasts
import com.js8call.example.MainActivity
import com.js8call.example.data.MessageEntity
import com.js8call.example.data.MessageRepository
import com.js8call.example.model.TransmitMessage
import com.js8call.example.model.TransmitState
import com.js8call.example.service.JS8EngineService
import com.js8call.example.util.CallsignValidator
import com.js8call.example.util.Js8Commands
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The send queue and transmit state, one for the whole app ([of]).
 *
 * It lives as long as the process and listens to the engine service itself, so
 * queued messages and automatic replies keep going out while the app is in the
 * background or the screen is locked (the foreground service keeps the process
 * alive). It used to depend on MainActivity relaying the service's broadcasts,
 * which stopped whenever the activity was not on screen: auto-replies queued
 * then were dropped, and queued sends waited until the app was opened again.
 */
class TransmitViewModel internal constructor(application: Application) : AndroidViewModel(application) {

    private val serviceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                JS8EngineService.ACTION_QUEUE_TX -> {
                    val text = intent.getStringExtra(JS8EngineService.EXTRA_QUEUE_TX_TEXT) ?: return
                    val directed = intent.getStringExtra(JS8EngineService.EXTRA_QUEUE_TX_DIRECTED)
                    val priority = intent.getIntExtra(JS8EngineService.EXTRA_QUEUE_TX_PRIORITY, 0)
                    val submode = if (intent.hasExtra(JS8EngineService.EXTRA_QUEUE_TX_SUBMODE)) intent.getIntExtra(JS8EngineService.EXTRA_QUEUE_TX_SUBMODE, 0) else null
                    queueMessage(text, directed, priority, submode = submode)
                    processNext()
                }
                JS8EngineService.ACTION_TX_STATE -> onTxState(intent)
                MainActivity.ACTION_PROCESS_TX_QUEUE -> processNext()
                JS8EngineService.ACTION_ENGINE_STATE -> when (intent.getStringExtra(JS8EngineService.EXTRA_STATE)) {
                    JS8EngineService.STATE_RUNNING -> processNext()
                    JS8EngineService.STATE_STOPPED, JS8EngineService.STATE_ERROR -> engineStopped()
                }
            }
        }
    }

    init {
        // runCatching: plain JVM unit tests have no main looper.
        runCatching { listen(application) }
    }

    private fun listen(application: Application) {
        AppBroadcasts.register(
            application,
            serviceReceiver,
            android.content.IntentFilter().apply {
                addAction(JS8EngineService.ACTION_QUEUE_TX)
                addAction(JS8EngineService.ACTION_TX_STATE)
                addAction(JS8EngineService.ACTION_ENGINE_STATE)
                addAction(MainActivity.ACTION_PROCESS_TX_QUEUE)
            },
        )
    }

    private fun onTxState(intent: Intent) {
        val state = intent.getStringExtra(JS8EngineService.EXTRA_TX_STATE)
        if (state == JS8EngineService.TX_STATE_STARTED) {
            setOnAir(
                intent.getFloatExtra(JS8EngineService.EXTRA_TX_ON_AIR_HZ, -1f).takeIf { it >= 0f },
                intent.getFloatExtra(JS8EngineService.EXTRA_TX_ON_AIR_BW_HZ, 50f),
            )
        } else if (state != JS8EngineService.TX_STATE_QUEUED) {
            setOnAir(null)
        }
        when (state) {
            JS8EngineService.TX_STATE_FINISHED -> {
                transmissionComplete()
                processNext()
            }
            JS8EngineService.TX_STATE_FAILED -> {
                val refused = intent.getBooleanExtra(JS8EngineService.EXTRA_TX_REFUSED, false)
                if (transmissionFailed(refused)) processNext()
            }
            JS8EngineService.TX_STATE_HANDED_BACK -> handedBack()
            JS8EngineService.TX_STATE_QUEUED -> setQueued()
            JS8EngineService.TX_STATE_STARTED -> startTransmitting()
        }
    }

    /** Hands the next queued message to the engine when it is running and nothing is in flight. */
    fun processNext() {
        // Keep queued entries until the engine can accept them; otherwise the
        // service reports an error without a TX completion event.
        if (!JS8EngineService.engineRunning) return
        if (_txState.value == TransmitState.TRANSMITTING || activeMessage != null) return
        // With the Net Switcher on, a message waits for the net it was queued on.
        val dial = currentDialHz()
        // Automatic replies (heartbeat/SNR/ACK) wait while a long message is coming in: keying up
        // would lose its frames (11-21: an HB reply cut a frame out of a 27-frame report). Stale
        // ones are dropped. Messages the operator sends go when asked.
        val now = System.currentTimeMillis()
        val stale = txQueue.filter { it.priority > 0 && now - it.timestamp > AUTO_STALE_MS && it !== activeMessage }
        if (stale.isNotEmpty()) {
            txQueue.removeAll(stale.toSet())
            stale.forEach { Log.i(TAG, "Dropped stale automatic reply: '${it.text}'") }
            publishQueue()
        }
        val receiving = txQueue.any { it.priority > 0 } && runCatching {
            com.js8call.example.ui.traffic.TrafficViewModel.of(getApplication()).messageInProgress(now)
        }.getOrDefault(false)
        val pick = TxQueueRules.pickNext(txQueue, autoAheadOfOperator) {
            (it.dialHz == null || dial == null || com.js8call.example.nets.NetSwitcher.sameDial(it.dialHz, dial)) && !(receiving && it.priority > 0)
        }
        if (receiving) retrySoon()
        pick ?: return
        val next = pick.message
        if (next.priority > 0 && autoReplySafeguard() && !autoReplyBudget.allows(now)) {
            // Other stations have had their share: this automatic reply is dropped, not delayed
            // (an answer minutes late is no answer), and the operator is told once per window.
            txQueue.remove(next)
            publishQueue()
            Log.i(TAG, "Auto-reply safeguard: dropped '${next.text}'")
            if (now - lastSafeguardNoticeMs >= AutoReplyBudget.DEFAULT_WINDOW_MS) {
                lastSafeguardNoticeMs = now
                AppBroadcasts.send(getApplication(), Intent(JS8EngineService.ACTION_ERROR).putExtra(
                    JS8EngineService.EXTRA_ERROR_MESSAGE,
                    getApplication<Application>().getString(com.js8call.example.R.string.auto_reply_safeguard_paused),
                ))
            }
            processNext()
            return
        }
        if (next.priority > 0) autoReplyBudget.record(now)
        autoAheadOfOperator = pick.autoAheadOfOperator
        // Keep this entry tied to the eventual TX result even if a higher-priority one is queued meanwhile.
        beginTransmission(next)
        Log.d(TAG, "Sending to service: text='${next.text}' directed=${next.directed}")
        val app = getApplication<Application>()
        val tx = Intent(app, JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_TRANSMIT_MESSAGE
            putExtra(JS8EngineService.EXTRA_TX_TEXT, next.text)
            next.directed?.let { putExtra(JS8EngineService.EXTRA_TX_DIRECTED, it) }
            next.submode?.let { putExtra(JS8EngineService.EXTRA_TX_SUBMODE, it) }
            putExtra(JS8EngineService.EXTRA_TX_AUTOMATIC, next.priority > 0)
            putExtra(JS8EngineService.EXTRA_TX_FREQ_HZ, com.js8call.example.service.TxOffsets.txOffset(app).toDouble())
        }
        runCatching { app.startService(tx) }.onFailure { e ->
            // Background start limits: the service is normally already running in the foreground.
            Log.e(TAG, "Couldn't hand the message to the service", e)
            activeMessage = null
            settleState()
        }
    }

    private val retryHandler by lazy { runCatching { android.os.Handler(android.os.Looper.getMainLooper()) }.getOrNull() }
    private val retry = Runnable { processNext() }

    /** Look at the queue again shortly (an automatic reply is waiting for a message to finish). */
    private fun retrySoon() {
        retryHandler?.let { h -> h.removeCallbacks(retry); h.postDelayed(retry, 5_000L) }
    }

    private fun currentDialHz(): Long? = runCatching {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(getApplication())
            .getString("last_frequency", "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    /** A queue entry as the queue sheet shows it. */
    data class QueueItem(val message: TransmitMessage, val sending: Boolean)

    private val _txState = MutableLiveData<TransmitState>(TransmitState.IDLE)
    val txState: LiveData<TransmitState> = _txState

    /** Offset and width of the transmission on the air right now (null when not sending). */
    private val _onAir = MutableLiveData<Pair<Float, Float>?>(null)
    val onAir: LiveData<Pair<Float, Float>?> = _onAir
    fun setOnAir(hz: Float?, bandwidthHz: Float = 50f) {
        val v = hz?.let { it to bandwidthHz }
        if (_onAir.value != v) _onAir.value = v
    }

    private val _queue = MutableLiveData<List<QueueItem>>(emptyList())
    val queue: LiveData<List<QueueItem>> = _queue

    private val txQueue = mutableListOf<TransmitMessage>()
    private var activeMessage: TransmitMessage? = null
    private var activeOnAir = false
    // Failed thread sends by message id, kept for Retry
    private val failedMessages = mutableMapOf<Long, TransmitMessage>()
    private val sendOrder = Mutex()
    /** Automatic replies sent in a row while an operator message waited (see [TxQueueRules]). */
    private var autoAheadOfOperator = 0
    private val autoReplyBudget = AutoReplyBudget()
    private var lastSafeguardNoticeMs = 0L
    private fun autoReplySafeguard(): Boolean = runCatching {
        PreferenceManager.getDefaultSharedPreferences(getApplication()).getBoolean(PREF_AUTO_REPLY_SAFEGUARD, true)
    }.getOrDefault(true)
    private val repository by lazy { MessageRepository.getInstance(getApplication()) }

    /**
     * Queue a message for transmission.
     */
    fun queueMessage(text: String, directed: String? = null, priority: Int = 0, messageId: Long? = null, submode: Int? = null) {
        if (text.isBlank()) return

        enqueue(
            TransmitMessage(
                text = text.trim(),
                directed = directed?.takeIf { it.isNotBlank() },
                priority = priority,
                messageId = messageId,
                submode = submode,
                dialHz = if (runCatching { com.js8call.example.nets.NetStore.of(getApplication()).enabled }.getOrDefault(false)) currentDialHz() else null,
            )
        )
    }

    private fun enqueue(message: TransmitMessage) {
        if (message.priority > 0 && autoReplySafeguard() && TxQueueRules.isDuplicateAuto(txQueue.filter { it !== activeMessage }, message)) {
            Log.i(TAG, "Dropped a duplicate automatic reply: '${message.text}'")
            return
        }
        txQueue.add(message)
        txQueue.sortByDescending { it.priority }
        if (_txState.value == TransmitState.IDLE) _txState.value = TransmitState.QUEUED
        publishQueue()
    }

    /** Queue a typed broadcast, recording direct MSG commands. */
    fun queueManualMessage(text: String, submode: Int? = null) {
        val upper = text.trim().uppercase(Locale.ROOT)
        saveAndQueue(upper, null, outgoingSavedMessage(upper, null), submode)
    }

    /** Queue a JS8 relay: sent to [relay], who passes it on to [target]. Saved in [target]'s thread. */
    fun queueRelayMessage(relay: String, target: String, text: String) {
        val wire = com.js8call.example.ui.traffic.RelayFinder.relayText(relay, target, text)
        saveAndQueue(wire, null, target.uppercase(Locale.ROOT) to "via ${relay.uppercase(Locale.ROOT)}: ${text.trim().uppercase(Locale.ROOT)}")
    }

    /** Queue a send typed in [callsign]'s thread, saving it there first. */
    fun queueThreadMessage(callsign: String, text: String, submode: Int? = null) {
        val addressed = threadText(callsign, text)
        saveAndQueue(addressed, callsign, callsign to threadBubbleText(callsign, addressed), submode)
    }

    // One at a time, so a send waiting on its insert is not overtaken
    private fun saveAndQueue(text: String, directed: String?, saved: Pair<String, String>?, submode: Int? = null) {
        viewModelScope.launch {
            sendOrder.withLock {
                val id = saved?.let { (to, body) -> insertOutgoing(to, body) }
                queueMessage(text, directed, messageId = id, submode = submode)
            }
            processQueue()
        }
    }

    private suspend fun insertOutgoing(to: String, body: String): Long? = try {
        repository.insertOutgoingMessage(to, body)
    } catch (e: SQLiteException) {
        // Still send it; it just won't show in the thread
        Log.e(TAG, "Couldn't save outgoing message", e)
        null
    }

    private fun processQueue() = processNext()

    private fun updateStatus(message: TransmitMessage?, status: Int) {
        message?.messageId?.let { id ->
            viewModelScope.launch { repository.updateMessageStatus(id, status) }
        }
    }

    private fun publishQueue() {
        _queue.value = txQueue.map { QueueItem(it, it === activeMessage) }
    }

    // A heartbeat the service started on its own can be on the air
    private fun settleUnlessOnAir() {
        if (_txState.value == TransmitState.TRANSMITTING) publishQueue() else settleState()
    }

    private fun settleState() {
        _txState.value = if (txQueue.isEmpty()) TransmitState.IDLE else TransmitState.QUEUED
        // Nothing on the air any more (covers a missed "finished" while in the background).
        setOnAir(null)
        publishQueue()
    }

    /** Drops a waiting send and its bubble. The one handed to the engine stays. */
    fun cancel(message: TransmitMessage) {
        if (message === activeMessage || !txQueue.removeAll { it === message }) return
        message.messageId?.let { deleteBubble(it) }
        if (activeMessage == null) settleUnlessOnAir() else publishQueue()
    }

    fun cancelAll() {
        txQueue.filter { it !== activeMessage }.forEach { cancel(it) }
    }

    /** Drops a thread send by message id; false when it is already with the engine. */
    fun discard(messageId: Long): Boolean {
        if (activeMessage?.messageId == messageId) return false
        failedMessages.remove(messageId)
        val queued = txQueue.firstOrNull { it.messageId == messageId }
        if (queued != null) cancel(queued) else deleteBubble(messageId)
        return true
    }

    private fun deleteBubble(messageId: Long) {
        viewModelScope.launch { repository.deleteMessage(messageId) }
    }

    fun canRetry(messageId: Long): Boolean = messageId in failedMessages

    fun retry(messageId: Long) {
        if (messageId !in failedMessages) return
        viewModelScope.launch {
            sendOrder.withLock {
                repository.updateMessageStatus(messageId, MessageEntity.STATUS_PENDING)
                // Deleted or already retried while the status was saving
                val message = failedMessages.remove(messageId) ?: return@withLock
                enqueue(message.copy(timestamp = System.currentTimeMillis()))
            }
            processQueue()
        }
    }

    fun beginTransmission(message: TransmitMessage) {
        activeMessage = message
        activeOnAir = false
        publishQueue()
    }

    fun hasActiveTransmission(): Boolean = activeMessage != null

    /**
     * Start transmitting (called when engine starts TX).
     */
    fun startTransmitting() {
        if (activeMessage != null) activeOnAir = true
        _txState.value = TransmitState.TRANSMITTING
    }

    fun setQueued() {
        _txState.value = TransmitState.QUEUED
    }

    /**
     * Transmission complete (called when engine finishes TX).
     */
    fun transmissionComplete() {
        val completed = activeMessage
        updateStatus(completed, MessageEntity.STATUS_SENT)
        activeMessage = null
        if (completed != null) txQueue.removeAll { it === completed }
        settleState()
    }

    /** Drops a send cut off on the air or [refused]. Any other stays at the head and returns false. */
    fun transmissionFailed(refused: Boolean = false): Boolean {
        val failed = activeMessage
        activeMessage = null
        if (failed != null && !activeOnAir && !refused) {
            settleState()
            return false
        }
        updateStatus(failed, MessageEntity.STATUS_FAILED)
        if (failed != null) {
            txQueue.removeAll { it === failed }
            failed.messageId?.let { failedMessages[it] = failed }
        }
        settleState()
        return true
    }

    fun handedBack() {
        activeMessage = null
        settleUnlessOnAir()
    }

    /** Monitoring stopped. A send that never reached the air waits for the next start. */
    fun engineStopped() {
        transmissionFailed()
    }

    /**
     * Get next message to transmit.
     */
    fun getNextMessage(): TransmitMessage? {
        return txQueue.firstOrNull()
    }

    companion object {
        private const val TAG = "TransmitViewModel"
        /** Settings: the auto-reply safeguard (see [AutoReplyBudget]); on unless turned off. */
        const val PREF_AUTO_REPLY_SAFEGUARD = "auto_reply_safeguard"
        /** An automatic reply still waiting after this long is no use; drop it. */
        private const val AUTO_STALE_MS = 10 * 60_000L

        @Volatile private var instance: TransmitViewModel? = null

        /** The one send queue for the whole app. Call on the main thread. */
        fun of(context: android.content.Context): TransmitViewModel =
            instance ?: synchronized(this) {
                instance ?: TransmitViewModel(context.applicationContext as Application).also { instance = it }
            }
    }
}

/** What goes on the air for [text] typed in [callsign]'s thread. */
internal fun threadText(callsign: String, text: String): String {
    // The engine matches the selected call case-sensitively
    val trimmed = text.trim().uppercase(Locale.ROOT)
    // Raw text and text already addressed to the station get no prefix
    if (trimmed.startsWith("`")) return trimmed
    if (trimmed.substringBefore(' ') == callsign.uppercase(Locale.ROOT)) return trimmed
    // The engine drops the selected call before CQ, HB or another callsign.
    return "$callsign $trimmed"
}

/** What [callsign]'s thread shows for [addressed], the text [threadText] sends. */
internal fun threadBubbleText(callsign: String, addressed: String): String {
    outgoingSavedMessage(addressed, callsign)?.let { return it.second }
    val prefix = "$callsign "
    return if (addressed.startsWith(prefix, ignoreCase = true)) addressed.drop(prefix.length) else addressed
}

private val whitespace = Regex("\\s+")
private val callsignAddress = Regex("[A-Z0-9/]*[0-9][A-Z0-9/]*", RegexOption.IGNORE_CASE)

/** Returns the destination and body for a direct saved MSG, if recognizable. */
internal fun outgoingSavedMessage(text: String, directed: String?): Pair<String, String>? {
    val trimmed = text.trim()
    val directCommand = Js8Commands.matchAt(trimmed)
    if (directCommand != null) {
        if (directCommand.command != Js8Commands.CMD_MSG || directCommand.payload.isBlank()) return null
        val target = directed?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return target to directCommand.payload
    }
    // Explicitly addressed messages take precedence over the selected destination.
    val parts = trimmed.split(whitespace, limit = 2)
    if (parts.size != 2) return null
    val address = parts[0]
    if (!CallsignValidator.isGroup(address) &&
        !callsignAddress.matches(address)
    ) return null
    val command = Js8Commands.matchAt(parts[1]) ?: return null
    if (command.command != Js8Commands.CMD_MSG || command.payload.isBlank()) return null
    return address to command.payload
}
