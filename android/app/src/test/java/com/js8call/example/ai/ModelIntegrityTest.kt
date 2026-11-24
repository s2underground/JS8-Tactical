package com.js8call.example.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelIntegrityTest {

    @Test
    fun sha256OfAKnownFile() {
        val f = File.createTempFile("hash", ".bin")
        try {
            f.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileHash.sha256(f))
            f.writeBytes(ByteArray(3_000_000) { (it % 251).toByte() }) // spans several read buffers
            assertEquals(64, FileHash.sha256(f).length)
        } finally {
            f.delete()
        }
    }

    /** Every model downloads from a fixed revision and carries a full SHA-256 to check against. */
    @Test
    fun modelsArePinned() {
        for (m in AiModels.ALL) {
            assertTrue(m.url, Regex("""^https://huggingface\.co/Qwen/[^/]+/resolve/[0-9a-f]{40}/""").containsMatchIn(m.url))
            assertTrue(m.url, m.url.endsWith("/" + m.file))
            assertTrue(m.sha256, Regex("^[0-9a-f]{64}$").matches(m.sha256))
        }
        assertEquals(AiModels.ALL.size, AiModels.ALL.map { it.sha256 }.toSet().size)
    }
}
