package com.js8call.example.ui.traffic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.js8call.core.JS8Engine
import com.js8call.example.R
import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.example.reports.Reports

/**
 * Pop-up report writer: one form per report type, with a live preview of the
 * line that goes on air and its airtime. Returns the line to the caller via
 * the fragment result [RESULT_KEY] (with [ARG_LINE] and [ARG_SEND]).
 */
class ReportWriterSheet : BottomSheetDialogFragment() {

    private lateinit var type: Reports.Type
    private var target: String? = null
    private val values = linkedMapOf<String, String>()
    private val inputs = mutableMapOf<String, EditText>()
    private var preview: TextView? = null
    private var airtime: TextView? = null

    /** MGRS digits per axis: 1 = 10 km, 2 = 1 km, 3 = 100 m, 4 = 10 m; 0 = Maidenhead grid. */
    private var precision = 2
    private var pendingLocationKey: String? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val key = pendingLocationKey ?: return@registerForActivityResult
        if (granted) fillMyPosition(key) else fillFromGrid(key)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_report_writer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        type = Reports.byTag(requireArguments().getString(ARG_TYPE).orEmpty()) ?: run { dismiss(); return }
        target = requireArguments().getString(ARG_TARGET)
        precision = prefs().getInt(PREF_PRECISION, 2)

        view.findViewById<TextView>(R.id.report_badge).text = type.tag
        view.findViewById<TextView>(R.id.report_title).text = type.title
        view.findViewById<TextView>(R.id.report_to).text =
            target?.let { getString(R.string.traffic_to, it) } ?: getString(R.string.report_to_everyone)
        preview = view.findViewById(R.id.report_preview)
        airtime = view.findViewById(R.id.report_airtime)

        val container: LinearLayout = view.findViewById(R.id.report_fields)
        type.fields.forEach { container.addView(buildField(it)) }

        view.findViewById<MaterialButton>(R.id.report_send).setOnClickListener { finish(send = true) }
        view.findViewById<MaterialButton>(R.id.report_edit).setOnClickListener { finish(send = false) }
        updatePreview()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun finish(send: Boolean) {
        val line = Reports.encode(type, values)
        if (send && values.values.none { it.isNotBlank() }) {
            Snackbar.make(requireView(), R.string.report_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        setFragmentResult(RESULT_KEY, bundleOf(ARG_LINE to line, ARG_SEND to send))
        dismiss()
    }

    // ---------------------------------------------------------------- fields

    private fun buildField(field: Reports.Field): View {
        val ctx = requireContext()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        when (field.kind) {
            Reports.Kind.CHOICE -> {
                column.addView(label(field.label))
                val group = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = false }
                field.options.forEach { option ->
                    group.addView(Chip(ctx, null, com.google.android.material.R.attr.chipStyle).apply {
                        text = option
                        isCheckable = true
                        textSize = 16f
                        minHeight = dp(48)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) values[field.key] = option
                            else if (values[field.key] == option) values.remove(field.key)
                            updatePreview()
                        }
                    })
                }
                column.addView(group)
            }
            Reports.Kind.LOCATION -> {
                val input = textInput(field, field.label, getString(R.string.report_location_hint))
                column.addView(input.first)
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.report_my_position)
                    minHeight = dp(48)
                    setOnClickListener { useMyPosition(field.key) }
                })
                val precisionGroup = ChipGroup(ctx).apply {
                    isSingleSelection = true; isSelectionRequired = true
                    setPadding(dp(12), 0, 0, 0)
                }
                listOf(1 to "10 km", 2 to "1 km", 3 to "100 m", 4 to "10 m", 0 to getString(R.string.report_grid)).forEach { (p, labelText) ->
                    precisionGroup.addView(Chip(ctx, null, com.google.android.material.R.attr.chipStyle).apply {
                        text = labelText
                        isCheckable = true
                        isChecked = p == precision
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) {
                                precision = p
                                prefs().edit().putInt(PREF_PRECISION, p).apply()
                            }
                        }
                    })
                }
                row.addView(precisionGroup)
                column.addView(row)
            }
            Reports.Kind.TIME -> {
                val input = textInput(field, field.label, "DDHHMMZ")
                input.second.setText(Reports.dtg())
                column.addView(input.first)
                column.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.report_now)
                    minHeight = dp(48)
                    setOnClickListener { input.second.setText(Reports.dtg()) }
                })
            }
            Reports.Kind.TEXT -> column.addView(textInput(field, field.label, field.hint).first)
        }
        return column
    }

    private fun textInput(field: Reports.Field, labelText: String, hintText: String): Pair<View, EditText> {
        val ctx = requireContext()
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = labelText
            placeholderText = hintText.ifBlank { null }
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(80))
            textSize = 17f
            if (field.kind == Reports.Kind.LOCATION || field.kind == Reports.Kind.TIME) typeface = android.graphics.Typeface.MONOSPACE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString().orEmpty()
                    if (v.isBlank()) values.remove(field.key) else values[field.key] = v
                    updatePreview()
                }
            })
        }
        layout.addView(edit)
        inputs[field.key] = edit
        return layout to edit
    }

    private fun label(text: String) = TextView(requireContext()).apply {
        this.text = text.uppercase()
        textSize = 11.5f
        setTextColor(ContextCompat.getColor(context, R.color.tac_text_dim))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(4))
    }

    // ---------------------------------------------------------------- preview

    private fun updatePreview() {
        val line = Reports.encode(type, values)
        preview?.text = line
        val sent = target?.let { "$it $line" } ?: line
        val submode = com.js8call.example.service.TxOffsets.submode(requireContext())
        val frames = runCatching {
            JS8Engine.countFrames(myCall(), myGrid(), "", sent, submode)
        }.getOrDefault(-1)
        airtime?.text = if (frames > 0) {
            val seconds = frames * when (submode) {
                com.js8call.example.service.JS8EngineService.SUBMODE_SLOW -> 30
                com.js8call.example.service.JS8EngineService.SUBMODE_FAST -> 10
                com.js8call.example.service.JS8EngineService.SUBMODE_TURBO -> 6
                else -> 15
            }
            getString(R.string.report_airtime, frames, seconds / 60, seconds % 60)
        } else getString(R.string.report_airtime_unknown)
    }

    // ---------------------------------------------------------------- location

    private fun useMyPosition(key: String) {
        pendingLocationKey = key
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) fillMyPosition(key)
        else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @SuppressLint("MissingPermission")
    private fun fillMyPosition(key: String) {
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val best: Location? = runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()
        if (best == null) {
            fillFromGrid(key)
            return
        }
        val p = LatLon(best.latitude, best.longitude)
        val text = if (precision == 0) Geo.latLonToMaidenhead(p, 6) else Geo.latLonToMgrs(p, precision)
        inputs[key]?.setText(text ?: Geo.latLonToMaidenhead(p, 6))
    }

    private fun fillFromGrid(key: String) {
        val grid = myGrid()
        if (grid.isNotBlank()) {
            inputs[key]?.setText(grid)
            Snackbar.make(requireView(), R.string.report_used_grid, Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(requireView(), R.string.report_no_position, Snackbar.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(requireContext())
    private fun myCall() = prefs().getString("callsign", "").orEmpty().uppercase()
    private fun myGrid() = prefs().getString("grid", "").orEmpty().uppercase()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "report_writer"
        const val RESULT_KEY = "report_writer_result"
        const val ARG_LINE = "line"
        const val ARG_SEND = "send"
        private const val ARG_TYPE = "type"
        private const val ARG_TARGET = "target"
        private const val PREF_PRECISION = "report_location_precision"

        fun newInstance(typeTag: String, target: String?) = ReportWriterSheet().apply {
            arguments = bundleOf(ARG_TYPE to typeTag, ARG_TARGET to target)
        }
    }
}
