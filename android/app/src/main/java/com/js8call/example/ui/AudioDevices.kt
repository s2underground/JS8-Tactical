package com.js8call.example.ui

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.js8call.example.R
import com.js8call.example.service.JS8EngineService

/**
 * The capture inputs the engine can listen on. The list, the stored selection
 * and the live switch live together here; screens only render the choice.
 */
object AudioDevices {

    data class Device(val id: Int, val name: String) {
        override fun toString(): String = name
    }

    /** True when rig audio arrives over the serial link instead of a microphone. */
    fun usesSerialAudio(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString("rig_type", "none") == "trusdx_serial"

    /** The display name for a capture device, or null for types not offered. */
    fun nameFor(device: AudioDeviceInfo): String? = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal Microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> device.productName?.toString() ?: "USB Audio Device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Input"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Line Input"
        // Phone-call, broadcast-tuner and internal routing inputs can't carry rig audio.
        in NOT_RIG_INPUTS -> null
        // Any other input Android reports (a radio's built-in sound card can show up under an
        // unexpected type) is offered rather than hidden, under the name the device gives itself.
        else -> (device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio input") + " (type ${device.type})"
    }

    /** Telephony, FM/TV tuner, remote submix and echo reference. */
    private val NOT_RIG_INPUTS = setOf(18, 16, 17, 25, 28)

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "type $type"
    }

    /**
     * Every audio device and USB device Android reports, for the diagnostics report: what a
     * radio's sound card or serial chip looks like on this phone, and whether the app offers it.
     */
    fun diagnostics(context: Context): String = buildString {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        append("Inputs:\n")
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            append("  id ${d.id} ${typeName(d.type)} \"${d.productName}\" offered as: ${nameFor(d) ?: "(not offered)"}")
            append(" · rates ${d.sampleRates.joinToString(",").ifEmpty { "any" }} · channels ${d.channelCounts.joinToString(",").ifEmpty { "any" }}\n")
        }
        append("Outputs:\n")
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            append("  id ${d.id} ${typeName(d.type)} \"${d.productName}\"\n")
        }
        append("USB devices:\n")
        val usb = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
        val list = usb?.deviceList?.values.orEmpty()
        if (list.isEmpty()) append("  (none attached)\n")
        for (u in list) {
            val classes = (0 until u.interfaceCount).joinToString(",") { i ->
                when (val c = u.getInterface(i).interfaceClass) { 1 -> "audio"; 2 -> "cdc"; 10 -> "cdc-data"; 3 -> "hid"; 255 -> "vendor"; else -> "class $c" }
            }
            val product = runCatching { u.productName }.getOrNull()
            val maker = runCatching { u.manufacturerName }.getOrNull()
            append("  %04x:%04x \"%s\" by \"%s\" · interfaces: %s · permission: %s\n".format(
                java.util.Locale.US, u.vendorId, u.productId, product, maker, classes, usb?.hasPermission(u)))
        }
        // What the USB serial driver makes of them: the ports Rig control can open for CAT. A radio
        // whose audio works but has no port here is one the serial driver doesn't recognise.
        append("USB serial ports found:\n")
        val ports = runCatching { com.js8call.core.UsbSerialPortCatalog.listPorts(context) }
        ports.onSuccess { list -> if (list.isEmpty()) append("  (none)\n") else list.forEach { append("  ${it.label}\n") } }
            .onFailure { append("  (could not list: ${it.message})\n") }
    }

    /**
     * Inputs available right now, in the order they are offered.
     *
     * A TruSDX or UV-Pro rig replaces the list: its audio arrives over the
     * serial link, so the phone's own inputs cannot carry it.
     */
    fun list(context: Context): List<Device> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean("rig_control_enabled", false) && prefs.getString("rig_type", "none") == "btech_uvpro") {
            return listOf(Device(JS8EngineService.UVPRO_AUDIO_ID, JS8EngineService.UVPRO_AUDIO_NAME))
        }
        if (usesSerialAudio(context)) {
            return listOf(
                Device(JS8EngineService.TRUSDX_AUDIO_SERIAL_ID, JS8EngineService.TRUSDX_AUDIO_SERIAL_NAME),
                Device(JS8EngineService.TRUSDX_AUDIO_SPEAKER_ID, JS8EngineService.TRUSDX_AUDIO_SPEAKER_NAME)
            )
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = mutableListOf<Device>()
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .mapNotNull { d -> nameFor(d)?.let { d to it } }
        // Phones often have two built-in mics with the same name; tell them apart by where Android
        // says they are ("bottom", "back"), or number them when it doesn't say.
        val counts = inputs.groupingBy { it.second }.eachCount()
        val seen = mutableMapOf<String, Int>()
        for ((device, name) in inputs) {
            if ((counts[name] ?: 0) < 2) { devices.add(Device(device.id, name)); continue }
            val n = (seen[name] ?: 0) + 1
            seen[name] = n
            val where = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) device.address.takeIf { it.isNotBlank() } else null
            devices.add(Device(device.id, "$name (${where ?: n})"))
        }

        if (devices.isEmpty()) {
            devices.add(Device(DEFAULT_DEVICE_ID, "Default Microphone"))
        }
        return devices
    }

    /**
     * The device the engine will capture from. A saved device that has since
     * been unplugged falls back to the first one available.
     */
    fun selected(context: Context): Device = selected(context, list(context))

    fun selectedName(context: Context): String = selected(context).name

    /** The saved input's name when it isn't connected right now (start would fall back to another input). */
    fun missingSaved(context: Context): String? {
        val savedName = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_SELECTED_NAME, null) ?: return null
        val devices = list(context)
        val savedId = PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_SELECTED_ID, DEFAULT_DEVICE_ID)
        if (devices.any { it.id == savedId || it.name == savedName }) return null
        return savedName
    }

    /** The same resolution against a list the caller already holds. */
    fun selected(context: Context, devices: List<Device>): Device {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedId = prefs.getInt(PREF_SELECTED_ID, DEFAULT_DEVICE_ID)
        devices.firstOrNull { it.id == savedId }?.let { d ->
            // Remember the name too (selections made before names were stored)
            if (prefs.getString(PREF_SELECTED_NAME, null) != d.name) prefs.edit().putString(PREF_SELECTED_NAME, d.name).apply()
            return d
        }
        // A USB sound card gets a new ID every time it reconnects; find it again by name
        // instead of silently falling back to the tablet's microphone.
        val savedName = prefs.getString(PREF_SELECTED_NAME, null)
        if (savedName != null) devices.firstOrNull { it.name == savedName }?.let { return it }
        return devices.first()
    }

    /**
     * Remember the choice, and move a live capture onto it. Returns true when
     * a switch request was dispatched; the service ignores a request for the
     * device it is already capturing on, since only it knows the active one.
     */
    fun select(context: Context, device: Device, engineRunning: Boolean): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getInt(PREF_SELECTED_ID, DEFAULT_DEVICE_ID) != device.id || prefs.getString(PREF_SELECTED_NAME, null) != device.name) {
            prefs.edit().putInt(PREF_SELECTED_ID, device.id).putString(PREF_SELECTED_NAME, device.name).apply()
        }

        if (!engineRunning) return false
        val intent = Intent(context, JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_SWITCH_AUDIO_DEVICE
            putExtra(JS8EngineService.EXTRA_AUDIO_DEVICE_ID, device.id)
        }
        context.startService(intent)
        return true
    }

    /**
     * Show the picker. [onSelected] runs after the choice is stored, so the
     * caller only has to refresh whatever it shows the device on.
     */
    fun showPicker(
        context: Context,
        engineRunning: Boolean,
        onSelected: (Device) -> Unit
    ) {
        val devices = list(context)
        val current = selected(context, devices)
        val checked = devices.indexOf(current)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.monitor_menu_audio_device)
            .setSingleChoiceItems(devices.map { it.name }.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val device = devices[which]
                select(context, device, engineRunning)
                onSelected(device)
            }
            .show()
    }

    private const val PREF_SELECTED_ID = "last_audio_device_id"
    /** The chosen device's name, to find a USB sound card again after it reconnects with a new ID. */
    const val PREF_SELECTED_NAME = "last_audio_device_name"
    private const val DEFAULT_DEVICE_ID = -1
}
