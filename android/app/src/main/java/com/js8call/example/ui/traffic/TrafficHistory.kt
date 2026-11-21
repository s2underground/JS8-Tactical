package com.js8call.example.ui.traffic

import com.js8call.example.rollup.RollupEngine
import java.io.File

/**
 * Message history on the device: every decoded frame and everything this
 * station sent, one line each, in the app's private storage. On start the
 * frames are replayed through the rollup engine, which rebuilds the messages
 * exactly as they were heard.
 *
 * Kept for [keepMs] (the user's setting); older lines are dropped when the file
 * is loaded and while the app runs. "Clear message history" deletes the file.
 * Nothing leaves the device.
 *
 * Line format (tab-separated, text escaped):
 *   F  receivedAtMs  utc  frequencyHz  type  snr  mode  text  [dialHz]
 *   O  sentMs  frequencyHz  text
 */
class TrafficHistory(private val file: File) {

    sealed class Entry(val timeMs: Long) {
        /** [dialHz]: radio dial when heard, so a reload knows the band (null in files from before it was kept). */
        class Frame(val frame: RollupEngine.Frame, val dialHz: Long? = null) : Entry(frame.receivedAtMs)
        class Sent(timeMs: Long, val frequencyHz: Float, val text: String) : Entry(timeMs)
    }

    @Synchronized
    fun append(entry: Entry) {
        try {
            file.parentFile?.mkdirs()
            file.appendText(encode(entry) + "\n")
        } catch (_: Exception) {
            // History is a convenience; a full disk must not stop the radio.
        }
    }

    /** Rename the file out of the way (crash-loop recovery); returns the new name, or null. */
    @Synchronized
    fun setAside(): String? {
        if (!file.exists()) return null
        val aside = File(file.parentFile, file.name.removeSuffix(".tsv") + "-set-aside-" + System.currentTimeMillis() + ".tsv")
        return if (file.renameTo(aside)) aside.name else null
    }

    /** Entries newer than [sinceMs], oldest first. Rewrites the file without the older ones. */
    @Synchronized
    fun load(sinceMs: Long): List<Entry> {
        if (!file.exists()) return emptyList()
        val lines = try { file.readLines() } catch (_: Exception) { return emptyList() }
        val kept = ArrayList<Entry>(lines.size)
        val keptLines = ArrayList<String>(lines.size)
        for (line in lines) {
            val e = decode(line) ?: continue
            if (e.timeMs >= sinceMs) { kept += e; keptLines += line }
        }
        if (keptLines.size != lines.size) rewrite(keptLines)
        return kept.sortedBy { it.timeMs }
    }

    /** Drop entries older than [sinceMs] from the file. */
    @Synchronized
    fun prune(sinceMs: Long) {
        if (!file.exists()) return
        val lines = try { file.readLines() } catch (_: Exception) { return }
        val kept = lines.filter { l -> decode(l)?.let { it.timeMs >= sinceMs } == true }
        if (kept.size != lines.size) rewrite(kept)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    fun sizeBytes(): Long = if (file.exists()) file.length() else 0L

    private fun rewrite(lines: List<String>) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** "Keep message history for" choices, in hours; 0 = don't keep. */
        const val KEY_KEEP_HOURS = "history_keep_hours"
        const val DEFAULT_KEEP_HOURS = 168

        fun encode(e: Entry): String = when (e) {
            is Entry.Frame -> with(e.frame) {
                listOfNotNull("F", receivedAtMs, utc, frequencyHz, type, snr, mode, escape(text), e.dialHz).joinToString("\t")
            }
            is Entry.Sent -> listOf("O", e.timeMs, e.frequencyHz, escape(e.text)).joinToString("\t")
        }

        fun decode(line: String): Entry? = try {
            val p = line.split('\t')
            when (p.firstOrNull()) {
                "F" -> if (p.size >= 8) Entry.Frame(
                    RollupEngine.Frame(
                        utc = p[2].toInt(), frequencyHz = p[3].toFloat(), type = p[4].toInt(),
                        text = unescape(p[7]), snr = p[5].toInt(), mode = p[6].toInt(), receivedAtMs = p[1].toLong(),
                    ),
                    dialHz = p.getOrNull(8)?.toLongOrNull()?.takeIf { it > 0 },
                ) else null
                "O" -> if (p.size >= 4) Entry.Sent(p[1].toLong(), p[2].toFloat(), unescape(p[3])) else null
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unescape(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> out.append('\t'); 'n' -> out.append('\n'); else -> out.append(s[i + 1])
                    }
                    i += 2
                } else { out.append(c); i++ }
            }
            return out.toString()
        }
    }
}
