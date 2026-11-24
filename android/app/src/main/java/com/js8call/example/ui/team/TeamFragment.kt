package com.js8call.example.ui.team

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.js8call.example.R
import com.js8call.example.prop.Band
import com.js8call.example.prop.Predictor
import com.js8call.example.prop.PropagationModel
import com.js8call.example.prop.StationProfile
import com.js8call.example.team.TeamBoard
import com.js8call.example.team.TeamMember
import com.js8call.example.team.TeamRoster
import com.js8call.example.ui.map.myPosition
import com.js8call.example.ui.prop.PropCache
import com.js8call.example.ui.traffic.TrafficViewModel
import java.util.concurrent.Executors

/**
 * Team: the user's own people, apart from everyone else on the net. Each row
 * shows when the member was last heard, their latest CHECKIN/SITREP, and a
 * stoplight per band for reaching them now (the Prop tab's model, from their
 * last heard grid or one typed in). Tap a member for their traffic; long-press
 * to edit. The roster lives only on this device.
 */
class TeamFragment : Fragment() {

    private lateinit var traffic: TrafficViewModel
    private lateinit var cache: PropCache
    private lateinit var body: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var predictor: Predictor? = null
    @Volatile private var predictorAtMs = 0L
    private var building = false
    private var lastRows: List<TeamBoard.Row> = emptyList()
    private var lastKey: Int? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        traffic = TrafficViewModel.of(requireContext())
        cache = ViewModelProvider(requireActivity())[PropCache::class.java]

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.tac_ground))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.tac_panel))
            setPadding(dp(16), dp(6), dp(8), dp(6))
        }
        header.addView(TextView(ctx).apply {
            text = getString(R.string.nav_team)
            setTextColor(color(R.color.tac_text))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Share"
            setOnClickListener { share() }
        })
        header.addView(MaterialButton(ctx).apply {
            text = "Add"
            setOnClickListener { editMember(null) }
        })
        root.addView(header)

        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(24))
        }
        root.addView(ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        traffic.stations.observe(viewLifecycleOwner) { refresh() }
        refresh()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun roster(): List<TeamMember> =
        TeamRoster.decode(PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(TeamRoster.KEY, null))

    private fun saveRoster(list: List<TeamMember>) {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(TeamRoster.KEY, TeamRoster.encode(list)).apply()
        lastKey = null
        refresh()
    }

    // ------------------------------------------------------------- data

    /** Reuse the Prop tab's learned predictor when fresh; otherwise build one off the main thread. */
    private fun ensurePredictor() {
        val now = System.currentTimeMillis()
        if (predictor != null && now - predictorAtMs < 5 * 60_000L) return
        if (building) return
        val ctx = context ?: return
        val me = myPosition(ctx)
        val wx = traffic.weather.effective(now)
        val profile = StationProfile.load(ctx)
        val cached = cache.learned
        if (cached != null && now - cache.learnedAtMs < 10 * 60_000L && cached.me == me) {
            predictor = Predictor(me, wx, profile, cached.correction, cached.nowcast)
            predictorAtMs = now
            return
        }
        building = true
        val all = traffic.observationStore.all()
        val listening = traffic.listeningLog.hours()
        val gridFor: (String) -> String? = { traffic.gridFor(it) }
        val grids = HashMap<String, String?>().also { m -> all.forEach { o -> m[o.call] = o.grid ?: gridFor(o.call) } }
        worker.execute {
            val p = try {
                Predictor.build(me, wx, profile, all, listening, { grids[it] }, now)
            } catch (e: Exception) {
                android.util.Log.w("TeamFragment", "predictor build failed", e)
                Predictor(me, wx, profile)
            }
            // Main looper, not view.post: if the view is gone by now, "building" must still clear.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                predictor = p
                predictorAtMs = now
                building = false
                lastKey = null
                if (view != null) refresh()
            }
        }
    }

    private fun refresh() {
        if (view == null) return
        ensurePredictor()
        val now = System.currentTimeMillis()
        val p = predictor
        val sky = PropagationModel.Sky(now)
        val scorer: ((com.js8call.example.reports.LatLon, Band) -> Double)? =
            if (p?.me != null) { pos, band -> p.score(pos, sky, band) } else null
        val rows = try {
            TeamBoard.build(roster(), traffic.teamHeard(), TeamBoard.latestStatuses(traffic.teamMessages()), scorer)
        } catch (e: Exception) {
            android.util.Log.e("TeamFragment", "team board failed", e)
            return
        }
        // Redraw only when something visible changed (rows, or the minute for "x min ago").
        val key = listOf(rows, now / 60_000L, p?.me != null, traffic.currentBand()).hashCode()
        lastRows = rows
        if (key == lastKey) return
        lastKey = key
        render(rows, now, p?.me != null)
    }

    // ------------------------------------------------------------- views

    private fun render(rows: List<TeamBoard.Row>, now: Long, haveOwnPosition: Boolean) {
        val ctx = requireContext()
        body.removeAllViews()

        if (rows.isEmpty()) {
            body.addView(card().apply {
                addView(text("Your team", 16f, color(R.color.tac_text), bold = true))
                addView(text("Add the callsigns of the people you work with. They are listed here apart from everyone else on the net, with when each was last heard, their latest check-in, and whether you can likely reach them now on each band.", 14f, color(R.color.tac_text_dim)).apply { setPadding(0, dp(6), 0, dp(10)) })
                addView(MaterialButton(ctx).apply { text = "Add a team member"; setOnClickListener { editMember(null) } })
            })
            return
        }

        val currentBand = traffic.currentBand()
        val heardNow = rows.count { it.heard != null && now - it.heard.lastHeardMs < 3_600_000L }
        val open = rows.count { it.status?.tone == TeamBoard.Tone.RED || it.status?.tone == TeamBoard.Tone.AMBER }
        body.addView(text(buildString {
            append("$heardNow of ${rows.size} heard in the last hour")
            if (open > 0) append(" · $open with needs or emergencies")
            if (!haveOwnPosition) append(" · set your grid in Settings to see reach")
        }, 13f, color(R.color.tac_text_dim)).apply { setPadding(dp(4), 0, dp(4), dp(8)) })

        rows.groupBy { it.member.section.ifBlank { "Team" } }.forEach { (section, list) ->
            body.addView(text(section.uppercase(), 12f, color(R.color.tac_text_dim), bold = true).apply {
                letterSpacing = 0.08f
                setPadding(dp(4), dp(8), 0, dp(6))
            })
            list.forEach { body.addView(memberCard(it, now, currentBand)) }
        }

        body.addView(text("Reach = predicted chance of a JS8 contact now with your station settings (Prop tab), from the member's last heard grid or the one you entered. Green good, amber possible, red poor, dark unlikely. Tap a member for their traffic; long-press to edit.", 12f, color(R.color.tac_text_quiet)).apply {
            setPadding(dp(4), dp(12), dp(4), 0)
        })
    }

    private fun memberCard(r: TeamBoard.Row, now: Long, currentBand: Band?): View {
        val ctx = requireContext()
        val tone = r.status?.tone
        return card(tone).apply {
            // Line 1: status dot, callsign, name.
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(ctx).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor(r, now)) }
                }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
                addView(text(r.member.call, 16f, color(R.color.tac_text), bold = true))
                if (r.member.name.isNotBlank()) addView(text("  ${r.member.name}", 14f, color(R.color.tac_text_dim)))
            })
            // Line 2: heard.
            addView(text(listOfNotNull(TeamBoard.heardText(r.heard, now), r.heard?.grid).joinToString(" · "), 13f, color(R.color.tac_text_dim)).apply {
                setPadding(dp(18), dp(2), 0, 0)
            })
            // Line 3: latest status.
            r.status?.let { st ->
                addView(text("${st.label}${if (st.detail.isNotBlank()) ": ${st.detail}" else ""} · ${TeamBoard.ago(st.timeMs, now)}", 13f, toneText(st.tone)).apply {
                    setPadding(dp(18), dp(2), 0, 0)
                })
            }
            // Line 4: reach per band.
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(18), dp(8), 0, 0)
                TeamBoard.BANDS.forEach { band ->
                    val score = r.reach[band]
                    addView(TextView(ctx).apply {
                        text = band.label.removeSuffix("m")
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setTextColor(if (score == null) color(R.color.tac_text_quiet) else 0xFFE6EAF0.toInt())
                        setTypeface(typeface, if (band == currentBand) Typeface.BOLD else Typeface.NORMAL)
                        background = GradientDrawable().apply {
                            cornerRadius = dp(4).toFloat()
                            setColor(cellColor(score))
                            if (band == currentBand) setStroke(dp(2), color(R.color.tac_accent))
                        }
                        contentDescription = "${band.label}: ${lightWord(score)}"
                    }, LinearLayout.LayoutParams(dp(34), dp(24)).apply { marginEnd = dp(4) })
                }
            })
            addView(text(
                if (r.reach.isEmpty()) r.positionNote.replaceFirstChar { it.uppercase() } + " · reach unknown"
                else "Best now: ${r.bestBand?.label ?: "none likely"} · ${r.positionNote}",
                12f, color(R.color.tac_text_quiet),
            ).apply { setPadding(dp(18), dp(4), 0, 0) })

            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            foreground = ContextCompat.getDrawable(ctx, tv.resourceId)
            setOnClickListener { openTraffic(r.member.call) }
            setOnLongClickListener { editMember(r.member); true }
            contentDescription = "${r.member.call}. ${TeamBoard.heardText(r.heard, now)}. Tap for traffic, long-press to edit."
        }
    }

    private fun openTraffic(call: String) {
        // Use the callsign as heard (it may carry a /P suffix).
        val heardCall = traffic.stations.value?.firstOrNull { !it.isGroup && TeamMember.baseOf(it.key) == TeamMember.baseOf(call) }?.key ?: call
        traffic.select(heardCall)
        val nav = findNavController()
        if (!nav.popBackStack(R.id.navigation_traffic, false)) nav.navigate(R.id.navigation_traffic)
    }

    // ------------------------------------------------------------- add / edit

    private fun editMember(existing: TeamMember?) {
        val ctx = requireContext()
        val heardCalls = traffic.stations.value.orEmpty().filter { !it.isGroup }.map { it.key }
        val sections = roster().map { it.section }.filter { it.isNotBlank() }.distinct()
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val call = AutoCompleteTextView(ctx).apply {
            hint = "Callsign"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, heardCalls))
            threshold = 1
            setText(existing?.call.orEmpty())
        }
        val name = EditText(ctx).apply { hint = "Name or role (optional)"; setText(existing?.name.orEmpty()) }
        val section = AutoCompleteTextView(ctx).apply {
            hint = "Section, e.g. Alpha (optional)"
            setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, sections))
            threshold = 0
            setText(existing?.section.orEmpty())
        }
        val grid = EditText(ctx).apply {
            hint = "Grid or MGRS (optional; used until heard)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(existing?.grid.orEmpty())
        }
        listOf(call, name, section, grid).forEach { form.addView(it) }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) "Add team member" else "Edit ${existing.call}")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (existing != null) setNeutralButton("Remove") { _, _ ->
                    saveRoster(TeamRoster.remove(roster(), existing.call))
                }
            }
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val c = call.text.toString().trim().uppercase()
            if (!Regex("""^[A-Z0-9/]{3,}$""").matches(c)) {
                call.error = "Enter a callsign"
                return@setOnClickListener
            }
            val m = TeamMember(c, name.text.toString().trim(), section.text.toString().trim(), grid.text.toString().trim().uppercase())
            saveRoster(TeamRoster.upsert(roster(), m, existing?.call))
            dialog.dismiss()
        }
    }

    private fun share() {
        if (lastRows.isEmpty()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Team status")
            putExtra(Intent.EXTRA_TEXT, TeamBoard.toText(lastRows, System.currentTimeMillis()))
        }, "Share team status"))
    }

    // ------------------------------------------------------------- styling

    private fun dotColor(r: TeamBoard.Row, now: Long): Int = when {
        r.status?.tone == TeamBoard.Tone.RED -> 0xFFFF5A5A.toInt()
        r.status?.tone == TeamBoard.Tone.AMBER -> color(R.color.tac_warn)
        r.heard != null && now - r.heard.lastHeardMs < 3_600_000L -> color(R.color.tac_good)
        r.heard != null && now - r.heard.lastHeardMs < 24 * 3_600_000L -> 0xFF7D8794.toInt()
        else -> 0xFF3A424E.toInt()
    }

    private fun cellColor(score: Double?): Int = when (TeamBoard.light(score)) {
        TeamBoard.Light.GO -> 0xFF2E8B5E.toInt()
        TeamBoard.Light.CAUTION -> 0xFFB7791F.toInt()
        TeamBoard.Light.POOR -> 0xFF8E3B3B.toInt()
        TeamBoard.Light.NONE -> if (score == null) 0xFF1A2029.toInt() else 0xFF1E242D.toInt()
    }

    private fun lightWord(score: Double?) = when (TeamBoard.light(score)) {
        TeamBoard.Light.GO -> "good"
        TeamBoard.Light.CAUTION -> "possible"
        TeamBoard.Light.POOR -> "poor"
        TeamBoard.Light.NONE -> if (score == null) "unknown" else "unlikely"
    }

    private fun card(tone: TeamBoard.Tone? = null): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(when (tone) {
                TeamBoard.Tone.RED -> 0x29FF5A5A
                TeamBoard.Tone.AMBER -> color(R.color.tac_warn_tint)
                else -> color(R.color.tac_card)
            })
            setStroke(dp(1), when (tone) {
                TeamBoard.Tone.RED -> 0xFF7A2A2A.toInt()
                TeamBoard.Tone.AMBER -> color(R.color.tac_warn_border)
                else -> color(R.color.tac_border)
            })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) }
    }

    private fun toneText(t: TeamBoard.Tone): Int = when (t) {
        TeamBoard.Tone.RED -> 0xFFFF7B7B.toInt()
        TeamBoard.Tone.AMBER -> color(R.color.tac_warn_text)
        TeamBoard.Tone.GREEN -> color(R.color.tac_good_text)
        TeamBoard.Tone.NEUTRAL -> color(R.color.tac_text_dim)
    }

    private fun text(s: String, size: Float, c: Int, bold: Boolean = false) = TextView(requireContext()).apply {
        text = s
        textSize = size
        setTextColor(c)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
