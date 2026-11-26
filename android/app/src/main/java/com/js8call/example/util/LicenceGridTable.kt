package com.js8call.example.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Reader for callgrid.bin: US amateur callsign -> approximate 4-character grid square, built from
 * the FCC licence file's ZIP codes by tools/callgrid/build_callgrid.py (issue #55). A licence
 * address is often not where the station is (portable, a PO box, an old address), so callers
 * show these grids as approximate and never send them, export them or use them as evidence.
 *
 * Format (big-endian): "JS8G", version 1, 3 reserved bytes, count u32, data date u32 (yyyymmdd),
 * then count records of {call u32, grid u16} sorted by call. Plain Kotlin, tested against the
 * builder's test vectors.
 */
class LicenceGridTable private constructor(
    private val buf: ByteBuffer,
    val count: Int,
    /** yyyymmdd the licence data was taken. */
    val dataDate: Int,
) {
    /** The approximate grid for [call], or null (not in the table, a portable or foreign call, malformed). */
    fun lookup(call: String): String? {
        val code = encodeCall(call) ?: return null
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val pos = HEADER + mid * RECORD
            val v = buf.getInt(pos).toLong() and 0xFFFF_FFFFL
            when {
                v < code -> lo = mid + 1
                v > code -> hi = mid - 1
                else -> return gridText(buf.getShort(pos + 4).toInt() and 0xFFFF)
            }
        }
        return null
    }

    companion object {
        private const val HEADER = 16
        private const val RECORD = 6
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val CALL = Regex("^[A-Z0-9]{3,6}$")

        /** The table in [data], or null if it isn't a well-formed version-1 file. */
        fun parse(data: ByteBuffer): LicenceGridTable? {
            val b = data.duplicate().order(ByteOrder.BIG_ENDIAN)
            if (b.capacity() < HEADER) return null
            if (b.get(0) != 'J'.code.toByte() || b.get(1) != 'S'.code.toByte() ||
                b.get(2) != '8'.code.toByte() || b.get(3) != 'G'.code.toByte()) return null
            if (b.get(4).toInt() != 1) return null
            val count = b.getInt(8)
            if (count < 0 || count.toLong() * RECORD + HEADER > b.capacity()) return null
            return LicenceGridTable(b, count, b.getInt(12))
        }

        /**
         * Base-37 value of a callsign as the builder writes it, or null if it can't be in the
         * table: anything with "/" (portable, or operating from elsewhere: the licence address
         * says nothing about where it is), or not 3 to 6 letters and digits.
         */
        fun encodeCall(call: String): Long? {
            val c = call.trim().uppercase(Locale.ROOT)
            if (!CALL.matches(c)) return null
            var v = 0L
            for (i in 0 until 6) {
                v = v * 37 + if (i < c.length) ALPHABET.indexOf(c[i]) + 1 else 0
            }
            return v
        }

        /** 4-character grid text for an index 0..32399, or null out of range. */
        fun gridText(index: Int): String? {
            if (index !in 0 until 32_400) return null
            val sLat = index % 10
            val sLon = (index / 10) % 10
            val f = index / 100
            return "${'A' + f / 18}${'A' + f % 18}$sLon$sLat"
        }
    }
}
