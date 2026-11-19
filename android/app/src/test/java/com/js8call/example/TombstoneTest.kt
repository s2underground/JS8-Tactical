package com.js8call.example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TombstoneTest {
    private fun varint(o: ByteArrayOutputStream, v: Long) {
        var x = v
        while (true) {
            if (x and 0x7F.inv().toLong() == 0L) { o.write(x.toInt()); return }
            o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
    }
    private fun tag(o: ByteArrayOutputStream, f: Int, w: Int) = varint(o, ((f shl 3) or w).toLong())
    private fun msg(o: ByteArrayOutputStream, f: Int, body: ByteArray) { tag(o, f, 2); varint(o, body.size.toLong()); o.write(body) }
    private fun str(o: ByteArrayOutputStream, f: Int, s: String) = msg(o, f, s.toByteArray())
    private fun num(o: ByteArrayOutputStream, f: Int, v: Long) { tag(o, f, 0); varint(o, v) }
    private fun build(block: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(block).toByteArray()

    private fun thread(id: Int, name: String, vararg frames: Pair<String, String>) = build { t ->
        num(t, 1, id.toLong()); str(t, 2, name)
        frames.forEachIndexed { i, (fn, file) ->
            msg(t, 4, build { f -> num(f, 1, 0x1000L + i); str(f, 4, fn); num(f, 5, 12); str(f, 6, file) })
        }
    }

    @Test
    fun readsTheCrashingThreadsBacktrace() {
        val bytes = build { o ->
            str(o, 2, "samsung/fingerprint")
            num(o, 6, 4242)
            msg(o, 10, build { s -> str(s, 2, "SIGSEGV"); str(s, 4, "SEGV_MAPERR"); num(s, 9, 0x10) })
            msg(o, 15, build { c -> str(c, 1, "null pointer dereference") })
            msg(o, 16, build { e -> num(e, 1, 7); msg(e, 2, thread(7, "FinalizerWatchd", "art::Monitor::Wait" to "/apex/libart.so")) })
            msg(o, 16, build { e -> num(e, 1, 4242); msg(e, 2, thread(4242, "GLThread 12", "mbgl::RenderImageSource::update" to "/data/app/base.apk!libmaplibre.so", "mbgl::Renderer::render" to "/data/app/base.apk!libmaplibre.so")) })
        }
        val s = Tombstone.summarize(bytes)
        assertTrue(s, s.contains("SIGSEGV / SEGV_MAPERR fault addr 0x10"))
        assertTrue(s, s.contains("Cause: null pointer dereference"))
        assertTrue(s, s.contains("Crashing thread: GLThread 12 (tid 4242)"))
        assertTrue(s, s.contains("#00 pc 00001000  base.apk!libmaplibre.so (mbgl::RenderImageSource::update+12)"))
        assertTrue(s, !s.contains("FinalizerWatchd"))
    }

    @Test
    fun garbageDoesNotThrow() {
        assertTrue(Tombstone.summarize(byteArrayOf(0x0a, 0x7f, 1, 2)).isNotEmpty())
    }
}
