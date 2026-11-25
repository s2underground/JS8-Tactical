package com.js8call.example.service

/**
 * How far a send has got on air, frame by frame: what the Traffic tab's
 * "Sending" bar shows, so the operator sees exactly what has gone out and what
 * is still to go, rather than waiting for the whole message to finish.
 *
 * [frames] holds the text each frame carries, in order (from the same framing
 * the engine transmits). [started] counts frames whose audio has begun and
 * [onAir] says one is going out right now.
 */
data class TxProgress(
    val frames: List<String>,
    val started: Int,
    val onAir: Boolean,
) {
    val total: Int get() = frames.size

    /** Frames that have gone out in full. */
    val done: Int get() = (if (onAir) started - 1 else started).coerceIn(0, total)

    /** The frame on air now, numbered from 1; 0 when none is. */
    val onAirNumber: Int get() = if (onAir && started in 1..total) started else 0

    val sentText: String get() = frames.take(done).joinToString("")
    val onAirText: String get() = if (onAirNumber > 0) frames[onAirNumber - 1] else ""
    val queuedText: String get() = frames.drop(done + if (onAirNumber > 0) 1 else 0).joinToString("")

    /** Every frame has gone out. */
    val complete: Boolean get() = total > 0 && done >= total

    companion object {
        /**
         * Frame texts for display: an identified send starts "MYCALL: ", which the
         * bar already says (it is our own send), so that lead is taken off,
         * across frames if a long compound call spans more than one. Frame
         * boundaries are kept, so progress still lines up with the air.
         */
        fun forDisplay(frames: List<String>, myCall: String): List<String> {
            val call = myCall.trim()
            if (call.isEmpty()) return frames
            val joined = frames.joinToString("")
            val lead = Regex("^" + Regex.escape(call) + ":\\s*", RegexOption.IGNORE_CASE).find(joined)?.value?.length ?: 0
            if (lead == 0) return frames
            var remaining = lead
            return frames.map { f ->
                val cut = minOf(remaining, f.length)
                remaining -= cut
                f.substring(cut)
            }
        }
    }
}
