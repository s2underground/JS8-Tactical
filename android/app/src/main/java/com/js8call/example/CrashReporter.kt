package com.js8call.example

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps a short, human-readable record of crashes and caught errors so a
 * tester can send it in. Nothing leaves the device unless the user copies or
 * shares it. Covers:
 *  - Kotlin/Java crashes (uncaught exception handler);
 *  - crashes inside native libraries such as the map engine, read back from
 *    Android's own exit records on the next launch (Android 11+);
 *  - errors the app caught and survived ([recordNonFatal]).
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val FILE = "crash-report.txt"
    private const val PREFS = "crash_reporter"
    private const val KEY_LAST_EXIT = "last_exit_seen"
    private const val MAX_BYTES = 64 * 1024

    private var appContext: Context? = null

    fun install(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append("CRASH on thread '${thread.name}'", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** An error the app recovered from; kept so it can be reported. */
    fun recordNonFatal(where: String, error: Throwable) {
        Log.w(TAG, "Non-fatal in $where", error)
        runCatching { append("Caught error in $where (app kept running)", error) }
    }

    /** On launch: add any crash Android recorded since last time, then offer the report if there is one. */
    fun showIfPending(activity: AppCompatActivity) {
        runCatching { collectExitReasons(activity) }
        val f = file(activity)
        if (!f.exists() || f.length() == 0L) return
        val text = f.readText()
        val view = TextView(activity).apply {
            setText(text)
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_body)
            .setView(ScrollView(activity).apply { addView(view) })
            .setPositiveButton(R.string.crash_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "JS8-Tactical error report")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.crash_share)))
                f.delete()
            }
            .setNeutralButton(R.string.crash_copy) { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("JS8-Tactical error report", text))
                f.delete()
            }
            .setNegativeButton(R.string.crash_dismiss) { _, _ -> f.delete() }
            .show()
    }

    private fun collectExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT, 0L)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        var newest = lastSeen
        for (e in exits.sortedBy { it.timestamp }) {
            if (e.timestamp <= lastSeen) continue
            newest = maxOf(newest, e.timestamp)
            val kind = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH -> "Kotlin/Java crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (e.g. map engine)"
                ApplicationExitInfo.REASON_ANR -> "App not responding (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android: low memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed by Android: excessive resource use"
                else -> null
            } ?: continue
            val sb = StringBuilder()
            sb.append("Android exit record: ").append(kind).append('\n')
            sb.append("When: ").append(stamp(e.timestamp)).append('\n')
            e.description?.let { sb.append("Detail: ").append(it).append('\n') }
            sb.append("Importance: ").append(e.importance).append(", PSS: ").append(e.pss / 1024).append(" MB\n")
            // ANR traces are plain text; native tombstones are binary, so only the first readable part is kept.
            if (e.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { e.traceInputStream?.bufferedReader()?.use { r -> sb.append(r.readText().take(12_000)) } }
            }
            appendRaw(sb.toString())
        }
        if (newest != lastSeen) prefs.edit().putLong(KEY_LAST_EXIT, newest).apply()
    }

    private fun append(title: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        appendRaw("$title\nWhen: ${stamp(System.currentTimeMillis())}\n$sw")
    }

    private fun appendRaw(body: String) {
        val ctx = appContext ?: return
        val f = file(ctx)
        val header = if (f.exists() && f.length() > 0) "" else
            "JS8-Tactical ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n"
        f.appendText("$header\n===== $body\n")
        if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES))
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
