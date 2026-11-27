package com.js8call.example.ui.prop

import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.js8call.example.R
import com.js8call.example.prop.Band
import com.js8call.example.prop.SpaceWeather
import com.js8call.example.prop.SsnForecast
import com.js8call.example.prop.StationProfile
import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon
import com.js8call.voacap.Voacap
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The VOACAP prediction for one path (issue #57): a day of reliability for every band, for the
 * month of the selected time, with this station's power and JS8 speed. Shown beside the Prop
 * tab's own model, not instead of it: VOACAP is the standard monthly-median reference, the
 * Prop tab's model is tuned on recent traffic.
 */
object VoacapPath {

    private const val TAG = "VoacapPath"
    private const val PREF_NOISE = "voacap_noise"
    private val io = Executors.newSingleThreadExecutor()

    fun noise(fragment: Fragment): Voacap.Noise {
        val name = PreferenceManager.getDefaultSharedPreferences(fragment.requireContext()).getString(PREF_NOISE, null)
        return Voacap.Noise.entries.firstOrNull { it.name == name } ?: Voacap.Noise.RESIDENTIAL
    }

    fun show(
        fragment: Fragment,
        me: LatLon,
        to: LatLon,
        timeMs: Long,
        profile: StationProfile,
        wx: SpaceWeather,
        ssnForecast: Map<String, Double>,
    ) {
        val ctx = fragment.context ?: return
        if (!Voacap.available()) {
            MaterialAlertDialogBuilder(ctx).setMessage(R.string.voacap_unavailable).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMs }
        val year = utc.get(Calendar.YEAR)
        val month = utc.get(Calendar.MONTH) + 1
        val ssn = SsnForecast.choose(ssnForecast, year, month, wx)
        val noise = noise(fragment)
        val bands = Band.entries.toList()
        val request = Voacap.Request(
            txLat = me.lat, txLon = me.lon, rxLat = to.lat, rxLon = to.lon,
            month = month, year = year, ssn = ssn.ssn, watts = profile.powerW,
            bandsMhz = bands.map { it.mhz }, js8ThresholdDb = profile.speed.thresholdDb, noise = noise,
        )
        io.execute {
            val result = runCatching { Voacap.predict(request) }
            result.exceptionOrNull()?.let { Log.w(TAG, "VOACAP prediction failed", it) }
            fragment.activity?.runOnUiThread {
                if (!fragment.isAdded) return@runOnUiThread
                result.fold(
                    onSuccess = { present(fragment, me, to, utc.get(Calendar.HOUR_OF_DAY), year, month, ssn, noise, profile, bands, it, timeMs, wx, ssnForecast) },
                    onFailure = { e ->
                        MaterialAlertDialogBuilder(fragment.requireContext())
                            .setMessage(fragment.getString(R.string.voacap_failed, e.message ?: e.javaClass.simpleName))
                            .setPositiveButton(android.R.string.ok, null).show()
                    },
                )
            }
        }
    }

    private fun present(
        fragment: Fragment, me: LatLon, to: LatLon, hourUtc: Int, year: Int, month: Int,
        ssn: SsnForecast.Value, noise: Voacap.Noise, profile: StationProfile, bands: List<Band>,
        p: Voacap.Prediction, timeMs: Long, wx: SpaceWeather, ssnForecast: Map<String, Double>,
    ) {
        val ctx = fragment.requireContext()
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val km = Geo.distanceKm(me, to).roundToInt()
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val dir = dirs[(((Geo.bearingDeg(me, to) + 22.5) % 360) / 45).toInt()]
        val monthName = java.text.DateFormatSymbols(Locale.US).shortMonths[month - 1]
        val ssnText = ctx.getString(
            when (ssn.source) {
                SsnForecast.Source.NOAA_FORECAST -> R.string.voacap_ssn_noaa
                SsnForecast.Source.FROM_FLUX -> R.string.voacap_ssn_flux
                SsnForecast.Source.FROM_MANUAL_FLUX -> R.string.voacap_ssn_manual
            },
            ssn.ssn.roundToInt(),
        )
        val watts = if (profile.powerW >= 10) profile.powerW.roundToInt().toString() else String.format(Locale.US, "%.1f", profile.powerW)
        val summary = TextView(ctx).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            text = ctx.getString(
                R.string.voacap_summary, String.format(Locale.US, "%,d", km), dir, "$monthName $year", ssnText,
                watts, profile.speed.label, profile.speed.thresholdDb.roundToInt(), ctx.getString(noiseLabel(noise)),
            )
        }
        val detail = TextView(ctx).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, pad / 2, 0, 0)
        }
        fun describe(band: Band?, h: Int) {
            val muf = p.mufMhz.getOrNull(h)?.takeIf { it > 0 }
            val mufText = muf?.let { String.format(Locale.US, "%.1f", it) } ?: "–"
            val b = band ?: bands.maxByOrNull { p.cell(h, it.mhz)?.reliability ?: -1.0 }
            val c = b?.let { p.cell(h, it.mhz) }
            detail.text = if (b == null || c == null) {
                ctx.getString(R.string.voacap_detail_none, h, mufText)
            } else {
                ctx.getString(
                    if (band == null) R.string.voacap_detail_best else R.string.voacap_detail_cell,
                    h, b.label, (c.reliability * 100).roundToInt(), signed(c.js8Snr), mufText,
                )
            }
        }
        val chart = VoacapChartView(ctx).apply {
            this.bands = bands
            prediction = p
            nowHourUtc = hourUtc
            onCellTapped = { band, h -> describe(band, h) }
            setPadding(0, pad / 2, 0, 0)
        }
        describe(null, hourUtc)
        val credit = TextView(ctx).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            text = ctx.getString(R.string.voacap_credit)
            setPadding(0, pad / 2, 0, 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(summary); addView(chart); addView(detail); addView(credit)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.voacap_title)
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.voacap_noise_button) { _, _ ->
                pickNoise(fragment, noise) { show(fragment, me, to, timeMs, profile, wx, ssnForecast) }
            }
            .show()
    }

    private fun pickNoise(fragment: Fragment, current: Voacap.Noise, then: () -> Unit) {
        val ctx = fragment.requireContext()
        val all = Voacap.Noise.entries
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.voacap_noise_title)
            .setSingleChoiceItems(all.map { ctx.getString(noiseLabel(it)) }.toTypedArray(), all.indexOf(current)) { d, which ->
                PreferenceManager.getDefaultSharedPreferences(ctx).edit { putString(PREF_NOISE, all[which].name) }
                d.dismiss()
                then()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun noiseLabel(n: Voacap.Noise) = when (n) {
        Voacap.Noise.INDUSTRIAL -> R.string.voacap_noise_industrial
        Voacap.Noise.RESIDENTIAL -> R.string.voacap_noise_residential
        Voacap.Noise.RURAL -> R.string.voacap_noise_rural
        Voacap.Noise.REMOTE -> R.string.voacap_noise_remote
    }

    private fun signed(db: Double): String {
        val v = db.roundToInt()
        return if (v >= 0) "+$v" else "−${-v}"
    }
}
