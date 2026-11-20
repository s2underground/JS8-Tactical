package com.js8call.example.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.js8call.example.R
import com.js8call.example.model.EngineState
import com.js8call.example.service.JS8EngineService

/**
 * Receiver on/off and band choice, shared by Traffic (the main screen) and the
 * radio details screen. Same behaviour as the old Radio tab, in one place.
 */
object RadioControls {

    data class Band(val label: String, val hz: Long)

    /** The band presets the Radio tab offers (plus a custom frequency), and the saved choice. */
    fun bands(context: Context): Pair<List<Band>, Int> {
        val res = context.resources
        val baseEntries = res.getStringArray(R.array.js8_frequency_entries)
        val baseValues = res.getStringArray(R.array.js8_frequency_values)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rigType = prefs.getString("rig_type", "none")
        val qmxBands = when (prefs.getString("qmx_band_profile", "low")) {
            "high" -> setOf("20m", "17m", "15m", "12m", "11m", "10m")
            "mixed" -> setOf("60m", "40m", "30m", "20m", "17m", "15m")
            else -> setOf("80m", "60m", "40m", "30m", "20m")
        }
        val list = baseEntries.indices
            .map { Band(baseEntries[it], baseValues[it].toLongOrNull() ?: 0L) }
            .filter { rigType != "qmx_serial" || qmxBands.any { b -> it.label.startsWith(b) } }
            .toMutableList()
        val customMhz = prefs.getString("custom_frequency_mhz", "")?.trim().orEmpty()
        customMhz.toDoubleOrNull()?.takeIf { it > 0 }?.let { list += Band("Custom - ${customMhz}MHz", (it * 1_000_000.0).toLong()) }
        val defaultHz = if (rigType == "qmx_serial") list.firstOrNull()?.hz ?: 14_078_000L else (baseValues.getOrNull(3)?.toLongOrNull() ?: 14_078_000L)
        val saved = prefs.getString("last_frequency", defaultHz.toString())?.toLongOrNull() ?: defaultHz
        val index = list.indexOfFirst { it.hz == saved }.takeIf { it >= 0 } ?: list.indexOfFirst { it.hz == defaultHz }.coerceAtLeast(0)
        return list to index
    }

    /** "20m - 14.078 MHz" → "20m · 14.078". */
    fun shortLabel(entry: String): String = entry.removeSuffix(" MHz").replace(" - ", " · ")

    /** Save the band and tune the radio (the service ignores this without a rig link). */
    fun selectBand(context: Context, band: Band) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString("last_frequency", band.hz.toString()).apply()
        context.startService(
            Intent(context, JS8EngineService::class.java)
                .setAction(JS8EngineService.ACTION_SET_FREQUENCY)
                .putExtra(JS8EngineService.EXTRA_FREQUENCY_HZ, band.hz)
        )
    }

    /** Start the receiver on the saved audio input. The caller has checked the microphone permission. */
    fun start(context: Context, monitor: MonitorViewModel) {
        val state = monitor.status.value?.state
        if (state == EngineState.RUNNING || state == EngineState.STARTING) return
        monitor.startMonitoring()
        val device = AudioDevices.selected(context)
        ContextCompat.startForegroundService(
            context,
            Intent(context, JS8EngineService::class.java)
                .setAction(JS8EngineService.ACTION_START)
                .putExtra(JS8EngineService.EXTRA_AUDIO_DEVICE_ID, device.id)
        )
    }

    fun stop(context: Context, monitor: MonitorViewModel) {
        monitor.stopMonitoring()
        context.startService(Intent(context, JS8EngineService::class.java).setAction(JS8EngineService.ACTION_STOP))
    }

    /** True when starting needs the microphone permission first (serial-audio rigs don't). */
    fun needsMicPermission(context: Context): Boolean =
        !AudioDevices.usesSerialAudio(context) &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
