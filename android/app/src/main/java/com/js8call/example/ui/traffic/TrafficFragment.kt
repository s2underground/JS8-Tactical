package com.js8call.example.ui.traffic

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.model.TransmitState
import com.js8call.example.ui.ComposeBarController
import com.js8call.example.ui.MonitorViewModel
import com.js8call.example.ui.TransmitViewModel
import com.js8call.example.ui.WaterfallView
import com.js8call.example.ui.readyToSend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The main operating screen: waterfall strip with station markers, stations
 * and groups on the left, the selected thread of rolled-up messages, a live
 * "receiving from" bar, report templates and compose.
 */
class TrafficFragment : Fragment() {

    private lateinit var traffic: TrafficViewModel
    private lateinit var monitor: MonitorViewModel
    private lateinit var transmit: TransmitViewModel

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private var clockView: TextView? = null
    private val clockTick = object : Runnable {
        override fun run() {
            clockView?.text = clockFormat.format(Date())
            clockHandler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_traffic, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val provider = ViewModelProvider(requireActivity())
        traffic = provider[TrafficViewModel::class.java]
        monitor = provider[MonitorViewModel::class.java]
        transmit = provider[TransmitViewModel::class.java]

        setUpStatusStrip(view)
        setUpWaterfall(view)
        setUpLists(view)
        setUpCompose(view)
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockView = null
    }

    // ------------------------------------------------------------- status

    private fun setUpStatusStrip(view: View) {
        clockView = view.findViewById(R.id.status_clock)
        if (com.js8call.example.BuildConfig.DEBUG) {
            // Test builds: long-press the title to load sample traffic.
            view.findViewById<TextView>(R.id.status_title).setOnLongClickListener {
                traffic.loadDemo()
                Snackbar.make(requireView(), R.string.traffic_demo_loaded, Snackbar.LENGTH_SHORT).show()
                true
            }
        }
        val freq: TextView = view.findViewById(R.id.status_frequency)
        val pill: TextView = view.findViewById(R.id.status_pill)

        monitor.radioFrequency.observe(viewLifecycleOwner) { hz ->
            freq.text = if (hz != null && hz > 0) String.format(Locale.US, "%.3f MHz", hz / 1_000_000.0) else getString(R.string.traffic_no_rig)
        }

        fun renderPill() {
            val running = monitor.isRunning.value == true
            val tx = transmit.txState.value == TransmitState.TRANSMITTING
            val (label, text, bg) = when {
                tx -> Triple(R.string.traffic_state_tx, R.color.tac_ground, R.drawable.bg_pill_tx)
                running -> Triple(R.string.traffic_state_rx, R.color.tac_good_text, R.drawable.bg_pill_rx)
                else -> Triple(R.string.traffic_state_off, R.color.tac_text_dim, R.drawable.bg_pill_off)
            }
            pill.setText(label)
            pill.setTextColor(ContextCompat.getColor(requireContext(), text))
            pill.setBackgroundResource(bg)
        }
        monitor.isRunning.observe(viewLifecycleOwner) { renderPill() }
        transmit.txState.observe(viewLifecycleOwner) { renderPill() }
        // Start/stop and audio setup live on the Radio tab for now.
        pill.setOnClickListener {
            requireActivity().findViewById<NavigationBarView>(R.id.bottom_navigation)?.selectedItemId = R.id.navigation_monitor
        }
    }

    // ------------------------------------------------------------- waterfall

    private fun setUpWaterfall(view: View) {
        val waterfall: WaterfallView = view.findViewById(R.id.traffic_waterfall)
        val overlay: StationMarkerOverlay = view.findViewById(R.id.traffic_markers)
        val renderer = monitor.getWaterfallRenderer()
        waterfall.bindRenderer(renderer)
        overlay.renderer = renderer
        waterfall.txOffsetHz = monitor.getTxOffset()
        waterfall.onOffsetChanged = { hz ->
            monitor.setTxOffset(hz)
            waterfall.txOffsetHz = hz
        }
        monitor.status.observe(viewLifecycleOwner) { status ->
            waterfall.txOffsetHz = status.txOffsetHz
            overlay.invalidate()
        }
        traffic.markers.observe(viewLifecycleOwner) { overlay.markers = it }
    }

    // ------------------------------------------------------------- lists

    private fun setUpLists(view: View) {
        val stationList: RecyclerView = view.findViewById(R.id.station_list)
        val threadList: RecyclerView = view.findViewById(R.id.thread_list)
        val title: TextView = view.findViewById(R.id.thread_title)
        val subtitle: TextView = view.findViewById(R.id.thread_subtitle)
        val empty: TextView = view.findViewById(R.id.thread_empty)
        val liveBar: View = view.findViewById(R.id.live_bar)
        val liveFrom: TextView = view.findViewById(R.id.live_from)
        val liveText: TextView = view.findViewById(R.id.live_text)

        val stations = StationAdapter { row -> traffic.select(row.key) }
        stationList.layoutManager = LinearLayoutManager(requireContext())
        stationList.adapter = stations
        stationList.itemAnimator = null

        val thread = ThreadAdapter(
            showSender = { traffic.selected.value.let { it == TrafficViewModel.ALL || it?.startsWith("@") == true } },
            onRequestResend = { sender -> requestResend(sender) },
            onShowRaw = { item -> showRaw(item) },
        )
        threadList.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        threadList.adapter = thread
        threadList.itemAnimator = null

        traffic.stations.observe(viewLifecycleOwner) { rows ->
            stations.submitList(rows)
            val sel = rows.firstOrNull { it.selected }
            title.text = sel?.label ?: getString(R.string.traffic_all)
            subtitle.text = sel?.detail.orEmpty()
            title.setTextColor(if (sel == null || sel.isGroup) ContextCompat.getColor(requireContext(), R.color.tac_text) else sel.color)
        }
        traffic.thread.observe(viewLifecycleOwner) { items ->
            val atBottom = !threadList.canScrollVertically(1)
            thread.submitList(items) {
                if (atBottom && items.isNotEmpty()) threadList.scrollToPosition(items.size - 1)
            }
            empty.isVisible = items.isEmpty()
        }
        traffic.selected.observe(viewLifecycleOwner) {
            thread.notifyDataSetChanged()
            view.findViewById<EditText>(R.id.compose_input).hint = when (it) {
                TrafficViewModel.ALL -> getString(R.string.traffic_hint_all)
                else -> getString(R.string.traffic_hint_to, it)
            }
        }
        traffic.live.observe(viewLifecycleOwner) { live ->
            liveBar.isVisible = live != null
            if (live != null) {
                liveFrom.text = if (live.sender != null) getString(R.string.traffic_receiving_from, live.sender)
                else getString(R.string.traffic_receiving_at, live.laneHz.toInt())
                liveFrom.setTextColor(live.color)
                liveText.text = getString(R.string.traffic_live_text, live.frames, live.text)
            }
        }
    }

    private fun requestResend(sender: String) {
        if (!readyToSend()) return
        // AGN? asks the sender to repeat their last message.
        transmit.queueThreadMessage(sender, "AGN?")
        Snackbar.make(requireView(), getString(R.string.traffic_resend_queued, sender), Snackbar.LENGTH_SHORT).show()
    }

    private fun showRaw(item: TrafficViewModel.ThreadItem.Incoming) {
        val lines = item.message.frames.mapIndexed { i, f ->
            if (f == null) "${i + 1}.  —  missing"
            else "${i + 1}.  ${zuluTime(f.slotStartEpochSec * 1000)}  +${f.frequencyHz.toInt()} Hz  ${f.snr} dB  ${f.text}"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.traffic_raw_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------- compose

    private fun setUpCompose(view: View) {
        val input: EditText = view.findViewById(R.id.compose_input)
        val repeat: Chip = view.findViewById(R.id.chip_repeat3)

        fun insertTemplate(template: String) {
            input.setText(template)
            input.setSelection(input.text.length)
            input.requestFocus()
        }
        val grid = { PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("grid", "").orEmpty().uppercase() }
        val dtg = { SimpleDateFormat("ddHHmm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()) }
        view.findViewById<Chip>(R.id.tpl_checkin).setOnClickListener { insertTemplate("#CHECKIN GOOD LOC:${grid()} ") }
        view.findViewById<Chip>(R.id.tpl_spot).setOnClickListener { insertTemplate("#SPOT SIZE: ACT: LOC:${grid()} T:${dtg()} ") }
        view.findViewById<Chip>(R.id.tpl_salute).setOnClickListener { insertTemplate("#SALUTE S: A: L:${grid()} U: T:${dtg()} E:") }
        view.findViewById<Chip>(R.id.tpl_sitrep).setOnClickListener { insertTemplate("#SITREP STATUS: NEEDS: ") }
        view.findViewById<Chip>(R.id.tpl_posrep).setOnClickListener { insertTemplate("#POSREP LOC:${grid()} STATUS: ") }

        ComposeBarController(
            fragment = this,
            root = view,
            commandMenuRes = R.menu.directed_command_menu,
            onSend = { text -> send(text, if (repeat.isChecked) 3 else 1) },
        )
    }

    private fun send(text: String, copies: Int): Boolean {
        if (!readyToSend()) return false
        val target = traffic.sendTarget()
        repeat(copies) {
            if (target == null) transmit.queueManualMessage(text) else transmit.queueThreadMessage(target, text)
        }
        val msg = if (copies > 1) getString(R.string.traffic_queued_n, copies) else getString(R.string.traffic_queued)
        Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
        return true
    }
}
