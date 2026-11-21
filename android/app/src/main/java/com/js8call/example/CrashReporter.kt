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
    /** Every report ever shown, newest last (capped), for Settings → Diagnostics. */
    private const val ARCHIVE = "crash-archive.txt"
    private const val ARCHIVE_MAX_BYTES = 256 * 1024
    private const val PREFS = "crash_reporter"
    private const val KEY_LAST_EXIT = "last_exit_seen"
    private const val KEY_LAST_NATIVE_TRACE = "last_native_trace_v2"
    private const val MAX_BYTES = 64 * 1024

    private var appContext: Context? = null

    fun install(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { noteCrashTime(context.applicationContext) }
            runCatching { append("CRASH on thread '${thread.name}'", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private const val KEY_CRASH_TIMES = "crash_times"

    /** Crash times are kept (last few) so the next start can tell it is in a crash loop. */
    private fun noteCrashTime(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val times = prefs.getString(KEY_CRASH_TIMES, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val kept = (times + System.currentTimeMillis()).takeLast(5).joinToString(",")
        prefs.edit().putString(KEY_CRASH_TIMES, kept).commit() // commit: the process is about to die
    }

    /**
     * True once per crash loop: two or more crashes in the last [windowMs]. Consumed when
     * read, so the next normal start goes back to normal.
     */
    fun takeCrashLoop(ctx: Context, windowMs: Long = 10 * 60_000L): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = prefs.getString(KEY_CRASH_TIMES, "").orEmpty().split(',')
            .mapNotNull { it.toLongOrNull() }.filter { now - it in 0..windowMs }
        if (recent.size < 2) return false
        prefs.edit().putString(KEY_CRASH_TIMES, "").apply()
        return true
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
        archive(activity, text)
        // Shown once: it is in the archive (Settings → Diagnostics) from here on, so tapping
        // outside the dialog can't make the same old report come back at every launch.
        f.delete()
        showReport(activity, text)
    }

    /**
     * The report dialog: a big Copy button first (field use: one tap, paste anywhere),
     * then the report with the newest problem at the top.
     */
    private fun showReport(activity: AppCompatActivity, text: String) {
        val entries = text.split("\n===== ").map { it.trim() }.filter { it.isNotEmpty() }
        val head = entries.firstOrNull { it.startsWith("JS8-Tactical") }
        val problems = entries.filterNot { it.startsWith("JS8-Tactical") }
        val newestFirst = buildString {
            head?.let { append(it).append("\n\n") }
            append("${problems.size} problem${if (problems.size == 1) "" else "s"} recorded, newest first.\n")
            problems.asReversed().forEach { append("\n===== ").append(it).append('\n') }
        }
        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        fun copy() {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("JS8-Tactical error report", newestFirst))
            android.widget.Toast.makeText(activity, "Error report copied. Paste it anywhere.", android.widget.Toast.LENGTH_LONG).show()
        }
        val copyButton = com.google.android.material.button.MaterialButton(activity).apply {
            setText("COPY ERROR REPORT")
            textSize = 20f
            minHeight = (64 * density).toInt()
            setOnClickListener { copy() }
        }
        val body = TextView(activity).apply {
            setText(newestFirst)
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, pad, 0, 0)
        }
        val column = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(copyButton, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(ScrollView(activity).apply { addView(body) }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.45).toInt(),
            ))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_body)
            .setView(column)
            .setPositiveButton(R.string.crash_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(activity.getString(R.string.bug_report_email)))
                    putExtra(Intent.EXTRA_SUBJECT, "JS8-Tactical crash report, ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
                    putExtra(Intent.EXTRA_TEXT, newestFirst)
                }
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.crash_share)))
            }
            .setNegativeButton(R.string.crash_dismiss, null)
            .show()
    }

    /** Settings → Diagnostics: the most recent report again, with the same Copy button. */
    fun showLast(activity: AppCompatActivity): Boolean {
        val past = File(activity.filesDir, ARCHIVE).takeIf { it.exists() }?.readText().orEmpty()
        val last = past.substringAfterLast("##### Report shown ", "").substringAfter('\n', "").trim()
        if (last.isEmpty()) return false
        showReport(activity, last)
        return true
    }

    private fun importanceName(i: Int): String = when {
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "receiver service running"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "background service"
        i < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "background"
        else -> "idle in background, no receiver service"
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
            // Android trimming an idle background app (receiver off) is normal housekeeping, not a problem.
            val receiverWanted = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(com.js8call.example.service.JS8EngineService.PREF_RECEIVER_WANTED, false)
            val background = e.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
            if ((e.reason == ApplicationExitInfo.REASON_LOW_MEMORY || e.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE) &&
                background && !receiverWanted) continue
            val sb = StringBuilder()
            sb.append("Android exit record: ").append(kind).append('\n')
            sb.append("When: ").append(stamp(e.timestamp)).append('\n')
            e.description?.let { sb.append("Detail: ").append(it).append('\n') }
            sb.append("Importance: ").append(e.importance).append(" (").append(importanceName(e.importance))
                .append("), PSS: ").append(e.pss / 1024).append(" MB\n")
            sb.append("Receiver switched on at the time: ").append(if (receiverWanted) "yes" else "no").append('\n')
            // ANR traces are plain text; native tombstones are binary (protobuf), so their readable strings are kept.
            if (e.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { e.traceInputStream?.bufferedReader()?.use { r -> sb.append(r.readText().take(12_000)) } }
            }
            if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) sb.append(nativeStrings(e))
            appendRaw(sb.toString())
        }
        // Native crashes seen before this reporter could read them: extract their traces once.
        val lastTrace = prefs.getLong(KEY_LAST_NATIVE_TRACE, 0L)
        var newestTrace = lastTrace
        for (e in exits.sortedBy { it.timestamp }) {
            if (e.reason != ApplicationExitInfo.REASON_CRASH_NATIVE || e.timestamp <= lastTrace) continue
            newestTrace = maxOf(newestTrace, e.timestamp)
            if (e.timestamp > lastSeen) continue // handled above
            appendRaw("Native crash trace (earlier crash)\nWhen: ${stamp(e.timestamp)}\n" + nativeStrings(e))
        }
        prefs.edit().putLong(KEY_LAST_EXIT, newest).putLong(KEY_LAST_NATIVE_TRACE, newestTrace).apply()
    }

    /**
     * Readable text from a native tombstone (binary protobuf): abort message,
     * library paths and symbol names from the backtrace, in order. Enough to
     * see which library and function crashed.
     */
    private fun nativeStrings(e: ApplicationExitInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val bytes = runCatching { e.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: return "(no native trace available)\n"
        return Tombstone.summarize(bytes)
    }

    private fun append(title: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        appendRaw("$title\nWhen: ${stamp(System.currentTimeMillis())} · build ${BuildConfig.GIT_SHA}\n$sw")
    }

    private fun appendRaw(body: String) {
        val ctx = appContext ?: return
        val f = file(ctx)
        val header = if (f.exists() && f.length() > 0) "" else
            "JS8-Tactical ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA}) · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n"
        f.appendText("$header\n===== $body\n")
        if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES))
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun archive(ctx: Context, text: String) {
        runCatching {
            val a = File(ctx.filesDir, ARCHIVE)
            a.appendText("\n##### Report shown ${stamp(System.currentTimeMillis())}\n$text\n")
            if (a.length() > ARCHIVE_MAX_BYTES) a.writeText(a.readText().takeLast(ARCHIVE_MAX_BYTES))
        }
    }

    /**
     * Everything useful for a bug report, built on demand (Settings → Diagnostics):
     * app and device, radio setup (no callsign or grid), past crash/ANR reports,
     * anything pending, and the app's own recent log lines (Android only lets an
     * app read its own). Shared only when the user chooses to.
     */
    fun diagnosticReport(ctx: Context): String {
        runCatching { collectExitReasons(ctx) }
        val p = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val sb = StringBuilder()
        sb.append("JS8-Tactical ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA}) · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Report made ${stamp(System.currentTimeMillis())}\n\n")
        sb.append("== Setup (no callsign or grid)\n")
        // Every setting except ones that identify the operator or their team.
        val private = Regex("(?i)call|grid|info|status_message|group|roster|host|sent_uids|location|lat|lon")
        p.all.toSortedMap().forEach { (k, v) ->
            if (private.containsMatchIn(k)) return@forEach
            val text = v.toString()
            if (text.length <= 200) sb.append("$k = $text\n")
        }
        sb.append('\n')
        val pending = file(ctx).takeIf { it.exists() }?.readText().orEmpty()
        if (pending.isNotBlank()) sb.append("== Pending report\n").append(pending).append('\n')
        val past = File(ctx.filesDir, ARCHIVE).takeIf { it.exists() }?.readText().orEmpty()
        sb.append("== Past crash/ANR reports (each shown once, oldest first)\n")
            .append(if (past.isBlank()) "(none)\n" else distinctReports(past).takeLast(48_000)).append('\n')
        val drops = com.js8call.example.service.UsbDropouts.text(ctx)
        sb.append("== Radio USB dropouts\n").append(if (drops.isBlank()) "(none recorded)\n" else drops.takeLast(12_000)).append('\n')
        val echoes = com.js8call.example.ui.traffic.OwnEchoLog.text(ctx)
        sb.append("== Own transmissions heard back (sent vs heard offset)\n").append(if (echoes.isBlank()) "(none decoded)\n" else echoes.takeLast(8_000)).append('\n')
        val ai = com.js8call.example.ai.AiSpeedLog.text(ctx)
        if (ai.isNotBlank()) sb.append("== AI speed tests\n").append(ai.takeLast(12_000)).append('\n')
        val setAside = ctx.filesDir.listFiles { f -> f.name.contains("-set-aside-") }.orEmpty()
        if (setAside.isNotEmpty()) {
            sb.append("== History files set aside after crash loops (names only)\n")
            setAside.sortedBy { it.name }.forEach { sb.append("${it.name} · ${it.length()} bytes\n") }
            sb.append('\n')
        }
        sb.append("== Recent app log\n")
        val log = runCatching {
            // The engine logs several debug lines a second; drop those so the report reaches back
            // hours instead of two minutes.
            val noise = Regex("Audio: \\d+ samples|Decode ready|Decode started|Decode finished|legacy_decode|Submode [A-Z]:|" +
                "processNextTxIfIdle|ACTION_PROCESS_TX_QUEUE|BLASTBufferQueue|VRI\\[|InsetsController|Ring buffer aligned|spectrum|" +
                // Twice-a-second engine status lines: idle TX audio, audio submits, empty decode passes.
                "TX audio: .*active=0|Audio submit|DecodeStarted|DecodeFinished: count=0|Spectrum broadcasts|" +
                "AutofillManager|SmartClip|ImeFocusController|ImeTracker|InputTransport|" +
                // Hamlib's step-by-step trace (several lines per CAT command); its warnings and errors stay.
                " [VD]/HamlibDebug")
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "60000"))
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.filterNot { noise.containsMatchIn(it) }.toList().takeLast(4000).joinToString("\n")
            }
        }.getOrElse { "(log unavailable: ${it.message})" }
        sb.append(log.takeLast(220_000))
        return sb.toString()
    }

    /** The archive repeats every report each time it was shown; keep each one once, in order. */
    internal fun distinctReports(past: String): String =
        past.split(Regex("(?m)^(?=##### |===== )"))
            .map { it.trimEnd() }
            .filter { it.startsWith("===== ") }
            .distinct()
            .joinToString("\n\n", postfix = "\n")

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
