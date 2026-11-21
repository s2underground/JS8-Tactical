package com.js8call.example.ui.digest

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.js8call.example.R
import com.js8call.example.digest.DigestBuilder
import com.js8call.example.digest.DigestBuilder.Tone
import com.js8call.example.ui.traffic.TrafficViewModel

/**
 * Digest: a summary of the net for a time window, worked out on the device
 * (no network). Needs and emergencies first, then traffic waiting for you,
 * reports, stations, and your own station. Tap a line with a callsign to open
 * that station's traffic. Share sends the digest as plain text.
 */
class DigestFragment : Fragment() {

    private lateinit var traffic: TrafficViewModel
    private lateinit var body: LinearLayout
    private var windowHours = 24
    private var last: DigestBuilder.Digest? = null
    private var stories: List<com.js8call.example.ai.AiStories.Story> = emptyList()

    private val windows = listOf(6 to "6 h", 24 to "24 h", 72 to "3 days", 168 to "7 days")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        windowHours = savedInstanceState?.getInt("window", 24) ?: 24
        traffic = TrafficViewModel.of(requireContext())

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.tac_ground))
        }

        // Header: title, window chips, share.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.tac_panel))
            setPadding(dp(16), dp(6), dp(8), dp(6))
        }
        header.addView(TextView(ctx).apply {
            text = getString(R.string.nav_digest)
            setTextColor(color(R.color.tac_text))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        val chips = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true; isSingleLine = true }
        windows.forEach { (h, label) ->
            chips.addView(Chip(ctx).apply {
                text = label
                isCheckable = true
                isChecked = h == windowHours
                id = View.generateViewId()
                setOnClickListener { windowHours = h; refresh() }
            })
        }
        header.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        if (com.js8call.example.ai.AiModels.enabled(ctx)) header.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.ai_button)
            setOnClickListener { androidx.navigation.fragment.NavHostFragment.findNavController(this@DigestFragment).navigate(R.id.navigation_ai) }
        })
        header.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Share"
            setOnClickListener { share() }
        })
        root.addView(header)

        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(24))
        }
        root.addView(ScrollView(ctx).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Rebuilt whenever the traffic picture changes (new messages, the 5 s tick).
        traffic.digestAlerts.observe(viewLifecycleOwner) { refresh() }
        traffic.stations.observe(viewLifecycleOwner) { refresh() }
        com.js8call.example.ai.AiWork.bulletins.observe(viewLifecycleOwner) { last?.let { render(it) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("window", windowHours)
    }

    private fun refresh() {
        if (view == null) return
        val now = System.currentTimeMillis()
        val d = try {
            val input = traffic.digestInput(now - windowHours * 3_600_000L, now)
            com.js8call.example.ai.AiWork.summariseBulletins(requireContext(), input.messages)
            if (com.js8call.example.ai.AiModels.enabled(requireContext()))
                stories = com.js8call.example.ai.AiStories.build(input.messages.filter { it.timeMs >= input.sinceMs })
            DigestBuilder.build(input)
        } catch (e: Exception) {
            android.util.Log.e("DigestFragment", "digest failed", e)
            return
        }
        if (d == last) return
        last = d
        render(d)
    }

    private fun render(d: DigestBuilder.Digest) {
        val ctx = requireContext()
        body.removeAllViews()

        // Headline card.
        body.addView(card(d.tone).apply {
            addView(TextView(ctx).apply {
                text = d.headline
                setTextColor(toneText(d.tone))
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                text = d.windowText
                setTextColor(color(R.color.tac_text_quiet))
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
        })

        if (com.js8call.example.ai.AiModels.enabled(ctx)) body.addView(overviewCard(d))

        d.sections.forEach { s ->
            body.addView(card(s.tone).apply {
                addView(TextView(ctx).apply {
                    text = s.title.uppercase()
                    setTextColor(if (s.tone == Tone.NEUTRAL) color(R.color.tac_text_dim) else toneText(s.tone))
                    textSize = 12f
                    letterSpacing = 0.08f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(6))
                })
                if (s.lines.isEmpty()) addView(TextView(ctx).apply {
                    text = s.empty
                    setTextColor(color(R.color.tac_text_quiet))
                    textSize = 14f
                })
                s.lines.forEach { addView(lineView(it)) }
            })
        }
    }

    private fun lineView(l: DigestBuilder.Line): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            minimumHeight = if (l.call != null) dp(44) else 0
            addView(TextView(ctx).apply {
                text = l.text
                setTextColor(if (l.tone == Tone.NEUTRAL) color(R.color.tac_text) else toneText(l.tone))
                textSize = 15f
            })
            if (l.detail.isNotEmpty()) addView(TextView(ctx).apply {
                text = l.detail
                setTextColor(color(R.color.tac_text_dim))
                textSize = 12f
            })
            l.call?.let { call ->
                val tv = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                contentDescription = "${l.text}. Open $call traffic"
                setOnClickListener {
                    traffic.select(call)
                    val nav = findNavController()
                    // Traffic is the start screen, so it is always underneath; go back to it.
                    if (!nav.popBackStack(R.id.navigation_traffic, false)) nav.navigate(R.id.navigation_traffic)
                }
            }
        }
    }

    /**
     * News roll-up: the bulletins heard, grouped into stories with how many stations reported each
     * (counted by the app, not the model), each in plain English (the AI one-line summary), and an
     * AI-written lead paragraph on request.
     */
    private fun overviewCard(d: DigestBuilder.Digest): View {
        val ctx = requireContext()
        val ai = com.js8call.example.ai.AiWork
        val stories = this.stories
        return card(Tone.NEUTRAL).apply {
            addView(TextView(ctx).apply {
                text = getString(R.string.ai_overview_title)
                setTextColor(AI_TEXT)
                textSize = 12f
                letterSpacing = 0.08f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            })
            val usable = ai.usable(ctx)
            fun section(title: String) = d.sections.firstOrNull { it.title == title }?.lines.orEmpty().filter { it.tone != Tone.GREEN }
            val activity = d.sections.firstOrNull { it.title == "Stations" }?.lines?.firstOrNull()?.text
            // The app's own line: always right, no AI needed.
            addView(TextView(ctx).apply {
                text = com.js8call.example.ai.AiPrompt.situation(section("Needs and emergencies").size, section("For you").size, stories.map { it.sources }, activity)
                textSize = 16f
                setTextColor(color(R.color.tac_text))
            })
            // With two or more stories the model sums up the main news; one story speaks for itself.
            if (stories.size >= 2 || overviewBusy || overviewText.isNotBlank()) {
                addView(TextView(ctx).apply {
                    textSize = 16f
                    setPadding(0, dp(6), 0, 0)
                    setTextColor(if (overviewFailed || overviewText.isBlank()) color(R.color.tac_text_dim) else color(R.color.tac_text))
                    text = when {
                        overviewBusy -> getString(R.string.ai_overview_working)
                        overviewText.isNotBlank() && !overviewFailed -> withCitations(overviewText)
                        overviewText.isNotBlank() -> overviewText
                        !usable -> getString(R.string.ai_overview_no_model)
                        else -> getString(R.string.ai_overview_hint)
                    }
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                })
                if (overviewText.isNotBlank() && !overviewBusy && !overviewFailed) addView(TextView(ctx).apply {
                    text = getString(R.string.ai_overview_written, java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(overviewAtMs)))
                    textSize = 12f
                    setTextColor(color(R.color.tac_text_quiet))
                    setPadding(0, dp(4), 0, 0)
                })
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
                if (usable && !overviewBusy && stories.size >= 2) row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(if (overviewText.isBlank()) R.string.ai_overview_write else R.string.ai_overview_update)
                    setOnClickListener { writeOverview(d) }
                })
                if (leadInput.isNotBlank() && !overviewBusy) row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.ai_notes)
                    setOnClickListener { showNotes() }
                })
                if (!usable) row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.settings_ai_open)
                    setOnClickListener { findNavController().navigate(R.id.navigation_ai) }
                })
                if (row.childCount > 0) addView(row)
            }

            // The stories themselves.
            if (stories.isNotEmpty()) addView(TextView(ctx).apply {
                text = getString(R.string.ai_stories, stories.size)
                setTextColor(color(R.color.tac_text_dim))
                textSize = 12f
                letterSpacing = 0.06f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(2))
            })
            val now = System.currentTimeMillis()
            stories.take(8).forEach { st ->
                val line = ai.summary(ctx, st.rawBody)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), 0, dp(4))
                    val tv = TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener { showSources(st) }
                    addView(TextView(ctx).apply {
                        text = line ?: (com.js8call.example.ai.AiStories.sentenceCase(st.body).let { if (it.length > 220) it.take(220) + "…" else it })
                        setTextColor(color(R.color.tac_text))
                        textSize = 15f
                    })
                    addView(TextView(ctx).apply {
                        val tag = com.js8call.example.ai.AiStories.sourceLine(st)
                        text = listOfNotNull(tag, ago(st.lastMs, now), if (line == null && usable) getString(R.string.ai_story_pending) else null,
                            getString(R.string.ai_sources_link)).joinToString("  ·  ")
                        setTextColor(if (st.sources > 1) CONFIRMED else color(R.color.tac_text_quiet))
                        textSize = 12f
                        if (st.sources > 1) setTypeface(typeface, Typeface.BOLD)
                    })
                })
            }
        }
    }

    /** "[2]" in the AI text becomes a link to story 2's transmissions. */
    private fun withCitations(text: String): CharSequence {
        val out = android.text.SpannableStringBuilder(text)
        Regex("\\[(\\d+)]").findAll(text).forEach { m ->
            val st = leadStories.getOrNull(m.groupValues[1].toInt() - 1) ?: return@forEach
            out.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) = showSources(st)
                override fun updateDrawState(ds: android.text.TextPaint) { ds.color = AI_TEXT; ds.isUnderlineText = true }
            }, m.range.first, m.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /** The exact transmissions behind a story: who, when, how well heard, the full text. */
    private fun showSources(st: com.js8call.example.ai.AiStories.Story) {
        val ctx = requireContext()
        val fmt = java.text.SimpleDateFormat("dd MMM HH:mm'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        box.addView(TextView(ctx).apply {
            text = com.js8call.example.ai.AiStories.sourceLine(st)
            setTextColor(if (st.sources > 1) CONFIRMED else color(R.color.tac_text_dim))
            textSize = 13f
        })
        st.msgs.forEach { m ->
            box.addView(TextView(ctx).apply {
                text = listOfNotNull(
                    "${m.sender ?: "?"} → ${m.recipient ?: "everyone"}",
                    fmt.format(java.util.Date(m.timeMs)),
                    m.snr?.let { "SNR $it dB" },
                    if (m.complete) "complete" else "${m.missingFrames} frame(s) missing",
                    if (m.sender?.uppercase() in st.relayedBy) "relayed copy" else "own report",
                ).joinToString("  ·  ")
                setTextColor(color(R.color.tac_text_dim))
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(2))
            })
            box.addView(TextView(ctx).apply {
                text = m.body.trim()
                setTextColor(color(R.color.tac_text))
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
            m.sender?.let { call ->
                box.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.ai_open_in_traffic, call)
                    setOnClickListener {
                        sourcesDialog?.dismiss()
                        traffic.select(call)
                        val nav = findNavController()
                        if (!nav.popBackStack(R.id.navigation_traffic, false)) nav.navigate(R.id.navigation_traffic)
                    }
                })
            }
        }
        sourcesDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.ai_sources_title, st.msgs.size))
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private var sourcesDialog: androidx.appcompat.app.AlertDialog? = null

    /** Exactly what the model was given for the summary. */
    private fun showNotes() {
        val ctx = requireContext()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ai_notes_title)
            .setView(ScrollView(ctx).apply {
                addView(TextView(ctx).apply {
                    text = leadInput
                    typeface = Typeface.MONOSPACE
                    textSize = 12.5f
                    setTextColor(color(R.color.tac_text))
                    setTextIsSelectable(true)
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                })
            })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun ago(ms: Long, now: Long): String {
        val m = ((now - ms) / 60_000L).coerceAtLeast(0)
        return when { m < 60 -> "${m} min ago"; m < 48 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
    }

    private fun writeOverview(d: DigestBuilder.Digest) {
        val ctx = requireContext()
        leadStories = stories.take(6)
        val storyFacts = leadStories.map { st ->
            (com.js8call.example.ai.AiWork.summary(ctx, st.rawBody) ?: com.js8call.example.ai.AiStories.sentenceCase(st.body).take(300)) to st.sources
        }
        val user = com.js8call.example.ai.AiPrompt.newsUser(storyFacts)
        leadInput = user
        overviewBusy = true
        overviewFailed = false
        overviewText = ""
        last?.let { render(it) }
        // Shown only when finished and checked (no half-written text on screen).
        com.js8call.example.ai.AiWork.overview(ctx, user,
            onText = { },
            onDone = { t, err ->
                overviewBusy = false
                overviewFailed = t == null
                overviewText = t ?: getString(R.string.ai_overview_failed, err ?: "no text came back")
                overviewAtMs = System.currentTimeMillis()
                if (view != null) last?.let { render(it) }
            })
    }

    private fun card(tone: Tone): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(when (tone) {
                Tone.RED -> 0x29FF5A5A
                Tone.AMBER -> color(R.color.tac_warn_tint)
                else -> color(R.color.tac_card)
            })
            setStroke(dp(1), when (tone) {
                Tone.RED -> 0xFF7A2A2A.toInt()
                Tone.AMBER -> color(R.color.tac_warn_border)
                Tone.GREEN -> 0xFF24523C.toInt()
                else -> color(R.color.tac_border)
            })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) }
    }

    private fun toneText(t: Tone): Int = when (t) {
        Tone.RED -> 0xFFFF7B7B.toInt()
        Tone.AMBER -> color(R.color.tac_warn_text)
        Tone.GREEN -> color(R.color.tac_good_text)
        Tone.NEUTRAL -> color(R.color.tac_text)
    }

    private fun share() {
        val text = last?.toText() ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "JS8 digest")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share digest"))
    }

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val AI_TEXT = 0xFFB39DDB.toInt()
        private val CONFIRMED = 0xFF5CC98B.toInt()
        // Kept across visits to the tab (the model takes a while to write it).
        private var overviewText = ""
        private var overviewBusy = false
        private var overviewFailed = false
        // The stories and the exact notes the last summary was written from (its citations).
        private var leadStories: List<com.js8call.example.ai.AiStories.Story> = emptyList()
        private var leadInput = ""
        private var overviewAtMs = 0L
    }
}
