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
import com.js8call.example.ui.queueBroadcast
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

    private companion object {
        const val ECHO_SHOW_MS = 3 * 60_000L
        /** The mismatch dialog is shown once per app session. */
        var echoMismatchShown = false
        const val KEY_AUTOREPLY = "autoreply_enabled"
        const val KEY_SCREEN_ON = "keep_screen_on"
        /** JS8 queries that only mean something when addressed. */
        val ALLCALL_QUERY = Regex("""^(?:SNR|GRID|INFO|STATUS|HEARING)\?$|^QUERY MSGS\??$""")
    }

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
        traffic = TrafficViewModel.of(requireContext())
        monitor = provider[MonitorViewModel::class.java]
        transmit = TransmitViewModel.of(requireContext())

        setUpStatusStrip(view)
        setUpWaterfall(view)
        setUpLists(view)
        setUpCompose(view)
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockTick)
        applyRole()
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
    }

    /** Listener role: send controls hidden (a banner says how to bring them back). */
    private fun applyRole() {
        val v = view ?: return
        val listener = com.js8call.example.ui.onboarding.RoleChooser.isListener(requireContext())
        v.findViewById<View>(R.id.compose_container)?.isVisible = !listener
        v.findViewById<View>(R.id.tpl_row)?.isVisible = !listener
        val parent = v.findViewById<View>(R.id.compose_container)?.parent as? ViewGroup ?: return
        var banner = parent.findViewWithTag<TextView>("listener_banner")
        if (listener && banner == null) {
            banner = TextView(requireContext()).apply {
                tag = "listener_banner"
                text = "Listening only. Tap here to show the send controls."
                setTextColor(ContextCompat.getColor(requireContext(), R.color.tac_text_dim))
                textSize = 13f
                val d = resources.displayMetrics.density
                setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.tac_panel))
                setOnClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Show send controls?")
                        .setMessage("You chose \"listen\" at first start. Switching to Operator shows the message box and report buttons. Only transmit with a transmitter you're allowed to use.")
                        .setPositiveButton("Switch to Operator") { _, _ ->
                            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                                .putString(com.js8call.example.ui.onboarding.RoleChooser.KEY, com.js8call.example.ui.onboarding.RoleChooser.OPERATOR).apply()
                            applyRole()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            parent.addView(banner)
        }
        banner?.isVisible = listener
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

        // Band: the saved preset, or the radio's own dial when it reports one that differs.
        fun renderBand() {
            val (bands, index) = com.js8call.example.ui.RadioControls.bands(requireContext())
            val preset = bands.getOrNull(index)
            val dial = monitor.radioFrequency.value
            freq.text = when {
                dial != null && dial > 0 && (preset == null || kotlin.math.abs(dial - preset.hz) > 1_000) ->
                    String.format(Locale.US, "%.3f", dial / 1_000_000.0)
                preset != null -> com.js8call.example.ui.RadioControls.shortLabel(preset.label)
                else -> getString(R.string.traffic_no_rig)
            }
        }
        renderBand()
        monitor.radioFrequency.observe(viewLifecycleOwner) { renderBand() }
        freq.setOnClickListener {
            val (bands, index) = com.js8call.example.ui.RadioControls.bands(requireContext())
            val canTune = com.js8call.example.ui.RadioControls.canTuneRadio(requireContext())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (canTune) getString(R.string.monitor_radio_frequency) else getString(R.string.band_pick_title_manual))
                .setSingleChoiceItems(bands.map { it.label }.toTypedArray(), index) { d, which ->
                    d.dismiss()
                    com.js8call.example.ui.RadioControls.selectBand(requireContext(), bands[which])
                    renderBand()
                    if (!canTune) {
                        // The app can't turn the radio's dial: say so, or stations get logged on the wrong band.
                        com.google.android.material.snackbar.Snackbar.make(
                            requireView(),
                            getString(R.string.band_pick_set_radio, com.js8call.example.ui.RadioControls.shortLabel(bands[which].label)),
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
                        ).setDuration(8_000).show()
                    }
                }
                .show()
        }

        // Receiver on/off, right on the main screen.
        val receiver: com.google.android.material.materialswitch.MaterialSwitch = view.findViewById(R.id.receiver_switch)
        var settingSwitch = false
        fun renderSwitch() {
            val st = monitor.status.value?.state
            settingSwitch = true
            receiver.isChecked = st == com.js8call.example.model.EngineState.RUNNING || st == com.js8call.example.model.EngineState.STARTING
            settingSwitch = false
        }
        renderSwitch()
        monitor.status.observe(viewLifecycleOwner) { renderSwitch() }
        receiver.setOnCheckedChangeListener { _, on ->
            if (settingSwitch) return@setOnCheckedChangeListener
            if (on) {
                if (com.js8call.example.ui.RadioControls.needsMicPermission(requireContext())) {
                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    startReceiverChecked()
                }
            } else {
                com.js8call.example.ui.RadioControls.stop(requireContext(), monitor)
            }
            receiver.post { renderSwitch() }
        }

        // Auto-reply and keep-screen-on: pills on tablets, ⋮ menu everywhere.
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val autoPill: TextView = view.findViewById(R.id.toggle_autoreply)
        val screenPill: TextView = view.findViewById(R.id.toggle_screen)
        fun renderToggle(v: TextView, on: Boolean) {
            v.setBackgroundResource(if (on) R.drawable.bg_pill_rx else R.drawable.bg_pill_off)
            v.setTextColor(ContextCompat.getColor(requireContext(), if (on) R.color.tac_good_text else R.color.tac_text_dim))
        }
        fun renderToggles() {
            renderToggle(autoPill, prefs.getBoolean(KEY_AUTOREPLY, false))
            renderToggle(screenPill, prefs.getBoolean(KEY_SCREEN_ON, false))
        }
        renderToggles()
        autoPill.setOnClickListener { setAutoReply(!prefs.getBoolean(KEY_AUTOREPLY, false)); renderToggles() }
        screenPill.setOnClickListener { setScreenOn(!prefs.getBoolean(KEY_SCREEN_ON, false)); renderToggles() }
        view.findViewById<View>(R.id.status_more).setOnClickListener { anchor ->
            val popup = android.widget.PopupMenu(requireContext(), anchor)
            val m = popup.menu
            m.add(0, 1, 0, "Auto-reply").apply { isCheckable = true; isChecked = prefs.getBoolean(KEY_AUTOREPLY, false) }
            m.add(0, 2, 1, "Keep screen on").apply { isCheckable = true; isChecked = prefs.getBoolean(KEY_SCREEN_ON, false) }
            m.add(0, 3, 2, "Audio input…")
            m.add(0, 6, 2, "Heartbeat offset… (${com.js8call.example.ui.HeartbeatOffsetDialog.summary(requireContext())})")
            m.add(0, 4, 3, "Raw decodes, clock and audio details")
            m.add(0, 5, 4, "Inbox (stored messages)")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> setAutoReply(!prefs.getBoolean(KEY_AUTOREPLY, false))
                    2 -> setScreenOn(!prefs.getBoolean(KEY_SCREEN_ON, false))
                    3 -> com.js8call.example.ui.AudioDevices.showPicker(requireContext(), monitor.isRunning.value == true) { d ->
                        Snackbar.make(requireView(), "Audio input: ${d.name}", Snackbar.LENGTH_SHORT).show()
                    }
                    4 -> androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.navigation_monitor)
                    5 -> androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.navigation_messages)
                    6 -> com.js8call.example.ui.HeartbeatOffsetDialog.show(requireContext())
                }
                renderToggles()
                true
            }
            popup.show()
        }

        fun renderPill() {
            val running = monitor.isRunning.value == true
            val tx = transmit.txState.value == TransmitState.TRANSMITTING
            val (label, text, bg) = when {
                tx -> Triple(R.string.traffic_state_tx_stop, R.color.tac_ground, R.drawable.bg_pill_tx)
                running -> Triple(R.string.traffic_state_rx, R.color.tac_good_text, R.drawable.bg_pill_rx)
                else -> Triple(R.string.traffic_state_off, R.color.tac_text_dim, R.drawable.bg_pill_off)
            }
            pill.setText(label)
            pill.setTextColor(ContextCompat.getColor(requireContext(), text))
            pill.setBackgroundResource(bg)
        }
        monitor.isRunning.observe(viewLifecycleOwner) { renderPill() }
        transmit.txState.observe(viewLifecycleOwner) { renderPill() }
        // The pill shows state; the Receiver switch next to it starts and stops listening.
        pill.setOnClickListener {
            // While sending, the TX pill is the stop button: drop the queue and unkey now.
            if (transmit.txState.value == TransmitState.TRANSMITTING) {
                transmit.cancelAll()
                requireContext().startService(
                    android.content.Intent(requireContext(), com.js8call.example.service.JS8EngineService::class.java)
                        .setAction(com.js8call.example.service.JS8EngineService.ACTION_HALT_TX),
                )
                Snackbar.make(requireView(), "Transmit stopped", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (monitor.isRunning.value != true) {
                Snackbar.make(requireView(), "Turn the Receiver switch on to start listening", Snackbar.LENGTH_SHORT).show()
            }
        }

        // Digest: a tab on the side rail; on phones in portrait (five tabs max) this button is the way in.
        val digest: TextView = view.findViewById(R.id.status_digest)
        digest.setOnClickListener {
            val bar = requireActivity().findViewById<NavigationBarView>(R.id.bottom_navigation)
            if (bar?.menu?.findItem(R.id.navigation_digest) != null) bar.selectedItemId = R.id.navigation_digest
            else androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.navigation_digest)
        }
        traffic.digestAlerts.observe(viewLifecycleOwner) { a ->
            val (label, textColor, bg) = when {
                a.count > 0 && a.tone == com.js8call.example.digest.DigestBuilder.Tone.RED ->
                    Triple("⚠ ${a.count}", R.color.tac_text, R.drawable.bg_pill_alert)
                a.count > 0 -> Triple("⚠ ${a.count}", R.color.tac_warn_text, R.drawable.bg_pill_off)
                else -> Triple(getString(R.string.nav_digest), R.color.tac_text_dim, R.drawable.bg_pill_off)
            }
            digest.text = label
            digest.setTextColor(ContextCompat.getColor(requireContext(), textColor))
            digest.setBackgroundResource(bg)
            digest.contentDescription = if (a.count > 0) "Digest: ${a.count} open needs or emergencies" else getString(R.string.nav_digest)
        }
    }

    /** Team: a tab on the side rail; on phones (five tabs max) the "My team" row is the way in. */
    private fun openTeam() {
        val bar = requireActivity().findViewById<NavigationBarView>(R.id.bottom_navigation)
        if (bar?.menu?.findItem(R.id.navigation_team) != null) bar.selectedItemId = R.id.navigation_team
        else androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.navigation_team)
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
            // Same as the Radio tab: the service uses this offset for automatic replies.
            requireContext().startService(
                android.content.Intent(requireContext(), com.js8call.example.service.JS8EngineService::class.java)
                    .setAction(com.js8call.example.service.JS8EngineService.ACTION_SET_TX_OFFSET)
                    .putExtra(com.js8call.example.service.JS8EngineService.EXTRA_TX_OFFSET_HZ, hz),
            )
        }
        monitor.status.observe(viewLifecycleOwner) { status ->
            waterfall.txOffsetHz = status.txOffsetHz
            overlay.invalidate()
        }
        traffic.markers.observe(viewLifecycleOwner) { overlay.markers = it }
        transmit.onAir.observe(viewLifecycleOwner) { air ->
            waterfall.onAirBandwidthHz = air?.second
            waterfall.onAirHz = air?.first
        }
        // Our own signal decoded back somewhere other than where we sent it: show where the
        // receiver heard it (for 3 minutes) and say so once per session, with the numbers.
        traffic.txArtifacts.observe(viewLifecycleOwner) { waterfall.txArtifactsHz = it }
        val clearEcho = Runnable { waterfall.heardBackHz = null }
        traffic.ownEcho.observe(viewLifecycleOwner) { echo ->
            waterfall.removeCallbacks(clearEcho)
            val age = echo?.let { System.currentTimeMillis() - it.atMs } ?: Long.MAX_VALUE
            if (echo == null || !echo.mismatch || age > ECHO_SHOW_MS) {
                waterfall.heardBackHz = null
                return@observe
            }
            waterfall.heardBackHz = echo.heardHz
            waterfall.postDelayed(clearEcho, ECHO_SHOW_MS - age)
            if (!echoMismatchShown) {
                echoMismatchShown = true
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Your signal was heard at a different offset")
                    .setMessage(
                        "Your receiver picked up your own transmission at ${echo.heardHz.toInt()} Hz, " +
                            "but it was sent at ${echo.sentHz.toInt()} Hz " +
                            "(${if (echo.offByHz > 0) "+" else ""}${echo.offByHz.toInt()} Hz).\n\n" +
                            "The waterfall now marks where it was heard in blue. Each reading is saved in " +
                            "Settings → Diagnostics → Share diagnostic report, so the cause can be pinned down.",
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
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

        val stations = StationAdapter(onLongClick = { stationLongPress(it) }) { row ->
            when {
                row.key == TrafficViewModel.TEAM -> openTeam()
                row.key == TrafficViewModel.WATCH -> askWatch()
                row.key == TrafficViewModel.INBOX ->
                    androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.navigation_messages)
                // Tapping the selected station again goes back to everyone.
                row.selected && row.key != TrafficViewModel.ALL -> traffic.select(TrafficViewModel.ALL)
                else -> traffic.select(row.key)
            }
        }
        stationLongPress = { row ->
            if (row.isGroup && (row.key.startsWith("@") || row.key.startsWith("#"))) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Stop watching ${row.key}?")
                    .setMessage("It disappears from this list (it comes back if new traffic to it is heard).")
                    .setPositiveButton("Stop watching") { _, _ -> traffic.unwatch(row.key) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            } else false
        }
        traffic.safeStart.observe(viewLifecycleOwner) { msg ->
            if (msg == null) return@observe
            traffic.safeStartShown()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Started in safe mode")
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        view.findViewById<View>(R.id.thread_clear).setOnClickListener { traffic.select(TrafficViewModel.ALL) }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.thread_relay).setOnClickListener {
            if (relayVia != null) { relayVia = null; renderRelay() } else showRelayFinder()
        }
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
        // Follow the newest traffic until the user scrolls up; then hold their place and offer
        // a "Latest" button to jump back and follow again.
        val latest: com.google.android.material.button.MaterialButton = view.findViewById(R.id.thread_latest)
        val threadLm = threadList.layoutManager as LinearLayoutManager
        fun renderLatest() {
            latest.isVisible = !followLatest && thread.itemCount > 0
            latest.text = if (newWhileAway > 0) getString(R.string.traffic_latest_new, newWhileAway) else getString(R.string.traffic_latest)
        }
        threadList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // Only the user's own scrolling changes follow mode (jumps made here are instant).
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING || newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val atEnd = !rv.canScrollVertically(1)
                    if (atEnd != followLatest) {
                        followLatest = atEnd
                        if (atEnd) newWhileAway = 0
                        renderLatest()
                    }
                }
            }
        })
        latest.setOnClickListener {
            followLatest = true
            newWhileAway = 0
            renderLatest()
            if (thread.itemCount > 0) threadList.scrollToPosition(thread.itemCount - 1)
        }
        traffic.thread.observe(viewLifecycleOwner) { items ->
            // Where the user is reading: the first visible card and its distance from the top
            // (scrollToPositionWithOffset measures from the top unless the layout is reversed).
            var anchorKey: Any? = null
            var anchorOffset = 0
            if (!followLatest) {
                val pos = threadLm.findFirstVisibleItemPosition()
                threadLm.findViewByPosition(pos)?.let { child ->
                    anchorKey = thread.currentList.getOrNull(pos)?.let(::threadKey)
                    anchorOffset = child.top - threadList.paddingTop
                }
                val before = thread.currentList.map(::threadKey).toHashSet()
                newWhileAway += items.count { threadKey(it) !in before }
            }
            thread.submitList(items) {
                if (items.isEmpty()) return@submitList
                if (followLatest) {
                    threadList.scrollToPosition(items.size - 1)
                } else {
                    val idx = anchorKey?.let { key -> items.indexOfFirst { threadKey(it) == key } } ?: -1
                    if (idx >= 0) threadLm.scrollToPositionWithOffset(idx, anchorOffset)
                }
                renderLatest()
            }
            empty.isVisible = items.isEmpty()
            if (items.isEmpty()) empty.text = emptyText()
        }
        traffic.selected.observe(viewLifecycleOwner) {
            // A newly opened thread starts at its newest message, following.
            followLatest = true
            newWhileAway = 0
            view.findViewById<View>(R.id.thread_latest).isVisible = false
            thread.notifyDataSetChanged()
            view.findViewById<View>(R.id.thread_clear).isVisible = it != TrafficViewModel.ALL
            if (relayFor != it) { relayVia = null; relayFor = null }
            renderRelay()
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
        view.findViewById<Chip>(R.id.tpl_speed).let { chip ->
            com.js8call.example.ui.SpeedChip.bind(chip, "Speed: %s")
            chip.setOnCloseIconClickListener { chip.performClick() }
        }

        // Report buttons open the report writer for the current selection.
        fun openWriter(tag: String) {
            if (childFragmentManager.findFragmentByTag(ReportWriterSheet.TAG) != null) return
            ReportWriterSheet.newInstance(tag, traffic.sendTarget()).show(childFragmentManager, ReportWriterSheet.TAG)
        }
        // Heartbeat: one tap, always to @HB (the service adds your grid and picks the heartbeat offset).
        view.findViewById<Chip>(R.id.tpl_heartbeat).setOnClickListener { queueBroadcast("HB") }
        view.findViewById<Chip>(R.id.tpl_cq).setOnClickListener { queueBroadcast("CQ CQ CQ") }
        view.findViewById<Chip>(R.id.tpl_checkin).setOnClickListener { openWriter("CHECKIN") }
        view.findViewById<Chip>(R.id.tpl_spot).setOnClickListener { openWriter("SPOT") }
        view.findViewById<Chip>(R.id.tpl_salute).setOnClickListener { openWriter("SALUTE") }
        view.findViewById<Chip>(R.id.tpl_sitrep).setOnClickListener { openWriter("SITREP") }
        view.findViewById<Chip>(R.id.tpl_posrep).setOnClickListener { openWriter("POSREP") }
        childFragmentManager.setFragmentResultListener(ReportWriterSheet.RESULT_KEY, viewLifecycleOwner) { _, result ->
            val line = result.getString(ReportWriterSheet.ARG_LINE).orEmpty()
            if (result.getBoolean(ReportWriterSheet.ARG_SEND)) {
                send(line, if (repeat.isChecked) 3 else 1)
            } else {
                input.setText(line)
                input.setSelection(input.text.length)
                input.requestFocus()
            }
        }

        // Text handed over from another tab (e.g. "Share solar data" on Prop).
        traffic.pendingCompose.observe(viewLifecycleOwner) { text ->
            if (text == null) return@observe
            input.setText(text)
            input.setSelection(input.text.length)
            input.requestFocus()
            traffic.composeConsumed()
        }

        ComposeBarController(
            fragment = this,
            root = view,
            commandMenuRes = R.menu.directed_command_menu,
            onSend = { text -> send(text, if (repeat.isChecked) 3 else 1) },
        )
    }

    private var stationLongPress: (TrafficViewModel.StationRow) -> Boolean = { false }

    /** Thread list follows the newest message until the user scrolls up. */
    private var followLatest = true
    private var newWhileAway = 0

    private fun threadKey(item: TrafficViewModel.ThreadItem): Any = when (item) {
        is TrafficViewModel.ThreadItem.Incoming -> "i" to item.message.id
        is TrafficViewModel.ThreadItem.Outgoing -> "o" to item.sentMs
    }

    /** Relay chosen for the selected station ([relayFor]); sends go "RELAY>TARGET>text". */
    private var relayVia: String? = null
    private var relayFor: String? = null

    private fun selectedStation(): String? = traffic.selected.value?.takeIf {
        it != TrafficViewModel.ALL && !it.startsWith("@") && !it.startsWith("#") && !it.startsWith("§")
    }

    private fun renderRelay() {
        val v = view ?: return
        val station = selectedStation()
        val button = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.thread_relay)
        button.isVisible = station != null
        button.text = relayVia?.let { getString(R.string.relay_via, it) } ?: getString(R.string.relay_find)
        val sel = traffic.selected.value ?: TrafficViewModel.ALL
        v.findViewById<EditText>(R.id.compose_input).hint = when {
            sel == TrafficViewModel.ALL -> getString(R.string.traffic_hint_all)
            relayVia != null && station != null -> getString(R.string.relay_hint, station, relayVia)
            else -> getString(R.string.traffic_hint_to, sel)
        }
    }

    /** Who could carry a message to the selected station, from traffic already heard. */
    private fun showRelayFinder() {
        val target = selectedStation() ?: return
        val ctx = requireContext()
        val plan = traffic.relayPlan(target)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val dim = ContextCompat.getColor(ctx, R.color.tac_text_dim)
        val text = ContextCompat.getColor(ctx, R.color.tac_text)
        fun line(s: CharSequence, color: Int = text, size: Float = 15f, top: Int = pad / 2) = android.widget.TextView(ctx).apply {
            this.text = s
            setTextColor(color)
            textSize = size
            setPadding(0, top, 0, 0)
        }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(line(when {
            plan.targetHeardUs != null -> getString(R.string.relay_direct_ok, target, legWhen(plan.targetHeardUs))
            plan.weHearTarget != null -> getString(R.string.relay_direct_oneway, target, legWhen(plan.weHearTarget))
            else -> getString(R.string.relay_direct_none, target)
        }))
        var dialog: androidx.appcompat.app.AlertDialog? = null
        if (plan.options.isEmpty()) {
            box.addView(line(getString(R.string.relay_none, target), dim, 14f))
        } else {
            box.addView(line(getString(R.string.relay_options_header, target), dim, 13.5f, pad))
            plan.options.forEach { o ->
                val row = line(
                    android.text.SpannableStringBuilder().apply {
                        val start = length
                        append("via ${o.relay}  ·  ${quality(o.score)}")
                        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, 0)
                        append("\n").append(legText("You ↔ ${o.relay}", o.relay, o.toRelay))
                        append("\n").append(legText("${o.relay} ↔ $target", o.relay, o.relayToTarget, target))
                    },
                    text, 14.5f, pad / 2,
                ).apply {
                    val tv = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setPadding(0, pad / 2, 0, pad / 2)
                    setOnClickListener {
                        relayVia = o.relay
                        relayFor = target
                        renderRelay()
                        dialog?.dismiss()
                    }
                }
                box.addView(row)
            }
        }
        box.addView(line(getString(R.string.relay_footer), dim, 12.5f, pad))
        dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.relay_title, target))
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun quality(score: Double) = when {
        score >= 0.5 -> "good"
        score >= 0.25 -> "fair"
        else -> "weak"
    }

    private fun ago(ms: Long): String {
        val min = ms / 60_000L
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            else -> "${min / 60} h ${min % 60} min ago"
        }
    }

    private fun snrText(snr: Int?) = snr?.let { if (it >= 0) "+$it dB" else "\u2212${-it} dB" }

    private fun legWhen(leg: RelayFinder.Leg) = listOfNotNull(snrText(leg.snr)?.let { "at $it" }, ago(leg.ageMs)).joinToString(", ")

    /** One leg in plain words. */
    private fun legText(label: String, relay: String, leg: RelayFinder.Leg, target: String? = null): String {
        val snr = snrText(leg.snr)
        val what = when (leg.kind) {
            RelayFinder.Leg.Kind.HEARD_US -> "$relay heard you" + (snr?.let { " at $it" } ?: "")
            RelayFinder.Leg.Kind.WE_HEAR -> "you hear $relay" + (snr?.let { " at $it" } ?: "") + " (no report of it hearing you)"
            RelayFinder.Leg.Kind.REPORTED -> {
                val heardBy = leg.reporter ?: relay
                val heard = if (heardBy == relay) target ?: "" else relay
                "$heardBy heard $heard" + (snr?.let { " at $it" } ?: "")
            }
            RelayFinder.Leg.Kind.WORKED -> "heard working each other"
            RelayFinder.Leg.Kind.CALLED -> "one called the other, no reply heard"
        }
        return "$label: $what, ${ago(leg.ageMs)}"
    }

    /**
     * Start the receiver, but if the saved sound card (the radio interface) isn't plugged
     * in, ask first instead of quietly listening on the tablet's own microphone.
     */
    private fun startReceiverChecked() {
        val ctx = context ?: return
        val missing = com.js8call.example.ui.AudioDevices.missingSaved(ctx)
        if (missing == null) {
            com.js8call.example.ui.RadioControls.start(ctx, monitor)
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.audio_missing_title, missing))
            .setMessage(getString(R.string.audio_missing_text, missing))
            .setPositiveButton(R.string.audio_missing_retry) { _, _ -> startReceiverChecked() }
            .setNeutralButton(R.string.audio_missing_use_mic) { _, _ -> com.js8call.example.ui.RadioControls.start(ctx, monitor) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { view?.findViewById<View>(R.id.receiver_switch)?.post { monitor.status.value?.let { monitor.updateState(it.state) } } }
            .show()
    }

    /** Microphone permission for the receiver switch; starts the receiver once granted. */
    private val micPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startReceiverChecked()
        else Snackbar.make(requireView(), R.string.permission_audio_denied, Snackbar.LENGTH_LONG).show()
    }

    private fun setAutoReply(on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putBoolean(KEY_AUTOREPLY, on).apply()
        Snackbar.make(
            requireView(),
            if (on) "Auto-reply on: your station answers SNR?, GRID?, heartbeats and stored-message queries while the app is open."
            else "Auto-reply off",
            Snackbar.LENGTH_LONG,
        ).show()
    }

    private fun setScreenOn(on: Boolean) {
        // MainActivity applies keep_screen_on to the window when it changes.
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putBoolean(KEY_SCREEN_ON, on).apply()
        Snackbar.make(requireView(), if (on) "Screen stays on" else "Screen turns off normally", Snackbar.LENGTH_SHORT).show()
    }

    /** "+ Watch a group or #tag": add an @group (traffic to it) or #tag (any message carrying it). */
    private fun askWatch() {
        val input = EditText(requireContext()).apply {
            hint = "@GHOSTNET or #SITREP"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(requireContext()).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Watch a group or tag")
            .setMessage("@NAME shows traffic sent to that group. #TAG shows every message that contains the tag. Long-press it later to stop watching.")
            .setView(box)
            .setPositiveButton("Watch") { _, _ ->
                val added = traffic.watch(input.text.toString())
                if (added == null) Snackbar.make(requireView(), "Type a group like @GHOSTNET or a tag like #SITREP", Snackbar.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun emptyText(): String {
        val running = monitor.isRunning.value == true
        val key = traffic.selected.value ?: TrafficViewModel.ALL
        return when {
            !running && key == TrafficViewModel.ALL -> getString(R.string.traffic_empty)
            key == TrafficViewModel.ALL -> getString(R.string.traffic_empty_listening)
            else -> getString(R.string.traffic_empty_station, key)
        }
    }

    private fun send(text: String, copies: Int): Boolean {
        if (!readyToSend()) return false
        val target = traffic.sendTarget()
        val via = relayVia
        if (via != null && target != null && target == relayFor) {
            repeat(copies) { transmit.queueRelayMessage(via, target, text) }
            Snackbar.make(requireView(), getString(R.string.relay_queued, target, via), Snackbar.LENGTH_SHORT).show()
            return true
        }
        if (target == null && text.trim().uppercase().startsWith("MSG ")) {
            Snackbar.make(requireView(), "A stored message (MSG) goes to one station: pick it in the list first.", Snackbar.LENGTH_LONG).show()
            return false
        }
        // A query typed to everyone ("STATUS?", "GRID?") needs an address or no one's software
        // treats it as a question: send it to @ALLCALL, as JS8Call does.
        val effective = target ?: ALLCALL_QUERY.takeIf { it.matches(text.trim().uppercase()) }?.let { "@ALLCALL" }
        repeat(copies) {
            if (effective == null) transmit.queueManualMessage(text) else transmit.queueThreadMessage(effective, text)
        }
        val msg = if (copies > 1) getString(R.string.traffic_queued_n, copies) else getString(R.string.traffic_queued)
        Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
        return true
    }
}
