package com.js8call.example

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class MessageLogWriter(private val context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val executor = Executors.newSingleThreadExecutor()
    private val timeFormatter = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    @Volatile
    private var enabled = false
    private var logTarget: LogTarget? = null

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
    }

    fun shutdown() {
        enabled = false
        executor.shutdown()
    }

    fun logRx(text: String, snr: Int, freqHz: Float, mode: Int, from: String?) {
        if (!enabled) return
        val timestamp = formatTimestamp()
        val fromPart = if (!from.isNullOrBlank()) " FROM=$from" else ""
        val line = "$timestamp RX$fromPart SNR=$snr FREQ=${formatFrequency(freqHz.toDouble())} MODE=$mode TEXT=\"${sanitizeText(text)}\""
        enqueue(line)
    }

    fun logTx(text: String, to: String?, freqHz: Double, mode: Int) {
        if (!enabled) return
        val timestamp = formatTimestamp()
        val toPart = if (!to.isNullOrBlank()) " TO=$to" else ""
        val line = "$timestamp TX$toPart FREQ=${formatFrequency(freqHz)} MODE=$mode TEXT=\"${sanitizeText(text)}\""
        enqueue(line)
    }

    private fun enqueue(line: String) {
        if (!enabled) return
        executor.execute {
            try {
                appendLine(line)
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing log line", e)
            }
        }
    }

    private fun appendLine(line: String) {
        if (!enabled) return
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        var target = ensureLogTarget() ?: return
        target = rotateIfNeeded(target, bytes.size) ?: return
        when (target) {
            is LogTarget.MediaStoreTarget -> writeToMediaStore(target.uri, bytes)
            is LogTarget.FileTarget -> writeToFile(target.file, bytes)
        }
    }

    private fun ensureLogTarget(): LogTarget? {
        val current = logTarget
        if (current != null) return current
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ensureMediaStoreTarget()
        } else {
            ensureLegacyFileTarget()
        }
        if (created != null) {
            logTarget = created
        }
        return created
    }

    /**
     * Downloads/JS8CallLogs/js8call-log.txt. A file of that name left by an earlier install
     * belongs to that install and can't be opened or reused (Android then refuses to make
     * another), so fall back to a dated name, remembered for next time, and finally to the
     * app's own folder (Android/data/<app>/files/JS8CallLogs) so logging never stops.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ensureMediaStoreTarget(): LogTarget? {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val name = prefs.getString(PREF_LOG_NAME, null) ?: LOG_NAME
        var uri = findMediaStoreEntry(name) ?: createMediaStoreEntry(name)
        if (uri == null) {
            val dated = "js8call-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            uri = createMediaStoreEntry(dated)
            if (uri != null) {
                prefs.edit().putString(PREF_LOG_NAME, dated).apply()
                Log.i(TAG, "Log file name in use by an earlier install; logging to Downloads/$LOG_DIR/$dated")
            }
        }
        if (uri != null) return LogTarget.MediaStoreTarget(uri)
        val dir = context.getExternalFilesDir(null)?.let { File(it, LOG_DIR) } ?: File(context.filesDir, LOG_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        Log.w(TAG, "Downloads not writable; logging to ${dir.absolutePath}")
        return LogTarget.FileTarget(File(dir, LOG_NAME))
    }

    private fun ensureLegacyFileTarget(): LogTarget? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "Storage permission missing; cannot write log")
            return null
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LOG_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create log directory: ${dir.absolutePath}")
            return null
        }
        val file = File(dir, LOG_NAME)
        return LogTarget.FileTarget(file)
    }

    private fun rotateIfNeeded(target: LogTarget, newBytes: Int): LogTarget? {
        val size = getSize(target)
        if (size + newBytes <= LOG_MAX_BYTES) return target
        when (target) {
            is LogTarget.MediaStoreTarget -> rotateMediaStore(target.uri)
            is LogTarget.FileTarget -> rotateFile(target.file)
        }
        logTarget = null
        return ensureLogTarget()
    }

    private fun getSize(target: LogTarget): Long {
        return when (target) {
            is LogTarget.MediaStoreTarget -> queryMediaStoreSize(target.uri)
            is LogTarget.FileTarget -> target.file.length()
        }
    }

    private fun writeToMediaStore(uri: Uri, bytes: ByteArray) {
        val written = (resolver.openOutputStream(uri, "wa") ?: resolver.openOutputStream(uri))
            ?.use { it.write(bytes) }
        if (written == null) Log.w(TAG, "Failed opening log stream")
    }

    private fun writeToFile(file: File, bytes: ByteArray) {
        FileOutputStream(file, true).use { it.write(bytes) }
    }

    private fun rotateMediaStore(uri: Uri) {
        // MediaStore targets are only created on Android 10+ (see ensureLogTarget).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            findMediaStoreEntry(LOG_BACKUP_NAME)?.let { resolver.delete(it, null, null) }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, LOG_BACKUP_NAME)
            }
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed rotating MediaStore log", e)
        }
    }

    private fun rotateFile(file: File) {
        val backup = File(file.parentFile, LOG_BACKUP_NAME)
        if (backup.exists() && !backup.delete()) {
            Log.w(TAG, "Failed deleting backup log: ${backup.absolutePath}")
        }
        if (file.exists() && !file.renameTo(backup)) {
            Log.w(TAG, "Failed rotating log file: ${file.absolutePath}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findMediaStoreEntry(displayName: String): Uri? {
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(displayName, RELATIVE_PATH)
        val id = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            args,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null } ?: return null
        return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMediaStoreEntry(displayName: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating log entry", e)
            null
        }
    }

    private fun queryMediaStoreSize(uri: Uri): Long {
        return resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null } ?: 0L
    }

    private fun formatTimestamp(): String = timeFormatter.format(Date())

    private fun formatFrequency(freqHz: Double): String {
        return String.format(Locale.US, "%.1f", freqHz)
    }

    private fun sanitizeText(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        return normalized.replace("\"", "\\\"")
    }

    private sealed class LogTarget {
        data class MediaStoreTarget(val uri: Uri) : LogTarget()
        data class FileTarget(val file: File) : LogTarget()
    }

    private companion object {
        private const val TAG = "MessageLogWriter"
        private const val LOG_DIR = "JS8CallLogs"
        private const val LOG_NAME = "js8call-log.txt"
        private const val PREF_LOG_NAME = "message_log_file_name"
        private const val LOG_BACKUP_NAME = "js8call-log.1.txt"
        private const val LOG_MAX_BYTES = 10L * 1024L * 1024L
        private const val TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss Z"
        private val RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/JS8CallLogs/"
    }
}
