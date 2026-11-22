package com.js8call.example.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import java.io.File

/**
 * The optional on-device language models: which ones, where they live, downloading and deleting.
 * Files go to the app's own storage (Android/data/<app>/files/models) and leave no trace when deleted.
 */
object AiModels {

    const val PREF_ENABLED = "ai_enabled"
    const val PREF_MODEL = "ai_model"
    private const val PREF_DOWNLOAD_PREFIX = "ai_download_"

    data class Model(
        val id: String,
        val name: String,
        val note: String,
        val file: String,
        val url: String,
        val approxBytes: Long,
    )

    /** Both Apache-2.0 and free to download without an account. */
    val ALL = listOf(
        Model(
            "qwen2.5-0.5b", "Small (Qwen2.5 0.5B)",
            "Fastest, but often copies text instead of summarising. Good for checking the device can run a model.",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491_000_000L,
        ),
        Model(
            "qwen2.5-1.5b", "Standard (Qwen2.5 1.5B)",
            "Recommended. Real summaries, about three times slower.",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1_120_000_000L,
        ),
    )

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    fun enabled(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_ENABLED, false)

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: Model) = File(dir(ctx), m.file)
    private fun partial(ctx: Context, m: Model) = File(dir(ctx), m.file + ".part")

    /** Downloaded and looks like a GGUF file. */
    fun ready(ctx: Context, m: Model): Boolean {
        val f = file(ctx, m)
        if (!f.isFile || f.length() < 50_000_000L) return false
        return runCatching { f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }.contentEquals("GGUF".toByteArray()) }.getOrDefault(false)
    }

    sealed class State {
        object None : State()
        data class Downloading(val done: Long, val total: Long, val paused: Boolean) : State()
        data class Failed(val reason: String) : State()
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
                            finish(ctx, m)
                            return if (ready(ctx, m)) State.Ready else State.Failed("downloaded file is not a model")
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
        return if (ready(ctx, m)) State.Ready else State.None
    }

    /**
     * Finishes any download that has completed (renames the .part file into place). Only the
     * Models screen used to do this, so a model downloaded in the background stayed invisible
     * to the Digest until that screen was opened. Cheap when nothing is downloading.
     */
    fun settle(ctx: Context) {
        ALL.forEach { m -> if (downloadId(ctx, m) != null) runCatching { state(ctx, m) } }
    }

    fun download(ctx: Context, m: Model) {
        if (downloadId(ctx, m) != null) return
        partial(ctx, m).delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("JS8-Tactical AI model")
            .setDescription(m.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setDestinationInExternalFilesDir(ctx, null, "models/" + m.file + ".part")
        val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putLong(PREF_DOWNLOAD_PREFIX + m.id, id).apply()
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
    }

    fun totalBytesOnDisk(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    private fun finish(ctx: Context, m: Model) {
        val part = partial(ctx, m)
        if (part.isFile) {
            file(ctx, m).delete()
            part.renameTo(file(ctx, m))
        }
        forget(ctx, m)
    }

    private fun downloadId(ctx: Context, m: Model): Long? =
        PreferenceManager.getDefaultSharedPreferences(ctx).getLong(PREF_DOWNLOAD_PREFIX + m.id, -1L).takeIf { it >= 0 }

    private fun forget(ctx: Context, m: Model) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().remove(PREF_DOWNLOAD_PREFIX + m.id).apply()
    }
}
