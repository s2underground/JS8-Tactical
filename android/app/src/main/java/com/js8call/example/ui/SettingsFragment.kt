package com.js8call.example.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.js8call.core.BluetoothSerialPortCatalog
import com.js8call.core.HamlibRigCatalog
import com.js8call.core.UsbSerialPortCatalog
import com.js8call.example.BuildConfig
import com.js8call.example.R
import com.js8call.example.atak.AtakBridge
import com.js8call.example.reports.Geo
import java.util.Locale

/**
 * Fragment for app settings.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val locationHandler = Handler(Looper.getMainLooper())
    private var pendingLocationListener: LocationListener? = null
    private var pendingLocationTimeout: Runnable? = null
    private var gridPreference: GridSquarePreference? = null
    private var audioDevicePreference: Preference? = null
    private var pendingStoragePermissionEnable = false
    private var logPreference: SwitchPreferenceCompat? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestGridLocation()
            } else {
                showGridError(R.string.permission_location_denied)
            }
        }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                view?.let { Snackbar.make(it, R.string.permission_bluetooth_denied, Snackbar.LENGTH_LONG).show() }
            }
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingStoragePermissionEnable) {
                    pendingStoragePermissionEnable = false
                    val pref = logPreference
                    val key = pref?.key ?: "log_messages_to_file"
                    pref?.isChecked = true
                    pref?.sharedPreferences
                        ?.edit()
                        ?.putBoolean(key, true)
                        ?.apply()
                }
            } else {
                pendingStoragePermissionEnable = false
                view?.let { Snackbar.make(it, R.string.permission_storage_denied, Snackbar.LENGTH_LONG).show() }
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("app_version")?.summary = BuildConfig.VERSION_NAME
        findPreference<Preference>("about_licences")?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME)
                .setMessage(R.string.about_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        val prefs = preferenceManager.sharedPreferences
        if (prefs != null && !prefs.contains("my_status")) {
            val statusPref = findPreference<EditTextPreference>("my_status")
            statusPref?.text = "JS8-Tactical ${BuildConfig.VERSION_NAME}"
        }

        val callsignPref = findPreference<EditTextPreference>("callsign")
        val existingCallsign = callsignPref?.text.orEmpty().trim().uppercase(Locale.US)
        if (callsignPref != null && callsignPref.text != existingCallsign) {
            callsignPref.text = existingCallsign
        }
        callsignPref?.setOnPreferenceChangeListener { preference, newValue ->
            val normalizedValue = newValue?.toString().orEmpty().trim().uppercase(Locale.US)
            preference.sharedPreferences
                ?.edit()
                ?.putString(preference.key, normalizedValue)
                ?.apply()
            (preference as? EditTextPreference)?.text = normalizedValue
            false
        }

        val customFrequencyPref = findPreference<EditTextPreference>("custom_frequency_mhz")
        customFrequencyPref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        customFrequencyPref?.setOnPreferenceChangeListener { preference, newValue ->
            val rawValue = newValue?.toString().orEmpty().trim()
            val normalizedValue = normalizeFrequencyMhz(rawValue)
            preference.summary = if (normalizedValue.isBlank()) {
                getString(R.string.settings_custom_frequency_summary)
            } else {
                getString(R.string.settings_custom_frequency_summary_format, normalizedValue)
            }
            preference.sharedPreferences
                ?.edit()
                ?.putString(preference.key, normalizedValue)
                ?.apply()
            false
        }
        val existingCustom = customFrequencyPref?.text.orEmpty().trim()
        if (customFrequencyPref != null) {
            val normalizedExisting = normalizeFrequencyMhz(existingCustom)
            customFrequencyPref.summary = if (normalizedExisting.isBlank()) {
                getString(R.string.settings_custom_frequency_summary)
            } else {
                getString(R.string.settings_custom_frequency_summary_format, normalizedExisting)
            }
        }

        val rigModelPref = findPreference<ListPreference>("rig_hamlib_model")
        if (rigModelPref != null) {
            val models = try {
                HamlibRigCatalog.listRigModels()
            } catch (_: Throwable) {
                emptyList()
            }

            val entries = if (models.isNotEmpty()) {
                models.map { it.label }.toTypedArray()
            } else {
                arrayOf(getString(R.string.settings_hamlib_model_none))
            }
            val values = if (models.isNotEmpty()) {
                models.map { it.id }.toTypedArray()
            } else {
                arrayOf("0")
            }
            rigModelPref.entries = entries
            rigModelPref.entryValues = values
        }

        val rigUsbPortPref = findPreference<ListPreference>("rig_hamlib_usb_port")
        if (rigUsbPortPref != null) {
            val usbPorts = try {
                UsbSerialPortCatalog.listPorts(requireContext())
            } catch (_: Throwable) {
                emptyList()
            }
            val btPorts = try {
                BluetoothSerialPortCatalog.listPorts(requireContext())
            } catch (_: Throwable) {
                emptyList()
            }

            val entries = ArrayList<String>()
            val values = ArrayList<String>()
            if (usbPorts.isEmpty() && btPorts.isEmpty()) {
                entries.add(getString(R.string.settings_hamlib_usb_port_none))
                values.add("auto")
            } else {
                entries.add(getString(R.string.settings_hamlib_usb_port_auto))
                values.add("auto")
                usbPorts.forEach { port ->
                    entries.add("USB ${port.label}")
                    values.add("android-usb:${port.deviceId}:${port.portIndex}")
                }
                btPorts.forEach { port ->
                    entries.add(port.label)
                    values.add("android-bt:${port.address}:${port.portIndex}")
                }
            }

            val currentValueRaw = prefs?.getString("rig_hamlib_usb_port", "auto") ?: "auto"
            val normalizedValue = normalizeSerialSelection(currentValueRaw, prefs, useLegacyFallback = true)
            if (normalizedValue != currentValueRaw) {
                prefs?.edit()?.putString("rig_hamlib_usb_port", normalizedValue)?.apply()
            }

            if (!values.contains(normalizedValue)) {
                entries.add(getString(R.string.settings_hamlib_usb_port_previous, normalizedValue))
                values.add(normalizedValue)
            }

            rigUsbPortPref.entries = entries.toTypedArray()
            rigUsbPortPref.entryValues = values.toTypedArray()
            rigUsbPortPref.value = normalizedValue

            rigUsbPortPref.setOnPreferenceChangeListener { _, newValue ->
                val selection = normalizeSerialSelection(
                    newValue?.toString().orEmpty(),
                    prefs,
                    useLegacyFallback = false
                )
                updateLegacyUsbPrefs(prefs, selection)
                true
            }
        }

        audioDevicePreference = findPreference("audio_device")
        audioDevicePreference?.setOnPreferenceClickListener {
            val engineRunning = ViewModelProvider(requireActivity())[MonitorViewModel::class.java]
                .isRunning.value == true
            AudioDevices.showPicker(requireContext(), engineRunning) { updateAudioDeviceSummary() }
            true
        }
        // Some rigs(e.g. TruSDX) replace the device list
        // The new val is stored after this returns so read it next pass
        findPreference<ListPreference>("rig_type")?.setOnPreferenceChangeListener { _, newValue ->
            view?.post { updateAudioDeviceSummary() }
            if (newValue == "btech_uvpro") {
                view?.let { Snackbar.make(it, R.string.settings_btech_uvpro_squelch_hint, Snackbar.LENGTH_LONG).show() }
            }
            if (newValue == "btech_uvpro" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            true
        }
        // The UV-Pro input is offered only while rig control is on
        findPreference<SwitchPreferenceCompat>("rig_control_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            view?.post { updateAudioDeviceSummary() }
            updateVoxPreambleEnabled(rigEnabled = newValue as? Boolean ?: false)
            true
        }
        updateVoxPreambleEnabled()

        gridPreference = findPreference("grid")
        gridPreference?.onUpdateClickListener = { onGridUpdateRequested() }

        setUpAtak()
        setUpHistory()
        setUpDiagnostics()
        findPreference<Preference>("open_ai")?.setOnPreferenceClickListener {
            androidx.navigation.fragment.NavHostFragment.findNavController(this).navigate(R.id.navigation_ai)
            true
        }
        findPreference<Preference>("open_library")?.setOnPreferenceClickListener {
            com.js8call.example.ui.library.AppLinks.open(this, "library")
            true
        }

        logPreference = findPreference("log_messages_to_file")
        logPreference?.setOnPreferenceChangeListener { _, newValue ->
            val enable = newValue as? Boolean ?: false
            if (enable && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                val granted = ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    pendingStoragePermissionEnable = true
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return@setOnPreferenceChangeListener false
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------ ATAK

    private fun setUpAtak() {
        val target = findPreference<ListPreference>(AtakBridge.KEY_TARGET)
        fun showCustom(value: String?) {
            val custom = value == "custom"
            findPreference<Preference>(AtakBridge.KEY_HOST)?.isVisible = custom
            findPreference<Preference>(AtakBridge.KEY_PORT)?.isVisible = custom
        }
        showCustom(target?.value)
        target?.setOnPreferenceChangeListener { _, v ->
            showCustom(v as? String)
            AtakBridge.resendAll()
            true
        }
        findPreference<EditTextPreference>(AtakBridge.KEY_PORT)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
        }
        listOf(AtakBridge.KEY_HOST, AtakBridge.KEY_PORT, AtakBridge.KEY_ENABLED).forEach { key ->
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ -> AtakBridge.resendAll(); true }
        }
        findPreference<Preference>("atak_legend")?.summary = atakLegend()
        findPreference<Preference>("atak_help")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_atak_help)
                .setMessage(R.string.settings_atak_help_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }
        findPreference<Preference>("atak_test")?.apply {
            summary = atakStatusText()
            setOnPreferenceClickListener {
                val grid = preferenceManager.sharedPreferences?.getString("grid", "").orEmpty()
                val where = Geo.maidenheadToLatLon(grid.trim())
                if (where == null) {
                    view?.let { v -> Snackbar.make(v, "Set your grid square first (General → Grid), so the test point lands near you.", Snackbar.LENGTH_LONG).show() }
                    return@setOnPreferenceClickListener true
                }
                val label = AtakBridge.target(requireContext()).label
                AtakBridge.sendTest(requireContext(), where) { err -> postAtakResult(err?.let { "Couldn't send: $it" } ?: "Test point sent to $label. Look for a cyan dot in ATAK.") }
                true
            }
        }
        findPreference<Preference>("atak_remove")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_atak_remove)
                .setMessage("Every point JS8-Tactical has sent will be deleted from ATAK, and from any other TAK device that received them. New reports will still be sent while \"Show reports in ATAK\" is on.")
                .setPositiveButton("Remove") { _, _ ->
                    AtakBridge.removeAll(requireContext()) { n, err ->
                        postAtakResult(err?.let { "Couldn't send: $it" } ?: "Sent delete for $n point${if (n == 1) "" else "s"}.")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    // ------------------------------------------------------------------ history

    /** Settings → Diagnostics: build the report off the main thread, then hand it to a share sheet. */
    private fun setUpDiagnostics() {
        findPreference<Preference>("time_sync_gps")?.setOnPreferenceClickListener {
            GpsTimeSyncDialog.show(childFragmentManager)
            true
        }
        findPreference<Preference>("diagnostics_last_error")?.setOnPreferenceClickListener {
            val act = activity as? androidx.appcompat.app.AppCompatActivity
            if (act == null || !com.js8call.example.CrashReporter.showLast(act)) {
                view?.let { v -> Snackbar.make(v, "No error reports on this device.", Snackbar.LENGTH_SHORT).show() }
            }
            true
        }
        findPreference<Preference>("hb_offset")?.let { pref ->
            pref.summary = HeartbeatOffsetDialog.summary(requireContext())
            pref.setOnPreferenceClickListener {
                HeartbeatOffsetDialog.show(requireContext()) { pref.summary = HeartbeatOffsetDialog.summary(requireContext()) }
                true
            }
        }
        findPreference<Preference>("diagnostics_share")?.setOnPreferenceClickListener {
            val ctx = requireContext().applicationContext
            view?.let { v -> Snackbar.make(v, "Collecting diagnostics…", Snackbar.LENGTH_SHORT).show() }
            Thread {
                val text = runCatching { com.js8call.example.CrashReporter.diagnosticReport(ctx) }
                    .getOrElse { "Could not build the report: ${com.js8call.example.CrashReporter.failure(it)}" }
                val file = java.io.File(ctx.cacheDir, "diagnostics").apply { mkdirs() }.resolve("js8-tactical-diagnostics.txt")
                runCatching { file.writeText(text) }
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        // Pre-addressed for email apps (others ignore it); the subject says which build.
                        putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(getString(R.string.bug_report_email)))
                        putExtra(android.content.Intent.EXTRA_SUBJECT,
                            "JS8-Tactical bug report, ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
                        runCatching {
                            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.diagnostics", file)
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }.onFailure { putExtra(android.content.Intent.EXTRA_TEXT, text.takeLast(90_000)) }
                    }
                    startActivity(android.content.Intent.createChooser(send, "Share diagnostics"))
                }
            }.start()
            true
        }
    }

    private fun setUpHistory() {
        val traffic = com.js8call.example.ui.traffic.TrafficViewModel.of(requireContext())
        findPreference<ListPreference>(com.js8call.example.ui.traffic.TrafficHistory.KEY_KEEP_HOURS)?.setOnPreferenceChangeListener { _, _ ->
            // Applied after the value is saved.
            Handler(Looper.getMainLooper()).post { if (isAdded) traffic.historySettingChanged(); updateHistorySummary() }
            true
        }
        findPreference<Preference>("history_clear")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_history_clear)
                .setMessage(R.string.settings_history_clear_confirm)
                .setPositiveButton("Delete") { _, _ ->
                    traffic.clearHistory()
                    Handler(Looper.getMainLooper()).postDelayed({ if (isAdded) updateHistorySummary() }, 300)
                    view?.let { v -> Snackbar.make(v, "Message history deleted.", Snackbar.LENGTH_SHORT).show() }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        updateHistorySummary()
    }

    private fun updateHistorySummary() {
        val traffic = com.js8call.example.ui.traffic.TrafficViewModel.of(requireContext())
        val kb = traffic.historySizeBytes() / 1024.0
        findPreference<Preference>("history_clear")?.summary = when {
            kb <= 0.0 -> "Nothing saved on this device"
            kb < 1024 -> String.format(Locale.US, "%.0f KB saved on this device", kb.coerceAtLeast(1.0))
            else -> String.format(Locale.US, "%.1f MB saved on this device", kb / 1024)
        }
    }

    /** "● red  emergency / RED  ● yellow …" with real coloured dots. */
    private fun atakLegend(): CharSequence {
        val items = listOf(
            0xFFFF0000.toInt() to "emergency or RED status",
            0xFFFFFF00.toInt() to "needs or AMBER",
            0xFF00FF00.toInt() to "good or GREEN",
            0xFFFF00FF.toInt() to "SPOT and SALUTE reports",
            0xFF00FFFF.toInt() to "other reports, test point",
            0xFFFFFFFF.toInt() to "heard stations (grid-square centre)",
        )
        val sb = android.text.SpannableStringBuilder()
        items.forEachIndexed { i, (color, label) ->
            if (i > 0) sb.append("\n")
            val start = sb.length
            sb.append("●")
            sb.setSpan(android.text.style.ForegroundColorSpan(color), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ").append(label)
        }
        return sb
    }

    private fun atakStatusText(): String {
        val s = AtakBridge.status
        return when {
            s.lastError != null -> "Last send failed: ${s.lastError}"
            s.sentPackets > 0 -> "${s.sentPackets} point updates sent to ${s.target} since the app started"
            else -> "Puts a cyan dot near your grid square for 5 minutes"
        }
    }

    private fun postAtakResult(message: String) {
        Handler(Looper.getMainLooper()).post {
            if (!isAdded) return@post
            findPreference<Preference>("atak_test")?.summary = atakStatusText()
            view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = updateAudioDeviceSummary()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = updateAudioDeviceSummary()
    }

    override fun onStart() {
        super.onStart()
        updateVoxPreambleEnabled()
        audioManager().registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun audioManager(): AudioManager =
        requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun updateAudioDeviceSummary() {
        audioDevicePreference?.summary = AudioDevices.selectedName(requireContext())
    }

    private fun updateVoxPreambleEnabled(
        rigEnabled: Boolean = preferenceManager.sharedPreferences?.getBoolean("rig_control_enabled", false) ?: false
    ) {
        findPreference<ListPreference>("vox_lead_seconds")?.isEnabled = !rigEnabled
    }

    override fun onStop() {
        audioManager().unregisterAudioDeviceCallback(audioDeviceCallback)
        cancelLocationRequest()
        super.onStop()
    }

    private fun onGridUpdateRequested() {
        val context = context ?: return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestGridLocation()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestGridLocation() {
        val context = context ?: return
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        cancelLocationRequest()

        val now = System.currentTimeMillis()
        val lastLocation = findRecentLocation(locationManager, now, LOCATION_MAX_AGE_MS)
        if (lastLocation != null) {
            applyGridFromLocation(lastLocation)
            return
        }

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            showGridError(R.string.error_location_update_failed)
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                cancelLocationRequest()
                applyGridFromLocation(location)
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        pendingLocationListener = listener
        try {
            locationManager.requestLocationUpdates(
                provider,
                0L,
                0f,
                listener,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            cancelLocationRequest()
            showGridError(R.string.error_location_update_failed)
            return
        }

        val timeout = Runnable {
            cancelLocationRequest()
            showGridError(R.string.error_location_update_failed)
        }
        pendingLocationTimeout = timeout
        locationHandler.postDelayed(timeout, LOCATION_TIMEOUT_MS)
    }

    private fun findRecentLocation(
        locationManager: LocationManager,
        nowMs: Long,
        maxAgeMs: Long
    ): Location? {
        val gps = getLastKnownIfFresh(
            locationManager,
            LocationManager.GPS_PROVIDER,
            nowMs,
            maxAgeMs
        )
        if (gps != null) return gps

        return getLastKnownIfFresh(
            locationManager,
            LocationManager.NETWORK_PROVIDER,
            nowMs,
            maxAgeMs
        )
    }

    private fun getLastKnownIfFresh(
        locationManager: LocationManager,
        provider: String,
        nowMs: Long,
        maxAgeMs: Long
    ): Location? {
        if (!locationManager.isProviderEnabled(provider)) return null
        val location = try {
            locationManager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            null
        } ?: return null
        return if (nowMs - location.time <= maxAgeMs) location else null
    }

    private fun cancelLocationRequest() {
        pendingLocationTimeout?.let { locationHandler.removeCallbacks(it) }
        pendingLocationTimeout = null
        val listener = pendingLocationListener
        pendingLocationListener = null
        val context = context ?: return
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listener?.let { locationManager.removeUpdates(it) }
    }

    private fun applyGridFromLocation(location: Location) {
        val grid = maidenheadFromLocation(location.latitude, location.longitude)
        gridPreference?.setGridValue(grid)
    }

    private fun showGridError(messageResId: Int) {
        val view = view ?: return
        Snackbar.make(view, messageResId, Snackbar.LENGTH_LONG).show()
    }

    private fun maidenheadFromLocation(latitude: Double, longitude: Double): String {
        var lon = -longitude
        var lat = latitude.coerceIn(-90.0, 90.0)
        if (lon < -180.0) lon += 360.0
        if (lon > 180.0) lon -= 360.0
        if (lon == 180.0) lon = 179.999999
        if (lat == 90.0) lat = 89.999999

        val lonMinutes = (180.0 - lon) * 60.0
        val latMinutes = (lat + 90.0) * 60.0

        val lonField = (lonMinutes / 1200.0).toInt().coerceIn(0, 17)
        val latField = (latMinutes / 600.0).toInt().coerceIn(0, 17)

        val lonFieldRemainder = lonMinutes - lonField * 1200.0
        val latFieldRemainder = latMinutes - latField * 600.0

        val lonSquare = (lonFieldRemainder / 120.0).toInt().coerceIn(0, 9)
        val latSquare = (latFieldRemainder / 60.0).toInt().coerceIn(0, 9)

        val lonSquareRemainder = lonFieldRemainder - lonSquare * 120.0
        val latSquareRemainder = latFieldRemainder - latSquare * 60.0

        val lonSub = (lonSquareRemainder / 5.0).toInt().coerceIn(0, 23)
        val latSub = (latSquareRemainder / 2.5).toInt().coerceIn(0, 23)

        val lonSubRemainder = lonSquareRemainder - lonSub * 5.0
        val latSubRemainder = latSquareRemainder - latSub * 2.5

        val lonExt = (lonSubRemainder / 0.5).toInt().coerceIn(0, 9)
        val latExt = (latSubRemainder / 0.25).toInt().coerceIn(0, 9)

        return buildString(8) {
            append(('A'.code + lonField).toChar())
            append(('A'.code + latField).toChar())
            append(('0'.code + lonSquare).toChar())
            append(('0'.code + latSquare).toChar())
            append(('A'.code + lonSub).toChar())
            append(('A'.code + latSub).toChar())
            append(('0'.code + lonExt).toChar())
            append(('0'.code + latExt).toChar())
        }.uppercase(Locale.US)
    }

    private fun normalizeSerialSelection(
        selection: String,
        prefs: android.content.SharedPreferences?,
        useLegacyFallback: Boolean
    ): String {
        val trimmed = selection.trim()
        if (trimmed.startsWith("android-usb:") || trimmed.startsWith("android-bt:")) {
            return trimmed
        }
        if (trimmed.isEmpty() || trimmed == "auto") {
            if (!useLegacyFallback) {
                return "auto"
            }
            val deviceId = prefs?.getString("rig_usb_device_id", "")?.toIntOrNull()
            val portIndex = prefs?.getString("rig_usb_port_index", "0")?.toIntOrNull() ?: 0
            return if (deviceId != null) {
                "android-usb:$deviceId:$portIndex"
            } else {
                "auto"
            }
        }
        val parts = trimmed.split(':', limit = 2)
        val deviceId = parts.firstOrNull()?.toIntOrNull()
        return if (deviceId != null) {
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            "android-usb:$deviceId:$portIndex"
        } else {
            trimmed
        }
    }

    private fun updateLegacyUsbPrefs(
        prefs: android.content.SharedPreferences?,
        selection: String
    ) {
        val editor = prefs?.edit() ?: return
        if (selection.startsWith("android-usb:")) {
            val remainder = selection.removePrefix("android-usb:")
            val parts = remainder.split(':', limit = 2)
            val deviceId = parts.firstOrNull()?.toIntOrNull()
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (deviceId != null) {
                editor.putString("rig_usb_device_id", deviceId.toString())
                editor.putString("rig_usb_port_index", portIndex.toString())
            }
        } else {
            editor.putString("rig_usb_device_id", "")
            editor.putString("rig_usb_port_index", "0")
        }
        editor.apply()
    }

    private fun normalizeFrequencyMhz(value: String): String {
        val trimmed = value.trim()
        val parsed = trimmed.toDoubleOrNull() ?: return ""
        if (parsed <= 0) return ""
        return if (parsed % 1.0 == 0.0) {
            parsed.toLong().toString()
        } else {
            String.format(Locale.US, "%.4f", parsed).trimEnd('0').trimEnd('.')
        }
    }

    private companion object {
        const val LOCATION_MAX_AGE_MS = 15 * 60 * 1000L
        const val LOCATION_TIMEOUT_MS = 20 * 1000L
    }
}
