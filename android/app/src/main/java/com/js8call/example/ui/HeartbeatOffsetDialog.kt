package com.js8call.example.ui

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.js8call.example.R
import com.js8call.example.service.TxOffsets

/**
 * Where heartbeats (and replies to them) go out. Any offset is allowed; outside
 * the 500–1,000 Hz heartbeat zone the dialog only reminds.
 */
object HeartbeatOffsetDialog {

    fun show(context: Context, onSaved: (() -> Unit)? = null) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val bw = TxOffsets.bandwidthHz(TxOffsets.submode(context))
        val tx = TxOffsets.txOffset(context)

        val group = RadioGroup(context)
        fun option(id: Int, title: String) = RadioButton(context).apply {
            this.id = id
            text = title
            textSize = 16f
        }.also { group.addView(it) }
        option(ID_ZONE, context.getString(R.string.hb_offset_zone))
        option(ID_TX, context.getString(R.string.hb_offset_tx, tx.toInt()))
        option(ID_FIXED, context.getString(R.string.hb_offset_fixed))

        val number = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = context.getString(R.string.hb_offset_fixed_hint)
            setText(TxOffsets.hbFixed(context).toInt().toString())
        }
        val note = TextView(context).apply {
            textSize = 13.5f
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.tac_text_dim))
            setPadding(0, pad / 2, 0, 0)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(group)
            addView(number, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(note)
        }

        fun chosenHz(): Float? = when (group.checkedRadioButtonId) {
            ID_TX -> tx
            ID_FIXED -> number.text?.toString()?.trim()?.toFloatOrNull()
            else -> null
        }
        fun refresh() {
            number.isEnabled = group.checkedRadioButtonId == ID_FIXED
            val hz = chosenHz()
            note.text = when {
                group.checkedRadioButtonId == ID_ZONE -> context.getString(R.string.hb_offset_note_zone)
                hz == null -> context.getString(R.string.hb_offset_note_enter)
                TxOffsets.inZone(hz, bw) -> context.getString(R.string.hb_offset_note_inside, hz.toInt())
                else -> context.getString(R.string.hb_offset_note_outside, hz.toInt())
            }
        }
        group.check(
            when (TxOffsets.hbMode(context)) {
                TxOffsets.HB_TX -> ID_TX
                TxOffsets.HB_FIXED -> ID_FIXED
                else -> ID_ZONE
            },
        )
        group.setOnCheckedChangeListener { _, _ -> refresh() }
        number.doAfterTextChanged { refresh() }
        refresh()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.hb_offset_title)
            .setView(box)
            .setPositiveButton(R.string.hb_offset_save) { _, _ ->
                when (group.checkedRadioButtonId) {
                    ID_TX -> TxOffsets.setHeartbeat(context, TxOffsets.HB_TX)
                    ID_FIXED -> {
                        val hz = number.text?.toString()?.trim()?.toFloatOrNull()
                        if (hz != null && hz >= 0f) TxOffsets.setHeartbeat(context, TxOffsets.HB_FIXED, hz)
                    }
                    else -> TxOffsets.setHeartbeat(context, TxOffsets.HB_ZONE)
                }
                onSaved?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One line for Settings and menus: where the next heartbeat goes. */
    fun summary(context: Context): String = when (TxOffsets.hbMode(context)) {
        TxOffsets.HB_TX -> context.getString(R.string.hb_offset_summary_tx)
        TxOffsets.HB_FIXED -> context.getString(R.string.hb_offset_summary_fixed, TxOffsets.hbFixed(context).toInt())
        else -> context.getString(R.string.hb_offset_summary_zone, TxOffsets.nextHeartbeat(context).toInt())
    }

    private const val ID_ZONE = 0x4b01
    private const val ID_TX = 0x4b02
    private const val ID_FIXED = 0x4b03
}
