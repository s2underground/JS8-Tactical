package com.js8call.example.ai

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.digest.DigestBuilder
import com.js8call.example.ui.traffic.TrafficViewModel
import com.js8call.llm.LlamaBridge
import java.util.Locale

/**
 * AI summary (experimental): download or delete a model, and time one summary on this device.
 * Nothing here transmits; the model only reads traffic already heard.
 */
class AiFragment : Fragment() {

    private lateinit var body: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { renderModels(); handler.postDelayed(this, 1000) }
    }
    private lateinit var modelsBox: LinearLayout
    private lateinit var output: TextView
    private lateinit var results: TextView
    private lateinit var runButton: MaterialButton
    private var threads = 4
    private var useSample = false
    @Volatile private var running = false
    @Volatile private var stop = false
    private var lastResult = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        threads = minOf(4, Runtime.getRuntime().availableProcessors())
        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        val root = ScrollView(ctx).apply { setBackgroundColor(color(R.color.tac_ground)); addView(body) }

        body.addView(text(getString(R.string.ai_title), 20f, bold = true))
        body.addView(text(getString(R.string.ai_intro), 13.5f, color(R.color.tac_text_dim)))
        body.addView(text(deviceLine(), 12.5f, color(R.color.tac_text_quiet)).apply { setPadding(0, dp(6), 0, 0) })
        if (!LlamaBridge.available) body.addView(text(getString(R.string.ai_no_native), 14f, 0xFFF2A33A.toInt(), bold = true))

        body.addView(section(getString(R.string.ai_models)))
        modelsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(modelsBox)

        body.addView(section(getString(R.string.ai_speed_test)))
        body.addView(text(getString(R.string.ai_speed_explain), 13f, color(R.color.tac_text_dim)))
        body.addView(chipRow(listOf(getString(R.string.ai_input_traffic) to false, getString(R.string.ai_input_sample) to true), useSample) { useSample = it })
        val cores = Runtime.getRuntime().availableProcessors()
        body.addView(chipRow(listOf(2, 4, 6, 8).filter { it <= cores }.map { getString(R.string.ai_threads, it) to it }, threads) { threads = it })
        runButton = MaterialButton(ctx).apply {
            text = getString(R.string.ai_run)
            setOnClickListener { if (running) stop = true else runTest() }
        }
        body.addView(runButton)
        results = text("", 13.5f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        body.addView(results)
        output = text("", 14.5f).apply {
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(color(R.color.tac_panel)) }
            visibility = View.GONE
        }
        body.addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        body.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.ai_copy)
            setOnClickListener {
                val all = AiSpeedLog.text(ctx).ifBlank { lastResult }
                if (all.isBlank()) return@setOnClickListener
                (ctx.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("AI speed test", all))
                Snackbar.make(requireView(), R.string.ai_copied, Snackbar.LENGTH_SHORT).show()
            }
        })
        return root
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll) }
    override fun onDestroyView() { stop = true; super.onDestroyView() }

    // ---------------------------------------------------------------- models

    private fun renderModels() {
        if (view == null) return
        val ctx = requireContext()
        modelsBox.removeAllViews()
        val selected = PreferenceManager.getDefaultSharedPreferences(ctx).getString(AiModels.PREF_MODEL, AiModels.ALL.first().id)
        AiModels.ALL.forEach { m ->
            val state = AiModels.state(ctx, m)
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(color(R.color.tac_panel))
                    setStroke(dp(1), if (m.id == selected && state is AiModels.State.Ready) 0xFF4FC3F7.toInt() else color(R.color.tac_divider))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            card.addView(text(m.name + "  ·  " + mb(m.approxBytes), 15.5f, bold = true))
            card.addView(text(m.note, 12.5f, color(R.color.tac_text_dim)))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
            when (state) {
                is AiModels.State.None, is AiModels.State.Failed -> {
                    if (state is AiModels.State.Failed) card.addView(text(state.reason, 12.5f, 0xFFF2A33A.toInt()))
                    row.addView(button(getString(R.string.ai_download)) { confirmDownload(m) })
                }
                is AiModels.State.Downloading -> {
                    val pct = if (state.total > 0) (state.done * 100 / state.total).toInt() else 0
                    row.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; progress = pct
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(text("  $pct%  ${mb(state.done)}" + if (state.paused) " · " + getString(R.string.ai_paused) else "", 12.5f, color(R.color.tac_text_dim)))
                    row.addView(button(getString(android.R.string.cancel)) { AiModels.cancel(ctx, m); renderModels() })
                }
                is AiModels.State.Ready -> {
                    row.addView(text(if (m.id == selected) getString(R.string.ai_in_use) else getString(R.string.ai_downloaded), 13f, 0xFF5CC98B.toInt(), bold = true),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (m.id != selected) row.addView(button(getString(R.string.ai_use)) {
                        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(AiModels.PREF_MODEL, m.id).apply(); renderModels()
                    })
                    row.addView(button(getString(R.string.ai_delete)) { confirmDelete(m) })
                }
            }
            card.addView(row)
            modelsBox.addView(card)
        }
        modelsBox.addView(text(getString(R.string.ai_storage, mb(AiModels.totalBytesOnDisk(ctx))), 12f, color(R.color.tac_text_quiet)))
    }

    private fun confirmDownload(m: AiModels.Model) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(m.name)
            .setMessage(getString(R.string.ai_download_confirm, mb(m.approxBytes)))
            .setPositiveButton(R.string.ai_download) { _, _ ->
                AiModels.download(requireContext(), m)
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(AiModels.PREF_MODEL, m.id).apply()
                renderModels()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(m: AiModels.Model) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.ai_delete_title, m.name))
            .setMessage(getString(R.string.ai_delete_text, mb(m.approxBytes)))
            .setPositiveButton(R.string.ai_delete) { _, _ -> AiWork.release(); AiModels.delete(requireContext(), m); renderModels() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- speed test

    private fun runTest() {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val model = AiModels.byId(prefs.getString(AiModels.PREF_MODEL, null))?.takeIf { AiModels.ready(ctx, it) }
            ?: AiModels.ALL.firstOrNull { AiModels.ready(ctx, it) }
        if (model == null) {
            Snackbar.make(requireView(), R.string.ai_need_model, Snackbar.LENGTH_LONG).show(); return
        }
        if (!LlamaBridge.available) {
            Snackbar.make(requireView(), R.string.ai_no_native, Snackbar.LENGTH_LONG).show(); return
        }
        val (digestText, msgs) = if (useSample) AiPrompt.SAMPLE else trafficInput()
        val user = AiPrompt.user(digestText, msgs)
        val nThreads = threads
        running = true
        stop = false
        runButton.text = getString(R.string.ai_stop)
        output.visibility = View.VISIBLE
        output.text = getString(R.string.ai_loading)
        results.text = ""
        val mem = memoryLine(ctx)
        AiWork.exclusive {
            val started = System.currentTimeMillis()
            val sb = StringBuilder()
            var report: String
            try {
                val session = LlamaBridge.load(AiModels.file(ctx, model).absolutePath, 4096, nThreads)
                if (session == null) {
                    report = "Model failed to load (${model.name})."
                } else session.use { s ->
                    val loadMs = s.loadMs
                    handler.post { if (view != null) output.text = getString(R.string.ai_reading) }
                    var first = true
                    val text = s.generate(AiPrompt.SYSTEM, user, 220, 0f) { piece ->
                        sb.append(piece)
                        val snapshot = sb.toString()
                        handler.post {
                            if (view != null) output.text = snapshot
                        }
                        if (first) first = false
                        !stop
                    }
                    val st = s.stats()
                    val err = s.error()
                    report = buildString {
                        appendLine("AI speed test · ${model.name} · $nThreads threads · ${if (useSample) "sample traffic" else "your traffic"}")
                        appendLine(deviceLine())
                        appendLine(mem)
                        appendLine(String.format(Locale.US, "Load model     %6.1f s", loadMs / 1000.0))
                        appendLine(String.format(Locale.US, "Read input     %6.1f s  (%d tokens, %.1f tok/s)", st.promptMs / 1000.0, st.promptTokens, st.promptTokPerSec))
                        appendLine(String.format(Locale.US, "Write summary  %6.1f s  (%d tokens, %.1f tok/s)", st.genMs / 1000.0, st.genTokens, st.genTokPerSec))
                        appendLine(String.format(Locale.US, "Total          %6.1f s%s", (System.currentTimeMillis() - started) / 1000.0, if (stop) "  (stopped early)" else ""))
                        if (err.isNotBlank()) appendLine("Error: $err")
                    }.trimEnd()
                    if (text.isBlank() && err.isNotBlank()) sb.append("(no output: $err)")
                }
            } catch (t: Throwable) {
                report = "Speed test failed: ${t.javaClass.simpleName}: ${t.message}"
            }
            val full = report + "\n\nSummary:\n" + sb.toString().trim()
            AiSpeedLog.append(ctx, full)
            handler.post {
                running = false
                lastResult = full
                if (view == null) return@post
                runButton.text = getString(R.string.ai_run)
                results.text = report
                if (sb.isBlank()) output.text = getString(R.string.ai_no_output)
            }
        }
    }

    private fun trafficInput(): Pair<String, List<AiPrompt.Msg>> {
        val traffic = TrafficViewModel.of(requireContext())
        val now = System.currentTimeMillis()
        val input = traffic.digestInput(now - 24 * 3_600_000L, now)
        val digest = DigestBuilder.build(input)
        val msgs = input.messages.filter { it.timeMs >= input.sinceMs }.map { AiPrompt.Msg(it.timeMs, it.sender, it.recipient, it.body) }
        return digest.toText() to msgs
    }

    // ---------------------------------------------------------------- views

    private fun deviceLine(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) " · " + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else ""
        return "${Build.MANUFACTURER} ${Build.MODEL}$soc · ${Runtime.getRuntime().availableProcessors()} cores"
    }

    private fun memoryLine(ctx: android.content.Context): String {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return "Memory: ${mb(mi.availMem)} free of ${mb(mi.totalMem)}"
    }

    private fun mb(bytes: Long) = if (bytes >= 1_000_000_000L) String.format(Locale.US, "%.2f GB", bytes / 1e9) else "${bytes / 1_000_000} MB"

    private fun <T> chipRow(options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit): View {
        val ctx = requireContext()
        val group = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true; isSingleLine = true }
        options.forEach { (label, value) ->
            group.addView(Chip(ctx).apply {
                text = label
                isCheckable = true
                id = View.generateViewId()
                isChecked = value == current
                setOnClickListener { onPick(value) }
            })
        }
        return HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(group) }
    }

    private fun section(s: String) = text(s.uppercase(), 12.5f, color(R.color.tac_text_quiet), bold = true).apply { setPadding(0, dp(18), 0, dp(6)) }

    private fun button(label: String, onClick: () -> Unit) =
        MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            setTextColor(color(R.color.tac_text))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
        }

    private fun text(s: CharSequence, size: Float, c: Int = color(R.color.tac_text), bold: Boolean = false) = TextView(requireContext()).apply {
        text = s
        textSize = size
        setTextColor(c)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, 0)
    }

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
