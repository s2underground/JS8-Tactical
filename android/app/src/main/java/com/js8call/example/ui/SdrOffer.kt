package com.js8call.example.ui

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.js8call.example.R
import com.js8call.example.service.JS8EngineService
import com.js8call.sdr.AirspyHf
import com.js8call.sdr.RtlSdr

/**
 * An SDR dongle is a receiver, not a sound card, so it never shows up among the audio inputs on
 * its own; it is picked as the Rig type. Field report (build 342): with the Airspy HF+ plugged
 * in, the audio list only showed the tablet's microphones. This offers the switch where people
 * look for it (the audio list, and when the dongle is plugged in), and offers the way back to the
 * radio setup afterwards. Nothing changes without the operator saying yes.
 */
object SdrOffer {

    /** An SDR that is plugged in: its Rig type value and the name shown for it. */
    data class Found(val rigType: String, val label: String)

    /** The rig type and audio input in use before switching to an SDR, to switch back to. */
    private const val PREF_RIG_TYPE_BEFORE = "sdr_offer_rig_type_before"
    private const val PREF_RIG_ENABLED_BEFORE = "sdr_offer_rig_enabled_before"
    private const val PREF_AUDIO_NAME_BEFORE = "sdr_offer_audio_name_before"

    fun classify(vendorId: Int, productId: Int): Found? = when {
        AirspyHf.isKnown(vendorId, productId) -> Found("airspy_hf", "Airspy HF+")
        RtlSdr.isKnown(vendorId, productId) -> Found("rtl_sdr", "RTL-SDR")
        else -> null
    }

    /** The first known SDR plugged in right now, if any. */
    fun attached(context: Context): Found? {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return runCatching { usb.deviceList.values }.getOrDefault(emptyList())
            .firstNotNullOfOrNull { classify(it.vendorId, it.productId) }
    }

    /** The SDR named by a USB-attached intent, if it is one. */
    fun fromIntent(intent: Intent?): Found? {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device: UsbDevice = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }) ?: return null
        return classify(device.vendorId, device.productId)
    }

    /** True when this SDR is already the receiver. */
    fun inUse(context: Context, found: Found): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("rig_control_enabled", false) && prefs.getString("rig_type", "none") == found.rigType
    }

    /** Ask, then make [found] the receiver. [onDone] runs after a yes. */
    fun confirm(context: Context, found: Found, onDone: () -> Unit = {}) {
        if (inUse(context, found)) return
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.sdr_offer_title, found.label))
            .setMessage(context.getString(R.string.sdr_offer_text, found.label))
            .setPositiveButton(R.string.sdr_offer_use) { _, _ ->
                use(context, found)
                onDone()
            }
            .setNegativeButton(R.string.sdr_offer_not_now, null)
            .show()
    }

    private fun use(context: Context, found: Found) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val fromRadio = AudioDevices.sdrLabel(context) == null
        prefs.edit {
            // Remember the radio setup (not when going from one SDR to another).
            if (fromRadio) {
                putString(PREF_RIG_TYPE_BEFORE, prefs.getString("rig_type", "none"))
                putBoolean(PREF_RIG_ENABLED_BEFORE, prefs.getBoolean("rig_control_enabled", false))
                putString(PREF_AUDIO_NAME_BEFORE, prefs.getString(AudioDevices.PREF_SELECTED_NAME, null))
            }
            // The receiver restarts on its own when the rig settings change.
            putBoolean("rig_control_enabled", true)
            putString("rig_type", found.rigType)
            putInt(AudioDevices.PREF_SELECTED_ID_KEY, JS8EngineService.RTLSDR_AUDIO_ID)
            putString(AudioDevices.PREF_SELECTED_NAME, found.label)
        }
    }

    /** The radio setup an SDR replaced, as its Rig type label, or null if there is none to go back to. */
    fun previousSetup(context: Context): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val type = prefs.getString(PREF_RIG_TYPE_BEFORE, null) ?: return null
        val values = context.resources.getStringArray(R.array.rig_type_values)
        val labels = context.resources.getStringArray(R.array.rig_type_entries)
        val i = values.indexOf(type)
        return if (i >= 0) labels[i] else type
    }

    /** Ask, then go back to the radio setup the SDR replaced. */
    fun confirmBack(context: Context, onDone: () -> Unit = {}) {
        val label = previousSetup(context) ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.sdr_back_title)
            .setMessage(context.getString(R.string.sdr_back_text, label))
            .setPositiveButton(R.string.sdr_back_switch) { _, _ ->
                back(context)
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun back(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val type = prefs.getString(PREF_RIG_TYPE_BEFORE, null) ?: return
        val audioName = prefs.getString(PREF_AUDIO_NAME_BEFORE, null)
        val enabledBefore = prefs.getBoolean(PREF_RIG_ENABLED_BEFORE, true)
        prefs.edit {
            putBoolean("rig_control_enabled", enabledBefore)
            putString("rig_type", type)
            // The input is found again by name (USB sound cards get a new ID each time).
            putInt(AudioDevices.PREF_SELECTED_ID_KEY, Int.MIN_VALUE)
            putString(AudioDevices.PREF_SELECTED_NAME, audioName)
            remove(PREF_RIG_TYPE_BEFORE)
            remove(PREF_RIG_ENABLED_BEFORE)
            remove(PREF_AUDIO_NAME_BEFORE)
        }
    }
}
