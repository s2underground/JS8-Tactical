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
            l.full?.let { full -> com.js8call.example.ai.AiWork.summary(ctx, full) }?.let { ai ->
                addView(TextView(ctx).apply {
                    text = "AI  ·  $ai"
                    setTextColor(AI_TEXT)
                    textSize = 14f
                    setPadding(0, dp(2), 0, 0)
                })
            }
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

    /** AI overview: written on request from this digest (bulletins as their one-line summaries). */
    private fun overviewCard(d: DigestBuilder.Digest): View {
        val ctx = requireContext()
        val ai = com.js8call.example.ai.AiWork
        return card(Tone.NEUTRAL).apply {
            addView(TextView(ctx).apply {
                text = getString(R.string.ai_overview_title)
                setTextColor(AI_TEXT)
                textSize = 12f
                letterSpacing = 0.08f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            })
            val textView = TextView(ctx).apply {
                textSize = 15.5f
                setTextColor(color(R.color.tac_text))
                setTextIsSelectable(true)
            }
            val meta = TextView(ctx).apply {
                textSize = 12f
                setTextColor(color(R.color.tac_text_quiet))
                setPadding(0, dp(4), 0, 0)
            }
            val usable = ai.usable(ctx)
            when {
                overviewBusy -> { textView.text = overviewText.ifBlank { getString(R.string.ai_overview_working) } }
                overviewText.isNotBlank() -> {
                    textView.text = overviewText
                    meta.text = getString(R.string.ai_overview_written, java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(overviewAtMs)))
                }
                !usable -> textView.text = getString(R.string.ai_overview_no_model)
                else -> textView.text = getString(R.string.ai_overview_hint)
            }
            addView(textView)
            if (meta.text.isNotEmpty()) addView(meta)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            if (usable && !overviewBusy) row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(if (overviewText.isBlank()) R.string.ai_overview_write else R.string.ai_overview_update)
                setOnClickListener { writeOverview(d) }
            })
            if (!usable) row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.settings_ai_open)
                setOnClickListener { findNavController().navigate(R.id.navigation_ai) }
            })
            if (row.childCount > 0) addView(row)
        }
    }

    private fun writeOverview(d: DigestBuilder.Digest) {
        val ctx = requireContext()
        val lines = d.sections.flatMap { it.lines }.mapNotNull { l -> l.full?.let { com.js8call.example.ai.AiWork.summary(ctx, it) } }
        overviewBusy = true
        overviewText = ""
        last?.let { render(it) }
        com.js8call.example.ai.AiWork.overview(ctx, d.toText(), lines,
            onText = { t -> overviewText = t; if (view != null) last?.let { render(it) } },
            onDone = { t, err ->
                overviewBusy = false
                overviewText = t ?: (err?.let { getString(R.string.ai_overview_failed, it) } ?: "")
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
        // Kept across visits to the tab (the model takes a while to write it).
        private var overviewText = ""
        private var overviewBusy = false
        private var overviewAtMs = 0L
    }
}
