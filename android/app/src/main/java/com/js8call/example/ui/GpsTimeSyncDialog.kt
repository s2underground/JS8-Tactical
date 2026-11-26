package com.js8call.example.ui

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.js8call.example.R
import com.js8call.example.service.JS8EngineService
import com.js8call.example.util.GpsTime
import java.util.Locale
import kotlin.math.abs

/**
 * Sync clock from GPS: asks the GPS for a few fresh fixes, shows how far this device's clock is
 * from GPS time (with the fix quality), and on the operator's word sets the app's time
 * correction ("time drift") to match. The device clock itself is not changed; nothing is sent
 * on the air; the position is never stored, logged or shown.
 *
 * Idea from doodlecomms' GPS UTC Clock (issue #52); the rules live in [GpsTime].
 */
class GpsTimeSyncDialog : DialogFragment() {

    private enum class Stage { PERMISSION, DENIED, GPS_OFF, WAITING, TIMED_OUT, RESULT }

    private val handler = Handler(Looper.getMainLooper())
    private var stage = Stage.WAITING
    private var locationManager: LocationManager? = null
    private var listening = false
    private var startElapsedNanos = 0L
    private val samples = mutableListOf<GpsTime.Sample>()
    private var satellitesUsed = -1
    private var result: GpsTime.Result? = null

    private lateinit var body: TextView
    private lateinit var progress: LinearProgressIndicator

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else show(Stage.DENIED)
    }

    private val listener = LocationListener { loc -> onFix(loc) }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (stage != Stage.WAITING) return
            val waitedSec = (SystemClock.elapsedRealtimeNanos() - startElapsedNanos) / 1_000_000_000L
            if (waitedSec >= TIMEOUT_SEC) {
                stopListening()
                show(Stage.TIMED_OUT)
                return
            }
            renderWaiting(waitedSec)
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val pad = (24 * ctx.resources.displayMetrics.density).toInt()
        body = TextView(ctx).apply {
            setTextIsSelectable(false)
            textSize = 15f
            setLineSpacing(0f, 1.15f)
        }
        progress = LinearProgressIndicator(ctx).apply { isIndeterminate = true }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 3, pad, 0)
            addView(body)
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = pad / 2
            })
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.gps_time_title)
            .setView(content)
            // Labels and actions are set per stage in render(); null listeners keep the dialog open.
            .setPositiveButton(R.string.gps_time_apply, null)
            .setNeutralButton(R.string.gps_time_again, null)
            .setNegativeButton(R.string.gps_time_close, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onPositive() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { begin() }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dismissAllowingStateLoss() }
            begin()
        }
        return dialog
    }

    /** Set when the screen went away mid-measurement: measure afresh on return. */
    private var restartOnResume = false

    override fun onStop() {
        super.onStop()
        if (stage == Stage.WAITING) restartOnResume = true
        stopListening()
    }

    override fun onResume() {
        super.onResume()
        // Back from the app's or location settings, or from another app mid-measurement.
        if (restartOnResume || stage == Stage.DENIED || stage == Stage.GPS_OFF) {
            restartOnResume = false
            if ((dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE) != null) begin()
        }
    }

    private fun begin() {
        val ctx = context ?: return
        stopListening()
        samples.clear()
        result = null
        satellitesUsed = -1
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            show(Stage.PERMISSION)
            return
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            show(Stage.GPS_OFF)
            return
        }
        startElapsedNanos = SystemClock.elapsedRealtimeNanos()
        try {
            // The GPS provider itself: not fused, not network, not a last-known fix.
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            runCatching { lm.registerGnssStatusCallback(gnssCallback, handler) }
            listening = true
        } catch (e: SecurityException) {
            show(Stage.PERMISSION)
            return
        } catch (e: IllegalArgumentException) {
            show(Stage.GPS_OFF)
            return
        }
        show(Stage.WAITING)
        handler.post(tick)
    }

    private fun stopListening() {
        handler.removeCallbacks(tick)
        if (!listening) return
        listening = false
        locationManager?.let { lm ->
            runCatching { lm.removeUpdates(listener) }
            runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
        }
    }

    private fun onFix(loc: Location) {
        if (stage != Stage.WAITING) return
        // Only fixes taken since we asked: a cached fix would measure an old moment.
        if (loc.elapsedRealtimeNanos < startElapsedNanos) return
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val nowWall = System.currentTimeMillis()
        @Suppress("DEPRECATION")
        val mock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider
        samples += GpsTime.Sample(
            offsetMs = GpsTime.offsetMs(loc.time, loc.elapsedRealtimeNanos, nowElapsed, nowWall),
            fixAgeMs = GpsTime.ageMs(loc.elapsedRealtimeNanos, nowElapsed),
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            mock = mock,
        )
        if (samples.size < GpsTime.SAMPLES) {
            renderWaiting((nowElapsed - startElapsedNanos) / 1_000_000_000L)
            return
        }
        stopListening()
        result = GpsTime.combine(samples)
        show(Stage.RESULT)
    }

    private fun onPositive() {
        val ctx = context ?: return
        when (stage) {
            Stage.PERMISSION -> askPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            Stage.DENIED -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            Stage.GPS_OFF -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            Stage.TIMED_OUT -> begin()
            Stage.RESULT -> {
                val r = result ?: return
                ctx.startService(Intent(ctx, JS8EngineService::class.java).apply {
                    action = JS8EngineService.ACTION_SET_TIME_DRIFT
                    putExtra(JS8EngineService.EXTRA_TIME_DRIFT_MS, r.offsetMs)
                })
                // Fix quality only: the position is never logged.
                Log.i(TAG, "Clock correction set from GPS: ${r.offsetMs} ms (spread ${r.spreadMs} ms, " +
                    "best accuracy ${r.bestAccuracyM} m, satellites $satellitesUsed, mock ${r.mock})")
                Toast.makeText(ctx, getString(R.string.gps_time_applied, signedSeconds(r.offsetMs)), Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            }
            Stage.WAITING -> Unit
        }
    }

    private fun show(next: Stage) {
        stage = next
        if (!isAdded) return
        val d = dialog as? AlertDialog ?: return
        val positive = d.getButton(AlertDialog.BUTTON_POSITIVE)
        val neutral = d.getButton(AlertDialog.BUTTON_NEUTRAL)
        val negative = d.getButton(AlertDialog.BUTTON_NEGATIVE)
        progress.visibility = if (next == Stage.WAITING) View.VISIBLE else View.GONE
        neutral?.visibility = if (next == Stage.RESULT) View.VISIBLE else View.GONE
        positive?.visibility = if (next == Stage.WAITING) View.GONE else View.VISIBLE
        negative?.setText(if (next == Stage.RESULT) R.string.gps_time_cancel else R.string.gps_time_close)
        when (next) {
            Stage.PERMISSION -> { body.setText(R.string.gps_time_need_permission); positive?.setText(R.string.gps_time_allow) }
            Stage.DENIED -> { body.setText(R.string.gps_time_denied); positive?.setText(R.string.gps_time_app_settings) }
            Stage.GPS_OFF -> { body.setText(R.string.gps_time_gps_off); positive?.setText(R.string.gps_time_location_settings) }
            Stage.TIMED_OUT -> { body.text = getString(R.string.gps_time_timed_out, "${TIMEOUT_SEC}s"); positive?.setText(R.string.gps_time_try_again) }
            Stage.WAITING -> renderWaiting(0)
            Stage.RESULT -> renderResult(positive)
        }
    }

    private fun renderWaiting(waitedSec: Long) {
        if (!isAdded) return
        val sats = if (satellitesUsed >= 0) getString(R.string.gps_time_satellites, satellitesUsed) else ""
        body.text = getString(R.string.gps_time_waiting, "${waitedSec}s", samples.size, GpsTime.SAMPLES, sats)
    }

    private fun renderResult(positive: android.widget.Button?) {
        val r = result ?: return
        val current = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getLong(JS8EngineService.PREF_TIME_DRIFT_MS, 0L)
        val concerns = GpsTime.concerns(r, current)
        val lines = mutableListOf<String>()
        lines += when {
            abs(r.offsetMs) < 50 -> getString(R.string.gps_time_clock_right)
            r.offsetMs > 0 -> getString(R.string.gps_time_clock_slow, seconds(abs(r.offsetMs)))
            else -> getString(R.string.gps_time_clock_fast, seconds(abs(r.offsetMs)))
        }
        lines += getString(R.string.gps_time_correction, signedSeconds(current), signedSeconds(r.offsetMs))
        val accuracy = r.bestAccuracyM?.let { String.format(Locale.US, "%.0f m", it) } ?: "?"
        lines += getString(R.string.gps_time_quality, accuracy, r.samples, "${r.spreadMs} ms") +
            if (satellitesUsed >= 0) " · " + getString(R.string.gps_time_satellites, satellitesUsed) else ""
        concerns.forEach { c ->
            lines += "⚠ " + getString(
                when (c) {
                    GpsTime.Concern.MOCK -> R.string.gps_time_concern_mock
                    GpsTime.Concern.POOR_ACCURACY -> R.string.gps_time_concern_accuracy
                    GpsTime.Concern.DISAGREE -> R.string.gps_time_concern_disagree
                    GpsTime.Concern.STALE -> R.string.gps_time_concern_stale
                    GpsTime.Concern.LARGE_CHANGE -> R.string.gps_time_concern_large
                },
            )
        }
        lines += getString(R.string.gps_time_note)
        body.text = lines.joinToString("\n\n")
        positive?.setText(if (GpsTime.needsCare(concerns)) R.string.gps_time_apply_anyway else R.string.gps_time_apply)
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.2f s", ms / 1000.0)
    private fun signedSeconds(ms: Long) = String.format(Locale.US, "%+.2f s", ms / 1000.0)

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    companion object {
        const val TAG = "GpsTimeSync"
        /** A cold start outdoors can take a minute or two; past this, say so and offer a retry. */
        private const val TIMEOUT_SEC = 180L

        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) != null || fm.isStateSaved) return
            GpsTimeSyncDialog().show(fm, TAG)
        }
    }
}
