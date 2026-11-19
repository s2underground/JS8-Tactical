package com.js8call.example

/**
 * Minimal reader for Android's native crash record (tombstone protobuf,
 * AOSP system/core/debuggerd/proto/tombstone.proto). Pulls out what's needed
 * to see where a native crash happened: signal, fault address, abort
 * message, causes, and the crashing thread's backtrace.
 */
object Tombstone {

    private class Reader(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (pos < end) {
                val c = b[pos++].toInt() and 0xFF
                result = result or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return result
                shift += 7
                if (shift > 63) break
            }
            return result
        }
        /** Returns (field, wireType). */
        fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }
        fun bytes(): Reader { val len = varint().toInt(); val r = Reader(b, pos, (pos + len).coerceAtMost(end)); pos = (pos + len).coerceAtMost(end); return r }
        fun string(): String { val r = bytes(); return String(b, r.pos, r.end - r.pos, Charsets.UTF_8) }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> pos = end
            }
        }
    }

    private class Frame(var relPc: Long = 0, var pc: Long = 0, var fn: String = "", var off: Long = 0, var file: String = "")
    private class Thread(var id: Int = 0, var name: String = "", val frames: MutableList<Frame> = mutableListOf(), val notes: MutableList<String> = mutableListOf())

    fun summarize(bytes: ByteArray): String = runCatching { parse(bytes) }.getOrElse { "(could not read native trace: ${it.message})\n" }

    private fun parse(bytes: ByteArray): String {
        val r = Reader(bytes)
        var tid = 0
        var signal = ""
        var abort = ""
        val causes = mutableListOf<String>()
        val threads = mutableMapOf<Int, Thread>()
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 6 && wire == 0 -> tid = r.varint().toInt()
                field == 10 && wire == 2 -> signal = readSignal(r.bytes())
                field == 14 && wire == 2 -> abort = r.string()
                field == 15 && wire == 2 -> {
                    val c = r.bytes()
                    while (c.more()) { val (f, w) = c.tag(); if (f == 1 && w == 2) causes += c.string() else c.skip(w) }
                }
                field == 16 && wire == 2 -> {
                    val e = r.bytes()
                    var key = 0; var t: Thread? = null
                    while (e.more()) {
                        val (f, w) = e.tag()
                        when {
                            f == 1 && w == 0 -> key = e.varint().toInt()
                            f == 2 && w == 2 -> t = readThread(e.bytes())
                            else -> e.skip(w)
                        }
                    }
                    t?.let { threads[if (key != 0) key else it.id] = it }
                }
                else -> r.skip(wire)
            }
        }
        val sb = StringBuilder()
        sb.append("Signal: ").append(signal.ifEmpty { "?" }).append('\n')
        if (abort.isNotEmpty()) sb.append("Abort message: ").append(abort).append('\n')
        causes.forEach { sb.append("Cause: ").append(it).append('\n') }
        val crashed = threads[tid]
        if (crashed == null) {
            sb.append("Crashing thread $tid not found (${threads.size} threads)\n")
        } else {
            sb.append("Crashing thread: ").append(crashed.name).append(" (tid ").append(tid).append(")\n")
            crashed.notes.forEach { sb.append("  note: ").append(it).append('\n') }
            crashed.frames.take(48).forEachIndexed { i, f ->
                sb.append(String.format(java.util.Locale.US, "  #%02d pc %08x  %s", i, f.relPc, f.file.substringAfterLast('/')))
                if (f.fn.isNotEmpty()) sb.append(" (").append(f.fn).append('+').append(f.off).append(')')
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun readSignal(r: Reader): String {
        var name = ""; var code = ""; var addr = -1L
        while (r.more()) {
            val (f, w) = r.tag()
            when {
                f == 2 && w == 2 -> name = r.string()
                f == 4 && w == 2 -> code = r.string()
                f == 9 && w == 0 -> addr = r.varint()
                else -> r.skip(w)
            }
        }
        return buildString {
            append(name)
            if (code.isNotEmpty()) append(" / ").append(code)
            if (addr >= 0) append(" fault addr 0x").append(java.lang.Long.toHexString(addr))
        }
    }

    private fun readThread(r: Reader): Thread {
        val t = Thread()
        while (r.more()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 0 -> t.id = r.varint().toInt()
                f == 2 && w == 2 -> t.name = r.string()
                f == 7 && w == 2 -> t.notes += r.string()
                f == 4 && w == 2 -> t.frames += readFrame(r.bytes())
                else -> r.skip(w)
            }
        }
        return t
    }

    private fun readFrame(r: Reader): Frame {
        val fr = Frame()
        while (r.more()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 0 -> fr.relPc = r.varint()
                f == 2 && w == 0 -> fr.pc = r.varint()
                f == 4 && w == 2 -> fr.fn = r.string()
                f == 5 && w == 0 -> fr.off = r.varint()
                f == 6 && w == 2 -> fr.file = r.string()
                else -> r.skip(w)
            }
        }
        return fr
    }
}
