package com.js8call.example.ai

import java.io.File
import java.security.MessageDigest

/** SHA-256 of a file, streamed (model files are up to about 1 GB). */
object FileHash {
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Copies [src] to [dst] and returns the SHA-256 of the bytes written, so the hash checked is
     * the hash of the copy (nothing can change the source between the check and the copy).
     */
    fun copyAndHash(src: File, dst: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        src.inputStream().buffered(1 shl 20).use { input ->
            dst.outputStream().buffered(1 shl 20).use { output ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    output.write(buf, 0, n)
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
