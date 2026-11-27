package com.js8call.example.ai

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * A one-off feasibility check for moving the AI engine into an isolated process (security plan,
 * Level 2: llama.cpp gets no permissions and no access to the app's files, network or USB).
 *
 * An isolated process can't open the app's files by name, so the model has to be handed over as
 * an open file descriptor, and llama.cpp opens models by path. Whether that can work depends on
 * what the device's SELinux policy lets an isolated process do with a passed descriptor, which
 * varies by Android version and maker. This asks the device once per app version, when a model
 * is downloaded, and puts the answers in the diagnostics report:
 *  - read: read bytes through the passed descriptor;
 *  - mmap: map it (llama.cpp maps model files);
 *  - reopen: open "/proc/self/fd/N" by name (would let llama.cpp load it unchanged);
 *  - native: load the AI engine's native library inside the isolated process.
 * Nothing else changes; the AI still runs as before.
 */
object IsolationProbe {

    private const val TAG = "AiIsolationProbe"
    private const val PREF_RESULT = "ai_isolation_probe"
    private const val MSG_PROBE = 1
    private const val KEY_FD = "fd"
    private const val KEY_LEN = "len"
    private const val KEY_RESULT = "result"

    /** The stored answer for the diagnostics report, or null if the probe hasn't run. */
    fun result(ctx: Context): String? = PreferenceManager.getDefaultSharedPreferences(ctx).getString(PREF_RESULT, null)

    /** Runs the probe once per app version, if a model is downloaded. Call from any thread. */
    fun runOnce(ctx: Context) {
        val app = ctx.applicationContext
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCodeCompat() }.getOrDefault(0L)
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        if (prefs.getString(PREF_RESULT, null)?.startsWith("v$version ") == true) return
        val model = AiModels.ALL.map { AiModels.file(app, it) }.firstOrNull { it.isFile && it.length() > 4096 } ?: return
        val pfd = runCatching { ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() ?: return
        val len = model.length()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val reply = Messenger(Handler(Looper.getMainLooper()) { m ->
                    val text = m.data?.getString(KEY_RESULT) ?: "no answer"
                    prefs.edit { putString(PREF_RESULT, "v$version $text") }
                    Log.i(TAG, "isolated process: $text")
                    runCatching { app.unbindService(this) }
                    true
                })
                val msg = Message.obtain(null, MSG_PROBE).apply {
                    data = Bundle().apply { putParcelable(KEY_FD, pfd); putLong(KEY_LEN, len) }
                    replyTo = reply
                }
                runCatching { Messenger(binder).send(msg) }.onFailure {
                    prefs.edit { putString(PREF_RESULT, "v$version send failed: ${it.javaClass.simpleName}") }
                    runCatching { app.unbindService(this) }
                }
                runCatching { pfd.close() } // the service received its own copy
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val bound = runCatching { app.bindService(Intent(app, Isolated::class.java), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) {
            prefs.edit { putString(PREF_RESULT, "v$version could not start an isolated process") }
            runCatching { pfd.close() }
        }
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    /** Runs in its own isolated process (AndroidManifest: isolatedProcess, process ":aiprobe"). */
    class Isolated : Service() {
        private val messenger = Messenger(Handler(Looper.getMainLooper()) { m ->
            if (m.what != MSG_PROBE) return@Handler false
            @Suppress("DEPRECATION")
            val pfd = m.data?.getParcelable<ParcelFileDescriptor>(KEY_FD)
            val len = m.data?.getLong(KEY_LEN) ?: 0L
            val result = if (pfd == null) "no descriptor" else probe(pfd, len)
            runCatching { pfd?.close() }
            runCatching { m.replyTo?.send(Message.obtain().apply { data = Bundle().apply { putString(KEY_RESULT, result) } }) }
            true
        })

        override fun onBind(intent: Intent?): IBinder = messenger.binder

        private fun probe(pfd: ParcelFileDescriptor, len: Long): String {
            fun check(name: String, block: () -> Boolean): String =
                "$name=" + runCatching { if (block()) "yes" else "no" }.getOrElse { "no (${it.javaClass.simpleName})" }
            val gguf = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
            val read = check("read") {
                val b = ByteArray(4)
                FileInputStream(pfd.fileDescriptor).channel.use { ch -> ch.position(0); ch.read(java.nio.ByteBuffer.wrap(b)) == 4 }
                    && b.contentEquals(gguf)
            }
            val mmap = check("mmap") {
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val map = ch.map(FileChannel.MapMode.READ_ONLY, 0, minOf(len, 1L shl 30))
                    map.get(map.capacity() - 1) // touch the far end too
                    map.get(0) == gguf[0]
                }
            }
            val reopen = check("reopen") {
                FileInputStream(File("/proc/self/fd/${pfd.fd}")).use { s -> val b = ByteArray(4); s.read(b) == 4 && b.contentEquals(gguf) }
            }
            val native = check("native") { System.loadLibrary("js8llm"); true }
            val files = check("appFiles") { File(applicationInfo.dataDir).list() != null }
            val isolated = if (android.os.Build.VERSION.SDK_INT >= 28) android.os.Process.isIsolated().toString() else "?"
            return "$read $mmap $reopen $native $files (isolated=$isolated, sdk ${android.os.Build.VERSION.SDK_INT})"
        }
    }
}
