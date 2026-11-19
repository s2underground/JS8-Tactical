package com.js8call.example.ui.library

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.js8call.example.library.Calculators
import com.js8call.example.prop.Band
import java.util.Locale

/** Calculator cards embedded in guides with `[[calc:id]]`. */
object CalcViews {

    private val BANDS = listOf(Band.B80, Band.B60, Band.B40, Band.B30, Band.B20, Band.B17, Band.B15, Band.B10)

    fun create(ctx: Context, id: String, defaultBand: Band?): View = when (id) {
        "dipole" -> bandCalc(ctx, "Dipole and inverted V lengths", defaultBand) { f ->
            val total = Calculators.dipoleTotalFt(f)
            val iv = Calculators.invertedVTotalFt(f)
            listOf(
                "Flat dipole, total: ${Calculators.ftAndM(total)}",
                "Each leg: ${Calculators.ftAndM(total / 2)}",
                "Inverted V, each leg: ${Calculators.ftAndM(iv / 2)}",
                "Cut each leg about 5% longer (${Calculators.ftAndM(total / 2 * 1.05)}), fold the extra back, then trim for lowest SWR.",
            )
        }
        "efhw" -> bandCalc(ctx, "End-fed half-wave (49:1 transformer)", defaultBand ?: Band.B40) { f ->
            listOf(
                "Radiator: ${Calculators.ftAndM(Calculators.efhwRadiatorFt(f))}",
                "Counterpoise, about: ${Calculators.ftAndM(Calculators.efhwCounterpoiseFt(f))}",
                "Cut for the lowest band you want. A 40 m EFHW usually also works on 20, 15 and 10 m.",
            )
        }
        "vertical" -> bandCalc(ctx, "Quarter-wave vertical", defaultBand ?: Band.B20) { f ->
            listOf(
                "Vertical element: ${Calculators.ftAndM(Calculators.quarterWaveFt(f))}",
                "Raised radials (each, at least 2): ${Calculators.ftAndM(Calculators.quarterWaveFt(f))}",
                "Radials lying on the ground can be any length; use as many as you can (8–16 is good).",
            )
        }
        "nvis" -> bandCalc(ctx, "NVIS antenna height", defaultBand ?: Band.B40) { f ->
            listOf(
                "Height above ground: ${Calculators.rangeFtAndM(Calculators.nvisHeightFt(f))}",
                "Lower end of the range is fine; just keep the ends out of reach.",
                "One wavelength here: ${Calculators.ftAndM(Calculators.wavelengthM(f) * Calculators.FT_PER_M)}",
            )
        }
        "wavelength" -> bandCalc(ctx, "Wavelength", defaultBand) { f ->
            val m = Calculators.wavelengthM(f)
            listOf(
                "One wavelength (λ): ${Calculators.ftAndM(m * Calculators.FT_PER_M)}",
                "Half wave: ${Calculators.ftAndM(m * Calculators.FT_PER_M / 2)} · quarter wave: ${Calculators.ftAndM(m * Calculators.FT_PER_M / 4)}",
                "These are free-space lengths; wire antennas are cut about 5% shorter (the 468/f rule).",
            )
        }
        "feedline" -> feedline(ctx, defaultBand)
        "airtime" -> airtime(ctx)
        "rf" -> rf(ctx, defaultBand)
        "swr" -> swr(ctx)
        else -> TextView(ctx).apply { text = "Calculator \"$id\"" }
    }

    // ---------------------------------------------------------------- building blocks

    private fun card(ctx: Context, title: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val d = resources.displayMetrics.density
        setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 10 * d
            setColor(0xFF161B22.toInt())
            setStroke((1 * d).toInt(), 0xFF5A4520.toInt())
        }
        addView(TextView(ctx).apply {
            text = "▸ $title"
            setTextColor(0xFFF2A33A.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun resultView(ctx: Context) = TextView(ctx).apply {
        setTextColor(0xFFE6EAF0.toInt())
        textSize = 15f
        setLineSpacing(0f, 1.2f)
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    /** Band chips plus a custom-frequency box; [compute] gives result lines for a frequency in MHz. */
    private fun bandCalc(ctx: Context, title: String, defaultBand: Band?, compute: (Double) -> List<String>): View {
        val card = card(ctx, title)
        val result = resultView(ctx)
        val freq = EditText(ctx).apply {
            hint = "or type MHz"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 14f
            setTextColor(0xFFE6EAF0.toInt())
            setHintTextColor(0xFF7D8794.toInt())
            minEms = 5
        }
        fun show(mhz: Double?) {
            result.text = if (mhz == null || mhz <= 0.1 || mhz > 60) "Pick a band or type a frequency (MHz)."
            else "At ${String.format(Locale.US, "%.3f", mhz)} MHz:\n" + compute(mhz).joinToString("\n") { "• $it" }
        }
        val chips = ChipGroup(ctx).apply { isSingleSelection = true; isSingleLine = true }
        BANDS.forEach { b ->
            chips.addView(Chip(ctx).apply {
                text = b.label
                isCheckable = true
                isChecked = b == (defaultBand ?: Band.B40)
                setOnClickListener { freq.setText(""); show(b.mhz) }
            })
        }
        freq.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toDoubleOrNull() ?: return
                chips.clearCheck(); show(v)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        card.addView(freq)
        card.addView(result)
        show((defaultBand ?: Band.B40).mhz)
        return card
    }

    private fun feedline(ctx: Context, defaultBand: Band?): View {
        val card = card(ctx, "Feedline loss")
        val result = resultView(ctx)
        var coax = Calculators.Coax.RG8X
        var band = defaultBand ?: Band.B40
        val length = number(ctx, "Length (feet)", "50")
        val power = number(ctx, "Radio power (watts)", "20")
        fun show() {
            val ft = length.text.toString().toDoubleOrNull()
            val w = power.text.toString().toDoubleOrNull()
            if (ft == null || w == null || ft <= 0 || w <= 0) { result.text = "Enter length and power."; return }
            val r = Calculators.feedline(coax, ft, band.mhz, w)
            result.text = String.format(
                Locale.US,
                "• %s, %.0f ft on %s: %.2f dB loss\n• %.1f W of %.0f W reaches the antenna (%.0f%% lost as heat)\n• Matched line; a high SWR adds more loss.",
                coax.label, ft, band.label, r.lossDb, r.powerOutW, w, r.percentLost,
            )
        }
        val coaxChips = ChipGroup(ctx).apply { isSingleSelection = true; isSingleLine = true }
        Calculators.Coax.entries.forEach { cx ->
            coaxChips.addView(Chip(ctx).apply { text = cx.label; isCheckable = true; isChecked = cx == coax; setOnClickListener { coax = cx; show() } })
        }
        val bandChips = ChipGroup(ctx).apply { isSingleSelection = true; isSingleLine = true }
        BANDS.forEach { b ->
            bandChips.addView(Chip(ctx).apply { text = b.label; isCheckable = true; isChecked = b == band; setOnClickListener { band = b; show() } })
        }
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = show()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        length.addTextChangedListener(watcher); power.addTextChangedListener(watcher)
        card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(coaxChips) })
        card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(bandChips) })
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(labeled(ctx, "Length, feet", length))
            addView(labeled(ctx, "Radio power, watts", power))
        })
        card.addView(result)
        show()
        return card
    }

    /** How long a message takes at each speed, framed exactly as the radio would send it. */
    private fun airtime(ctx: Context): View {
        val card = card(ctx, "How long will my message take?")
        val result = resultView(ctx)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val myCall = prefs.getString("callsign", "").orEmpty().ifBlank { "N0CALL" }
        val myGrid = prefs.getString("grid", "").orEmpty()
        val msg = EditText(ctx).apply {
            setText("W1AW MEET AT THE RIVER BRIDGE AT 1800")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 14f
            setTextColor(0xFFE6EAF0.toInt())
        }
        fun show() {
            val text = msg.text.toString().trim().uppercase()
            if (text.isEmpty()) { result.text = "Type a message."; return }
            val lines = Calculators.Speed.entries.map { sp ->
                val frames = try {
                    com.js8call.core.JS8Engine.countFrames(myCall, myGrid, "", text, sp.submode)
                } catch (_: Throwable) { -1 }
                if (frames > 0) "• ${sp.label}: $frames frame${if (frames == 1) "" else "s"}, about ${Calculators.duration(Calculators.airtimeSeconds(frames, sp))}"
                else "• ${sp.label}: can't count here"
            }
            result.text = "Sent from $myCall:\n" + lines.joinToString("\n") +
                "\nShorter words and fewer characters mean fewer frames. Your callsign and the recipient's take space in the first frame."
        }
        msg.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = show()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        card.addView(labeled(ctx, "Message (as you would type it, e.g. \"W1AW HELLO\")", msg))
        card.addView(result)
        show()
        return card
    }

    /** Dial frequency + audio offset = where the signal really is. */
    private fun rf(ctx: Context, defaultBand: Band?): View {
        val card = card(ctx, "Where is a signal on the band?")
        val result = resultView(ctx)
        var band = defaultBand ?: Band.B40
        val offset = number(ctx, "Offset (Hz)", "1500")
        fun show() {
            val off = offset.text.toString().toDoubleOrNull()
            if (off == null || off < 0 || off > 3500) { result.text = "Enter an offset between 0 and 3,000 Hz."; return }
            val rf = Calculators.rfMHz(band.mhz, off)
            val area = when {
                off < 500 -> "below the usual JS8 area"
                off < 1000 -> "the heartbeat area (500–1,000 Hz)"
                off <= 2500 -> "the traffic area (1,000–2,500 Hz)"
                else -> "above the usual traffic area"
            }
            result.text = String.format(Locale.US, "• Dial %.3f MHz + %.0f Hz = %.6f MHz on the air\n• That offset is in %s.", band.mhz, off, rf, area)
        }
        val chips = ChipGroup(ctx).apply { isSingleSelection = true; isSingleLine = true }
        BANDS.forEach { b -> chips.addView(Chip(ctx).apply { text = b.label; isCheckable = true; isChecked = b == band; setOnClickListener { band = b; show() } }) }
        offset.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = show()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        card.addView(labeled(ctx, "Audio offset on the waterfall, Hz", offset))
        card.addView(result)
        show()
        return card
    }

    private fun swr(ctx: Context): View {
        val card = card(ctx, "What does my SWR mean?")
        val result = resultView(ctx)
        val swr = number(ctx, "SWR (e.g. 1.5)", "1.5")
        fun show() {
            val s = swr.text.toString().toDoubleOrNull()
            if (s == null || s < 1.0) { result.text = "Enter an SWR of 1.0 or more."; return }
            val refl = Calculators.reflectedFraction(s) * 100
            val verdict = when {
                s <= 1.5 -> "Excellent. Leave it."
                s <= 2.0 -> "Fine for most radios."
                s <= 3.0 -> "Usable with a tuner; many radios reduce power above 2:1."
                else -> "Too high: check the antenna, connections and length before transmitting at full power."
            }
            result.text = String.format(Locale.US, "• %.1f%% of the power is reflected (%.2f dB)\n• %s", refl, Calculators.mismatchLossDb(s), verdict)
        }
        swr.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = show()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        card.addView(labeled(ctx, "SWR shown on your meter or radio", swr))
        card.addView(result)
        show()
        return card
    }

    private fun labeled(ctx: Context, label: String, field: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (6 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt(), 0)
        addView(TextView(ctx).apply { text = label; textSize = 12f; setTextColor(0xFF9AA4B2.toInt()) })
        addView(field)
    }

    private fun number(ctx: Context, hint: String, value: String) = EditText(ctx).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        textSize = 14f
        gravity = Gravity.START
        setTextColor(0xFFE6EAF0.toInt())
        setHintTextColor(0xFF7D8794.toInt())
        minEms = 6
    }
}
