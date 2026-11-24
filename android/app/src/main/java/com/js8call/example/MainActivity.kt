package com.js8call.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.model.EngineState
import com.js8call.example.service.JS8EngineService
import com.js8call.example.ui.DecodeViewModel
import com.js8call.example.ui.MessagesViewModel
import com.js8call.example.ui.MonitorViewModel
import com.js8call.example.ui.TransmitViewModel
import com.js8call.example.ui.traffic.TrafficViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: NavigationBarView
    private lateinit var navController: NavController
    private var onMonitorTab = true
    private lateinit var decodeViewModel: DecodeViewModel
    private lateinit var monitorViewModel: MonitorViewModel
    private var spectrumBroadcastCount: Long = 0
    private lateinit var messagesViewModel: MessagesViewModel
    private lateinit var transmitViewModel: TransmitViewModel
    private val ownSignalProbe = com.js8call.example.ui.OwnSignalProbe()
    private lateinit var trafficViewModel: TrafficViewModel

    private val decodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                JS8EngineService.ACTION_DECODE -> {
                    val utc = intent.getIntExtra(JS8EngineService.EXTRA_UTC, 0)
                    val snr = intent.getIntExtra(JS8EngineService.EXTRA_SNR, 0)
                    val dt = intent.getFloatExtra(JS8EngineService.EXTRA_DT, 0f)
                    val freq = intent.getFloatExtra(JS8EngineService.EXTRA_FREQ, 0f)
                    val text = intent.getStringExtra(JS8EngineService.EXTRA_TEXT) ?: ""
                    val type = intent.getIntExtra(JS8EngineService.EXTRA_TYPE, 0)
                    val quality = intent.getFloatExtra(JS8EngineService.EXTRA_QUALITY, 0f)
                    val mode = intent.getIntExtra(JS8EngineService.EXTRA_MODE, 0)
                    val driftMs = intent.getIntExtra(JS8EngineService.EXTRA_DRIFT_MS, 0)
                    decodeViewModel.addDecode(utc, snr, dt, freq, text, type, quality, mode, driftMs)
                    monitorViewModel.updateSnr(snr)
                }
                JS8EngineService.ACTION_TX_SENT -> {
                    val text = intent.getStringExtra(JS8EngineService.EXTRA_TX_SENT_TEXT) ?: return
                    val freq = intent.getFloatExtra(JS8EngineService.EXTRA_TX_SENT_FREQ, 0f)
                    val utc = intent.getIntExtra(JS8EngineService.EXTRA_UTC, 0)
                    decodeViewModel.addOutgoing(text, freq, utc)
                }
            }
        }
    }

    private val monitorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                JS8EngineService.ACTION_ENGINE_STATE -> {
                    val state = when (intent.getStringExtra(JS8EngineService.EXTRA_STATE)) {
                        JS8EngineService.STATE_RUNNING -> EngineState.RUNNING
                        JS8EngineService.STATE_STOPPED -> EngineState.STOPPED
                        JS8EngineService.STATE_STARTING -> EngineState.STARTING
                        JS8EngineService.STATE_ERROR -> EngineState.ERROR
                        else -> EngineState.ERROR
                    }
                    monitorViewModel.updateState(state)
                    // The send queue (TransmitViewModel) follows engine state itself.
                }
                JS8EngineService.ACTION_SPECTRUM -> {
                    val bins = intent.getFloatArrayExtra(JS8EngineService.EXTRA_BINS)
                    val binHz = intent.getFloatExtra(JS8EngineService.EXTRA_BIN_HZ, 0f)
                    val powerDb = intent.getFloatExtra(JS8EngineService.EXTRA_POWER_DB, 0f)
                    val peakDb = intent.getFloatExtra(JS8EngineService.EXTRA_PEAK_DB, 0f)
                    if (bins != null) {
                        spectrumBroadcastCount += 1
                        if (spectrumBroadcastCount % 20L == 0L) {
                            android.util.Log.i(
                                "JS8MainActivity",
                                "Spectrum broadcasts: count=$spectrumBroadcastCount bins=${bins.size} binHz=$binHz power=$powerDb peak=$peakDb"
                            )
                        }
                        monitorViewModel.updateSpectrum(bins, binHz, powerDb, peakDb)
                        // Where does our own transmission show up in what the receiver hears?
                        val air = transmitViewModel.onAir.value
                        ownSignalProbe.feed(bins, binHz, air?.first, air?.second ?: 50f)?.let { r ->
                            android.util.Log.i("JS8MainActivity", "Spectrum while sending: sent=${r.sentHz} peaks=${r.peaks} atSent=${r.riseAtSentDb} dB")
                            trafficViewModel.reportOwnSignal(r)
                        }
                    }
                }
                JS8EngineService.ACTION_AUDIO_DEVICE -> {
                    val deviceName = intent.getStringExtra(JS8EngineService.EXTRA_AUDIO_DEVICE) ?: "Unknown"
                    monitorViewModel.updateAudioDevice(deviceName)
                }
                JS8EngineService.ACTION_WARNING -> {
                    val message = intent.getStringExtra(JS8EngineService.EXTRA_ERROR_MESSAGE) ?: return
                    if (intent.getBooleanExtra(JS8EngineService.EXTRA_WARNING_QUIET, false)) {
                        android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
                        return
                    }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Check the radio connection")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                JS8EngineService.ACTION_ERROR -> {
                    val message = intent.getStringExtra(JS8EngineService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                    monitorViewModel.onError(message)
                }
                JS8EngineService.ACTION_AUDIO_DEVICE_LOST -> {
                    val device = intent.getStringExtra(JS8EngineService.EXTRA_AUDIO_DEVICE) ?: "Audio device"
                    val message = getString(R.string.monitor_audio_device_lost, device)
                    monitorViewModel.onError(message)
                    if (!onMonitorTab) Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
                JS8EngineService.ACTION_RADIO_FREQUENCY -> {
                    val frequencyHz = intent.getLongExtra(JS8EngineService.EXTRA_RADIO_FREQUENCY_HZ, 0L)
                    if (frequencyHz > 0) {
                        monitorViewModel.updateFrequency(frequencyHz)
                    }
                }
                JS8EngineService.ACTION_TIME_DRIFT -> {
                    val driftMs = intent.getLongExtra(JS8EngineService.EXTRA_TIME_DRIFT_MS, 0L)
                    monitorViewModel.updateTimeDrift(driftMs)
                }
                JS8EngineService.ACTION_RIG_STATUS -> {
                    val connected = intent.getBooleanExtra(JS8EngineService.EXTRA_RIG_CONNECTED, false)
                    monitorViewModel.updateRigConnected(connected)
                }
            }
        }
    }

    companion object {
        /** When Exit was last used (ms); a USB-triggered reopen right after it closes itself. */
        const val PREF_EXITED_AT = "exited_at_ms"
        private const val REQUEST_RECORD_AUDIO = 1
        private const val REQUEST_BLUETOOTH_CONNECT = 3
        private const val REQUEST_LOCATION = 2
        private const val PREF_KEEP_SCREEN_ON = "keep_screen_on"
        const val ACTION_PROCESS_TX_QUEUE = "com.js8call.example.ACTION_PROCESS_TX_QUEUE"
        /** Launched from the "receiver was closed" notification: start listening again. */
        const val EXTRA_RESTART_RECEIVER = "restart_receiver"
    }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == PREF_KEEP_SCREEN_ON) {
            applyKeepScreenOn(prefs.getBoolean(PREF_KEEP_SCREEN_ON, false))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        super.onCreate(savedInstanceState)
        Log.i("JS8MainActivity", "Opened by: action=${intent?.action} extras=${intent?.extras?.keySet()}")
        // Just after Exit, the radio's USB reconnecting (the rig port was closed) can ask
        // Android to open the app again. That is not the user opening it: close again.
        val exitedAgo = System.currentTimeMillis() -
            PreferenceManager.getDefaultSharedPreferences(this).getLong(PREF_EXITED_AT, 0L)
        if (savedInstanceState == null && intent?.action != Intent.ACTION_MAIN && exitedAgo in 0..15_000L) {
            Log.i("JS8MainActivity", "Reopened ${exitedAgo} ms after Exit by ${intent?.action}; closing")
            finishAndRemoveTask()
            return
        }
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) CrashReporter.showIfPending(this)
        // First run: Listener / Operator / Net control (sets the Start-here guide).
        if (savedInstanceState == null) window.decorView.post {
            if (!isFinishing) com.js8call.example.ui.onboarding.RoleChooser.showIfFirstRun(this)
        }

        // Set up bottom navigation
        bottomNav = findViewById(R.id.bottom_navigation)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        bottomNav.setupWithNavController(navController)
        // Back on the first screen sends the app to the background instead of closing it:
        // closing stops the receiver, which is never what a stray Back press in the field means.
        // (Stop the receiver with its switch, or Stop in the notification.)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.previousBackStackEntry != null) navController.popBackStack()
                else moveTaskToBack(true)
            }
        })
        navController.addOnDestinationChangedListener { controller, destination, _ ->
            onMonitorTab = destination.id == R.id.navigation_monitor
            val inMessagesTab = runCatching { controller.getBackStackEntry(R.id.navigation_messages) }.isSuccess
            if ((destination.id == R.id.navigation_conversation ||
                    destination.id == R.id.navigation_everything) && inMessagesTab
            ) {
                bottomNav.menu.findItem(R.id.navigation_messages)?.isChecked = true
            }
            if (destination.id == R.id.navigation_report_edit) bottomNav.menu.findItem(R.id.navigation_reports)?.isChecked = true
        }
        // A tab is checked only while its own screen shows, so one tap can arrive as either event
        bottomNav.setOnItemSelectedListener { item -> onNavItemTapped(navController, item) }
        bottomNav.setOnItemReselectedListener { item -> onNavItemTapped(navController, item) }

        decodeViewModel = ViewModelProvider(this)[DecodeViewModel::class.java]
        monitorViewModel = ViewModelProvider(this)[MonitorViewModel::class.java]
        messagesViewModel = ViewModelProvider(this)[MessagesViewModel::class.java]
        transmitViewModel = TransmitViewModel.of(this)
        trafficViewModel = TrafficViewModel.of(this)
        decodeViewModel.loadPersistedDecodesIfEnabled()
        // Propagation observations are tagged with the band in use.
        monitorViewModel.radioFrequency.observe(this) { hz -> trafficViewModel.setDialFrequency(hz) }
        // Listening time per band lets the propagation model learn from what was *not* heard.
        monitorViewModel.isRunning.observe(this) { running -> trafficViewModel.setListening(running == true) }

        // Observe unread count for badge on Messages tab
        messagesViewModel.totalUnreadCount.observe(this) { count -> trafficViewModel.setInboxUnread(count ?: 0) }
        messagesViewModel.totalUnreadCount.observe(this) { count ->
            // Phones in portrait have no Messages tab (five-tab limit); Traffic covers it there.
            if (bottomNav.menu.findItem(R.id.navigation_messages) == null) return@observe
            val badge = bottomNav.getOrCreateBadge(R.id.navigation_messages)
            if (count > 0) {
                badge.isVisible = true
                badge.number = count
            } else {
                badge.isVisible = false
            }
        }

        // Check permissions
        checkPermissions()
        handleLaunchIntent(intent)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        applyKeepScreenOn(prefs.getBoolean(PREF_KEEP_SCREEN_ON, false))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /** Notification taps: restart the receiver, or open a message thread. */
    private fun handleLaunchIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_RESTART_RECEIVER, false)) {
            intent.removeExtra(EXTRA_RESTART_RECEIVER)
            if (com.js8call.example.ui.RadioControls.needsMicPermission(this)) {
                checkPermissions()
            } else {
                com.js8call.example.ui.RadioControls.start(this, monitorViewModel)
                Toast.makeText(this, R.string.receiver_restarted, Toast.LENGTH_LONG).show()
            }
        }
        if (intent.getBooleanExtra("open_messages", false)) {
            intent.removeExtra("open_messages")
            val call = intent.getStringExtra("callsign")
            runCatching {
                if (call.isNullOrBlank()) navController.navigate(R.id.navigation_messages)
                else navController.navigate(R.id.navigation_conversation, bundleOf("callsign" to call))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Engine state broadcasts aren't heard while the app is in the background (a USB
        // dropout or a stop can happen then): catch up from the service's real state.
        val running = JS8EngineService.engineRunning
        val shown = monitorViewModel.status.value?.state
        if (running && shown != EngineState.RUNNING) monitorViewModel.updateState(EngineState.RUNNING)
        else if (!running && shown == EngineState.RUNNING) monitorViewModel.updateState(EngineState.STOPPED)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        applyKeepScreenOn(prefs.getBoolean(PREF_KEEP_SCREEN_ON, false))
        val filter = IntentFilter().apply {
            addAction(JS8EngineService.ACTION_DECODE)
            addAction(JS8EngineService.ACTION_TX_SENT)
        }
        AppBroadcasts.register(this, decodeReceiver, filter)

        val monitorFilter = IntentFilter().apply {
            addAction(JS8EngineService.ACTION_ENGINE_STATE)
            addAction(JS8EngineService.ACTION_SPECTRUM)
            addAction(JS8EngineService.ACTION_AUDIO_DEVICE)
            addAction(JS8EngineService.ACTION_ERROR)
            addAction(JS8EngineService.ACTION_WARNING)
            addAction(JS8EngineService.ACTION_AUDIO_DEVICE_LOST)
            addAction(JS8EngineService.ACTION_RADIO_FREQUENCY)
            addAction(JS8EngineService.ACTION_TIME_DRIFT)
            addAction(JS8EngineService.ACTION_RIG_STATUS)
        }
        AppBroadcasts.register(this, monitorReceiver, monitorFilter)
        // Belt and braces: hand over anything waiting
        transmitViewModel.processNext()
    }

    override fun onStop() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        AppBroadcasts.unregister(decodeReceiver)
        AppBroadcasts.unregister(monitorReceiver)
        decodeViewModel.persistDecodesOnStop()
        if (isFinishing && !isChangingConfigurations && !exiting) {
            stopEngineService()
        }
        super.onStop()
    }

    /**
     * Pop back to a tab when only its own screens sit above it. NavigationUI leaves the
     * current screen showing when that tab is the start destination.
     */
    private fun onNavItemTapped(navController: NavController, item: MenuItem): Boolean {
        val start = navController.graph.startDestinationId
        val onStack = { id: Int -> runCatching { navController.getBackStackEntry(id) }.isSuccess }
        val tabs = (0 until bottomNav.menu.size()).map { bottomNav.menu.getItem(it).itemId }
        val otherTabOpen = tabs.any { it != item.itemId && it != start && onStack(it) }
        if (onStack(item.itemId) && !otherTabOpen) {
            navController.popBackStack(item.itemId, false)
            return true
        }
        // A restored tab can open on a screen above its root, which NavigationUI reports as a miss
        return NavigationUI.onNavDestinationSelected(item, navController) || onStack(item.itemId)
    }

    /** Opens [callsign]'s thread on the Messages tab. */
    fun openThread(callsign: String) {
        if (bottomNav.selectedItemId != R.id.navigation_messages) {
            bottomNav.selectedItemId = R.id.navigation_messages
            navController.popBackStack(R.id.navigation_messages, false)
        }
        navController.navigate(R.id.navigation_conversation, bundleOf("callsign" to callsign))
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // Show explanation
                Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.permission_audio_message,
                    Snackbar.LENGTH_LONG
                ).setAction("Grant") {
                    requestAudioPermission()
                }.show()
            }
            else -> {
                // Request permission
                requestAudioPermission()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val hasScan = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            when {
                hasConnect && hasScan -> {
                    // Permission already granted
                }
                shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
                    shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN) -> {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Bluetooth permission needed for device access",
                        Snackbar.LENGTH_LONG
                    ).setAction("Grant") {
                        requestBluetoothPermission()
                    }.show()
                }
                else -> {
                    requestBluetoothPermission()
                }
            }
        }
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestBluetoothPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ),
            REQUEST_BLUETOOTH_CONNECT
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    // Permission granted
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Microphone permission granted",
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    // Permission denied
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        R.string.permission_audio_denied,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
            REQUEST_BLUETOOTH_CONNECT -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Bluetooth permission granted",
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Bluetooth permission denied; Bluetooth devices may not work",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Exit: off the air (receiver, heartbeats, scheduled reports), out of recent apps. */
    fun confirmExit() {
        val scheduled = com.js8call.example.reports.ReportStore.of(this).list().count { it.schedule != null }
        val queued = transmitViewModel.queue.value?.size ?: 0
        val msg = buildString {
            append(getString(R.string.exit_text))
            if (queued > 0) append("\n\n").append(resources.getQuantityString(R.plurals.exit_queued, queued, queued))
            if (scheduled > 0) append("\n\n").append(resources.getQuantityString(R.plurals.exit_scheduled, scheduled, scheduled))
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_title)
            .setMessage(msg)
            .setPositiveButton(R.string.exit_confirm) { _, _ -> exitApp() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exitApp() {
        transmitViewModel.cancelAll()
        PreferenceManager.getDefaultSharedPreferences(this).edit().putLong(PREF_EXITED_AT, System.currentTimeMillis()).apply()
        exiting = true
        stopEngineService()
        // Every window of the app, not just this one (a second copy may have been opened by the radio's USB).
        runCatching { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks.forEach { it.finishAndRemoveTask() } }
        finishAndRemoveTask()
    }

    private var exiting = false

    private fun stopEngineService() {
        val intent = Intent(this, JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_STOP
        }
        startService(intent)
    }
}
