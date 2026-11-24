package com.js8call.example.ai

import android.app.DownloadManager
import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The optional on-device language models: which ones, where they live, downloading and deleting.
 * Files go to the app's own storage (Android/data/<app>/files/models) and leave no trace when deleted.
 *
 * Integrity: each download comes from one fixed revision of its Hugging Face repository (a later
 * upload can't change what the app fetches) and must match the SHA-256 that Hugging Face
 * publishes for that file. The check runs in the background once the download finishes, and once
 * for a model downloaded by an older version of the app. A file that doesn't match is deleted
 * and never loaded. After that, a model counts as checked while its size and timestamp are
 * unchanged (the app's own storage, which other apps can't write to).
 */
object AiModels {

    const val PREF_ENABLED = "ai_enabled"
    const val PREF_MODEL = "ai_model"
    private const val PREF_DOWNLOAD_PREFIX = "ai_download_"
    private const val PREF_VERIFIED_PREFIX = "ai_verified_"
    private const val PREF_REJECTED_PREFIX = "ai_rejected_"

    data class Model(
        val id: String,
        val name: String,
        val note: String,
        val file: String,
        val url: String,
        val approxBytes: Long,
        /** SHA-256 of the file, as published on its Hugging Face page (lower case hex). */
        val sha256: String,
    )

    /** Both Apache-2.0 and free to download without an account. */
    val ALL = listOf(
        Model(
            "qwen2.5-0.5b", "Small (Qwen2.5 0.5B)",
            "Fastest, but often copies text instead of summarising. Good for checking the device can run a model.",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/df5bf01389a39c743ab467d734bf501681e041c5/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491_000_000L,
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        ),
        Model(
            "qwen2.5-1.5b", "Standard (Qwen2.5 1.5B)",
            "Recommended. Real summaries, about three times slower.",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1_120_000_000L,
            "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        ),
    )

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    fun enabled(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, false)

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: Model) = File(dir(ctx), m.file)
    private fun partial(ctx: Context, m: Model) = File(dir(ctx), m.file + ".part")

    /** Downloaded, looks like a GGUF file, and passed the SHA-256 check. Cheap: safe on the main thread. */
    fun ready(ctx: Context, m: Model): Boolean {
        val f = file(ctx, m)
        return looksLikeGguf(f) && prefs(ctx).getString(PREF_VERIFIED_PREFIX + m.id, null) == stamp(f, m)
    }

    private fun looksLikeGguf(f: File): Boolean {
        if (!f.isFile || f.length() < 50_000_000L) return false
        return runCatching { f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }.contentEquals("GGUF".toByteArray()) }.getOrDefault(false)
    }

    /** What a passed check is recorded as: the expected hash plus the file's size and time. */
    private fun stamp(f: File, m: Model) = "${m.sha256}:${f.length()}:${f.lastModified()}"

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    // Hashing a 1 GB file takes a few seconds, so it runs on its own thread, one file at a time.
    private val verifier = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-model-verify").apply { isDaemon = true } }
    private val verifying: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Checks [src] against [m]'s SHA-256 in the background. On a match it becomes the model file
     * (renamed into place if it was the .part download); otherwise it is deleted.
     */
    private fun verify(ctx: Context, m: Model, src: File) {
        if (!verifying.add(m.id)) return
        val app = ctx.applicationContext
        verifier.execute {
            try {
                val ok = runCatching { FileHash.sha256(src) }.getOrNull() == m.sha256
                val target = file(app, m)
                if (ok && (src == target || (target.delete() || !target.exists()) && src.renameTo(target))) {
                    prefs(app).edit { putString(PREF_VERIFIED_PREFIX + m.id, stamp(target, m)); remove(PREF_REJECTED_PREFIX + m.id) }
                } else {
                    src.delete()
                    prefs(app).edit { remove(PREF_VERIFIED_PREFIX + m.id); putBoolean(PREF_REJECTED_PREFIX + m.id, !ok) }
                }
            } finally {
                verifying.remove(m.id)
            }
        }
    }

    sealed class State {
        object None : State()
        data class Downloading(val done: Long, val total: Long, val paused: Boolean) : State()
        data class Failed(val reason: String) : State()
        /** Downloaded; checking the SHA-256 (a few seconds). */
        object Verifying : State()
        object Ready : State()
    }

    fun state(ctx: Context, m: Model): State {
        val id = downloadId(ctx, m)
        if (id != null) {
            val dm = ctx.getSystemService(DownloadManager::class.java)
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: m.approxBytes
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            forget(ctx, m)
                            val part = partial(ctx, m)
                            if (part.isFile) {
                                verify(ctx, m, part)
                                return State.Verifying
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            forget(ctx, m)
                            return State.Failed("download failed ($reason)")
                        }
                        DownloadManager.STATUS_PAUSED -> return State.Downloading(done, total, true)
                        else -> return State.Downloading(done, total, false)
                    }
                }
            }
            forget(ctx, m)
        }
        if (m.id in verifying) return State.Verifying
        if (ready(ctx, m)) return State.Ready
        val f = file(ctx, m)
        if (f.isFile) {
            // Downloaded by a version of the app that didn't check: check it now.
            verify(ctx, m, f)
            return State.Verifying
        }
        if (prefs(ctx).getBoolean(PREF_REJECTED_PREFIX + m.id, false)) {
            return State.Failed("The download didn't match the published checksum, so it was deleted. Download it again; if this keeps happening, please report it.")
        }
        return State.None
    }

    /**
     * Finishes any download that has completed (checks it, then moves it into place), and starts
     * the one-time check of a model downloaded before checking existed. Only the Models screen
     * used to do this, so a model already on the phone stayed unusable for the Digest after an
     * update until that screen was opened (found in alpha.5 diagnostics). Cheap otherwise.
     */
    fun settle(ctx: Context) {
        ALL.forEach { m ->
            val unchecked = file(ctx, m).isFile && !ready(ctx, m)
            if (downloadId(ctx, m) != null || unchecked) runCatching { state(ctx, m) }
        }
    }

    fun download(ctx: Context, m: Model) {
        if (downloadId(ctx, m) != null) return
        partial(ctx, m).delete()
        prefs(ctx).edit { remove(PREF_REJECTED_PREFIX + m.id) }
        val req = DownloadManager.Request(m.url.toUri())
            .setTitle("JS8-Tactical AI model")
            .setDescription(m.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setDestinationInExternalFilesDir(ctx, null, "models/" + m.file + ".part")
        val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        prefs(ctx).edit { putLong(PREF_DOWNLOAD_PREFIX + m.id, id) }
    }

    fun cancel(ctx: Context, m: Model) {
        downloadId(ctx, m)?.let { ctx.getSystemService(DownloadManager::class.java).remove(it) }
        forget(ctx, m)
        partial(ctx, m).delete()
    }

    /** Removes the model completely. */
    fun delete(ctx: Context, m: Model) {
        cancel(ctx, m)
        file(ctx, m).delete()
        prefs(ctx).edit { remove(PREF_VERIFIED_PREFIX + m.id); remove(PREF_REJECTED_PREFIX + m.id) }
    }

    fun totalBytesOnDisk(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    private fun downloadId(ctx: Context, m: Model): Long? =
        PreferenceManager.getDefaultSharedPreferences(ctx).getLong(PREF_DOWNLOAD_PREFIX + m.id, -1L).takeIf { it >= 0 }

    private fun forget(ctx: Context, m: Model) {
        prefs(ctx).edit { remove(PREF_DOWNLOAD_PREFIX + m.id) }
    }
}
