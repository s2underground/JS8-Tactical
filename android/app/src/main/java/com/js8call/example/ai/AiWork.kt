package com.js8call.example.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.js8call.example.digest.DigestBuilder
import com.js8call.llm.LlamaBridge
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the on-device model one job at a time on a background thread: a one-line summary for
 * each bulletin as it is heard, and the Digest overview on request. The model is loaded for a
 * batch of work and unloaded after two idle minutes so it never sits on ~1 GB of memory that the
 * receiver may need.
 */
object AiWork {

    private const val TAG = "AiWork"
    private const val IDLE_UNLOAD_S = 120L
    private const val CACHE_FILE = "ai_bulletins.txt"
    private const val CACHE_MAX = 400

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ai-work").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val main = Handler(Looper.getMainLooper())
    private var session: LlamaBridge.Session? = null
    private var sessionModel: String? = null
    private var unloadTask: ScheduledFuture<*>? = null
    private val queued = HashSet<String>()
    private var cache: LinkedHashMap<String, String>? = null

    private val _bulletins = MutableLiveData<Map<String, String>>(emptyMap())
    /** Bulletin key ([AiPrompt.key]) → one-line summary. */
    val bulletins: LiveData<Map<String, String>> = _bulletins

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    /** AI on, model downloaded, engine present. */
    fun usable(ctx: Context): Boolean = AiModels.enabled(ctx) && LlamaBridge.available && model(ctx) != null

    fun model(ctx: Context): AiModels.Model? {
        val id = PreferenceManager.getDefaultSharedPreferences(ctx).getString(AiModels.PREF_MODEL, null)
        return AiModels.byId(id)?.takeIf { AiModels.ready(ctx, it) } ?: AiModels.ALL.firstOrNull { AiModels.ready(ctx, it) }
    }

    /** The one-line summary, or null (none yet, or the model only copied the bulletin). */
    fun summary(ctx: Context, body: String): String? =
        cache(ctx)[keyOf(body)]?.takeIf { it != FAILED }
            ?.let { AiPrompt.firstSentences(it) }
            ?.takeIf { AiPrompt.unverifiedNumbers(it, body).isEmpty() }

    /** Same text from different senders (relays) shares one summary. */
    private fun keyOf(body: String) = AiPrompt.key(AiPrompt.bulletinBody(body))

    private const val FAILED = "\u0000"

    /** Queue a one-line summary for every bulletin in [messages] that doesn't have one yet. */
    fun summariseBulletins(ctx: Context, messages: List<DigestBuilder.Msg>) {
        if (!usable(ctx)) return
        val app = ctx.applicationContext
        val todo = messages.filter { DigestBuilder.isBulletin(it) }
            .sortedByDescending { it.timeMs }
            .map { it.body.trim() }
            .filter { b -> val k = keyOf(b); k !in cache(app) && synchronized(queued) { queued.add(k) } }
        todo.forEach { body ->
            worker.execute {
                val key = keyOf(body)
                try {
                    val s = acquire(app) ?: return@execute
                    setBusy(true)
                    val raw = s.generate(AiPrompt.BULLETIN_SYSTEM, AiPrompt.bulletinUser(body), 60, 0f)
                    val line = AiPrompt.completeSentences(AiPrompt.firstSentences(AiPrompt.clean(raw, oneLine = true)))
                    val bad = AiPrompt.unverifiedNumbers(line, body)
                    if (bad.isNotEmpty()) Log.w(TAG, "bulletin summary dropped: numbers not in the bulletin: $bad")
                    // A copy, or a summary with numbers the bulletin doesn't contain, is not shown; remembered so it isn't retried every minute.
                    store(app, key, if (line.isBlank() || AiPrompt.isEcho(line, body) || bad.isNotEmpty()) FAILED else line)
                    Log.i(TAG, "bulletin summarised in ${s.stats().let { it.promptMs + it.genMs }} ms")
                } catch (t: Throwable) {
                    Log.e(TAG, "bulletin summary failed", t)
                } finally {
                    synchronized(queued) { queued.remove(key) }
                    idle()
                }
            }
        }
    }

    /**
     * The news lead for the roll-up from [userText] ([AiPrompt.leadUser]); [onText] gets the text so
     * far as it is written, [onDone] the final text or an error.
     */
    fun overview(ctx: Context, userText: String, onText: (String) -> Unit, onDone: (String?, String?) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            try {
                val s = acquire(app) ?: run { main.post { onDone(null, "The model could not be loaded.") }; return@execute }
                setBusy(true)
                val sb = StringBuilder()
                val raw = s.generate(AiPrompt.LEAD_SYSTEM, userText, 120, 0f) { piece ->
                    sb.append(piece)
                    val so = AiPrompt.clean(sb.toString())
                    main.post { onText(so) }
                    // Stop early once it is clearly copying its input out.
                    !(so.split(' ').size >= 30 && AiPrompt.isLeadEcho(so, userText))
                }
                val err = s.error().takeIf { it.isNotBlank() && raw.isBlank() }
                val st = s.stats()
                Log.i(TAG, "lead: ${st.promptTokens} tokens in ${st.promptMs} ms, ${st.genTokens} out in ${st.genMs} ms")
                val text = AiPrompt.clean(raw).let { AiPrompt.completeSentences(it) }.ifBlank { null }
                val wrongNumbers = text?.let { AiPrompt.unverifiedNumbers(it, userText) }.orEmpty()
                when {
                    text != null && AiPrompt.isLeadEcho(text, userText) -> main.post { onDone(null, "the model copied out its notes instead of writing a summary" +
                        if (model(app)?.id == "qwen2.5-0.5b") " (the Small model often does; try Standard)" else ". Try Update.") }
                    wrongNumbers.isNotEmpty() -> main.post { onDone(null, "the summary had figures not in the messages (${wrongNumbers.joinToString()}), so it isn't shown. Try Update.") }
                    else -> main.post { onDone(text, err) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "lead failed", t)
                main.post { onDone(null, t.message ?: t.javaClass.simpleName) }
            } finally {
                idle()
            }
        }
    }

    /** Run [block] on the AI thread with no model of ours loaded (the speed test loads its own). */
    fun exclusive(block: () -> Unit) {
        worker.execute {
            unloadTask?.cancel(false)
            session?.close(); session = null; sessionModel = null
            try { block() } catch (t: Throwable) { Log.e(TAG, "exclusive job failed", t) }
        }
    }

    /** Drop the loaded model now (e.g. it was deleted or AI was turned off). */
    fun release() {
        worker.execute { session?.close(); session = null; sessionModel = null }
    }

    // ---------------------------------------------------------------- internals (worker thread)

    private fun acquire(ctx: Context): LlamaBridge.Session? {
        unloadTask?.cancel(false)
        if (!usable(ctx)) return null
        val m = model(ctx) ?: return null
        if (session != null && sessionModel == m.id) return session
        session?.close()
        val threads = minOf(4, Runtime.getRuntime().availableProcessors())
        session = LlamaBridge.load(AiModels.file(ctx, m).absolutePath, 4096, threads)
        sessionModel = if (session != null) m.id else null
        Log.i(TAG, "model ${m.id} ${if (session != null) "loaded" else "failed to load"}")
        return session
    }

    private fun idle() {
        setBusy(false)
        unloadTask?.cancel(false)
        unloadTask = worker.schedule({
            session?.close(); session = null; sessionModel = null
            Log.i(TAG, "model unloaded after idle")
        }, IDLE_UNLOAD_S, TimeUnit.SECONDS)
    }

    private fun setBusy(b: Boolean) = _busy.postValue(b)

    @Synchronized
    private fun cache(ctx: Context): LinkedHashMap<String, String> {
        cache?.let { return it }
        val map = LinkedHashMap<String, String>()
        runCatching {
            File(ctx.filesDir, CACHE_FILE).takeIf { it.exists() }?.readLines()?.forEach { l ->
                val i = l.indexOf('\t')
                if (i > 0) map[l.substring(0, i)] = l.substring(i + 1)
            }
        }
        cache = map
        _bulletins.postValue(HashMap(map))
        return map
    }

    @Synchronized
    private fun store(ctx: Context, key: String, line: String) {
        val map = cache(ctx)
        map[key] = line.replace('\t', ' ').replace('\n', ' ')
        while (map.size > CACHE_MAX) map.remove(map.keys.first())
        runCatching { File(ctx.filesDir, CACHE_FILE).writeText(map.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
        _bulletins.postValue(HashMap(map))
    }
}
