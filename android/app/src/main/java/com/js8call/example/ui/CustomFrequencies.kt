package com.js8call.example.ui

import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.js8call.example.R
import java.util.Locale

/**
 * The operator's own frequencies, each with a name ("GhostNet", "Local Net"), listed after the
 * presets wherever a band is picked (issue #65). Any frequency, any name: nothing here limits
 * where the radio may go. Replaces the single "Custom Frequency" setting, which is carried over.
 */
object CustomFrequencies {

    const val PREF = "custom_frequencies"
    /** The old single custom frequency (MHz), read once and carried over. */
    const val LEGACY_PREF = "custom_frequency_mhz"
    const val MAX_LABEL = 32
    const val MAX_ENTRIES = 50

    data class Entry(val hz: Long, val label: String)

    /** One entry per line: "hz<TAB>label". */
    fun parse(raw: String?): List<Entry> = raw.orEmpty().lines().mapNotNull { line ->
        val tab = line.indexOf('\t')
        val hz = (if (tab < 0) line else line.substring(0, tab)).trim().toLongOrNull() ?: return@mapNotNull null
        if (hz <= 0) return@mapNotNull null
        Entry(hz, cleanLabel(if (tab < 0) "" else line.substring(tab + 1)))
    }.distinctBy { it.hz }.take(MAX_ENTRIES)

    fun encode(list: List<Entry>): String = list.joinToString("\n") { "${it.hz}\t${cleanLabel(it.label)}" }

    /** Labels are one line, no tabs, and short enough for a band list. */
    fun cleanLabel(label: String): String =
        label.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(MAX_LABEL)

    /** MHz typed by the operator to Hz; null if it isn't a positive number. */
    fun parseMhz(text: String): Long? {
        val mhz = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!mhz.isFinite() || mhz <= 0 || mhz > 100_000) return null
        return Math.round(mhz * 1_000_000.0)
    }

    /** 7107000 → "7.107"; 14078500 → "14.0785". */
    fun mhzText(hz: Long): String =
        String.format(Locale.US, "%.6f", hz / 1_000_000.0).trimEnd('0').trimEnd('.')

    /** How an entry reads in a band list, in the presets' "name - freq MHz" form. */
    fun bandLabel(e: Entry): String =
        "${e.label.ifBlank { "Custom" }} - ${mhzText(e.hz)} MHz"

    /** The saved list, carrying over the old single custom frequency the first time. */
    fun load(context: Context): List<Entry> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.contains(PREF)) return parse(prefs.getString(PREF, null))
        val legacy = prefs.getString(LEGACY_PREF, null)?.let { parseMhz(it) }
        val list = listOfNotNull(legacy?.let { Entry(it, "") })
        prefs.edit { putString(PREF, encode(list)) }
        return list
    }

    fun save(context: Context, list: List<Entry>) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF, encode(list.distinctBy { it.hz }.take(MAX_ENTRIES)))
            remove(LEGACY_PREF)
        }
    }

    /** The list, with add, edit and delete. [onChanged] runs after every change. */
    fun manage(context: Context, onChanged: () -> Unit = {}) {
        val list = load(context)
        val items = list.map { bandLabel(it) }.toTypedArray()
        val b = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.custom_freq_title)
            .setPositiveButton(R.string.custom_freq_add) { _, _ -> edit(context, null) { onChanged(); manage(context, onChanged) } }
            .setNegativeButton(R.string.custom_freq_done, null)
        if (items.isEmpty()) b.setMessage(R.string.custom_freq_empty)
        else b.setItems(items) { _, which -> edit(context, list[which]) { onChanged(); manage(context, onChanged) } }
        b.show()
    }

    /** Add (entry null) or edit one frequency. */
    fun edit(context: Context, entry: Entry?, onChanged: () -> Unit) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val freqField = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(entry?.let { mhzText(it.hz) }.orEmpty())
        }
        val labelField = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(entry?.label.orEmpty())
        }
        val freqLayout = TextInputLayout(context).apply { hint = context.getString(R.string.custom_freq_mhz_hint); addView(freqField) }
        val labelLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.custom_freq_label_hint)
            counterMaxLength = MAX_LABEL
            isCounterEnabled = true
            addView(labelField)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(freqLayout); addView(labelLayout)
        }
        val b = MaterialAlertDialogBuilder(context)
            .setTitle(if (entry == null) R.string.custom_freq_add_title else R.string.custom_freq_edit_title)
            .setView(box)
            .setPositiveButton(R.string.custom_freq_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (entry != null) {
            b.setNeutralButton(R.string.custom_freq_delete) { _, _ ->
                save(context, load(context).filterNot { it.hz == entry.hz })
                onChanged()
            }
        }
        val dialog = b.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hz = parseMhz(freqField.text?.toString().orEmpty())
                if (hz == null) {
                    freqLayout.error = context.getString(R.string.custom_freq_bad)
                    return@setOnClickListener
                }
                val updated = Entry(hz, cleanLabel(labelField.text?.toString().orEmpty()))
                val rest = load(context).filterNot { it.hz == entry?.hz || it.hz == hz }
                // Edits keep their place in the list; new ones go at the end.
                val at = entry?.let { e -> load(context).indexOfFirst { it.hz == e.hz } }?.takeIf { it >= 0 } ?: rest.size
                save(context, rest.toMutableList().apply { add(at.coerceAtMost(size), updated) })
                dialog.dismiss()
                onChanged()
            }
        }
        dialog.show()
    }
}
