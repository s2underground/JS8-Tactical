package com.js8call.example.interference

import java.util.Locale

/**
 * Plain-text storage for the learned baselines (one block per dial). Unreadable lines are
 * skipped, so a damaged file costs at most some learning, never a crash.
 */
object BaselineText {

    fun write(baselines: Map<Long, InterferenceDetector.DialBaseline>): String = buildString {
        for ((khz, b) in baselines.toSortedMap()) {
            append("D ").append(khz).append('\n')
            append("F ").append(b.floorDb.joinToString(" ") { f(it) }).append('\n')
            append("N ").append(b.floorN.joinToString(" ")).append('\n')
            append("R ").append(b.decodesPerWindow.joinToString(" ") { f(it) }).append('\n')
            append("S ").append(b.snrDb.joinToString(" ") { f(it) }).append('\n')
            append("T ").append(b.toneOccupancy.joinToString(" ") { f(it) }).append('\n')
        }
    }

    fun read(text: String): MutableMap<Long, InterferenceDetector.DialBaseline> {
        val out = HashMap<Long, InterferenceDetector.DialBaseline>()
        var cur: InterferenceDetector.DialBaseline? = null
        for (line in text.lineSequence()) {
            val parts = line.trim().split(' ')
            if (parts.size < 2) continue
            val values = parts.drop(1)
            when (parts[0]) {
                "D" -> cur = values[0].toLongOrNull()?.takeIf { it in 100..1_000_000 }?.let { k ->
                    InterferenceDetector.DialBaseline().also { out[k] = it }
                }
                "F" -> cur?.let { fill(values, it.floorDb) }
                "R" -> cur?.let { fill(values, it.decodesPerWindow) }
                "S" -> cur?.let { fill(values, it.snrDb) }
                "T" -> cur?.let { fill(values, it.toneOccupancy) }
                "N" -> cur?.let { b ->
                    if (values.size == b.floorN.size) values.forEachIndexed { i, v -> b.floorN[i] = v.toIntOrNull()?.coerceIn(0, InterferenceDetector.BASELINE_CAP) ?: 0 }
                }
            }
        }
        return out
    }

    private fun fill(values: List<String>, into: FloatArray) {
        if (values.size != into.size) return
        values.forEachIndexed { i, v -> into[i] = if (v == "NaN") Float.NaN else v.toFloatOrNull()?.takeIf { it.isFinite() } ?: into[i] }
    }

    private fun f(v: Float) = if (v.isNaN()) "NaN" else String.format(Locale.US, "%.2f", v)
}
