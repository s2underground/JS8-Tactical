package com.js8call.example.ui

import android.widget.PopupMenu
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import android.widget.TextView
import com.js8call.example.R
import com.js8call.example.service.JS8EngineService

/**
 * Toolbar chip showing the current TX speed; tapping opens the picker.
 * The choice is saved to the same preference the engine reads.
 */
object SpeedChip {

    private class Speed(val submode: Int, @param:StringRes val label: Int)

    private val speeds = listOf(
        Speed(JS8EngineService.SUBMODE_SLOW, R.string.tx_speed_slow),
        Speed(JS8EngineService.SUBMODE_NORMAL, R.string.tx_speed_normal),
        Speed(JS8EngineService.SUBMODE_FAST, R.string.tx_speed_fast),
        Speed(JS8EngineService.SUBMODE_TURBO, R.string.tx_speed_turbo)
    )

    /** [format]: how the label is shown, e.g. "Speed: %s"; default just the speed's name. */
    fun bind(chip: TextView, format: String = "%s") {
        chip.setTag(R.id.speed_chip, format)
        render(chip)
        chip.setOnClickListener { showPicker(chip) }
    }

    private fun render(chip: TextView) {
        val format = chip.getTag(R.id.speed_chip) as? String ?: "%s"
        chip.text = String.format(format, chip.context.getString(current(chip).label))
    }

    private fun showPicker(chip: TextView) {
        val popup = PopupMenu(chip.context, chip)
        speeds.forEachIndexed { index, speed -> popup.menu.add(0, index, index, speed.label) }
        popup.menu.setGroupCheckable(0, true, true)
        popup.menu.getItem(speeds.indexOf(current(chip))).isChecked = true

        popup.setOnMenuItemClickListener { item ->
            val speed = speeds[item.itemId]
            PreferenceManager.getDefaultSharedPreferences(chip.context)
                .edit()
                .putInt(JS8EngineService.PREF_TX_SUBMODE, speed.submode)
                .apply()
            render(chip)
            true
        }
        popup.show()
    }

    private fun current(chip: TextView): Speed {
        val submode = PreferenceManager.getDefaultSharedPreferences(chip.context)
            .getInt(JS8EngineService.PREF_TX_SUBMODE, JS8EngineService.SUBMODE_NORMAL)
        return speeds.firstOrNull { it.submode == submode }
            ?: speeds.first { it.submode == JS8EngineService.SUBMODE_NORMAL }
    }
}
