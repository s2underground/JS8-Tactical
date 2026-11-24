package com.js8call.example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's java.util.regex is ICU, not the JDK's engine, and a few patterns that the JVM (and
 * so these unit tests) accept fail on phones. The one that bit us: "[:" starts a POSIX class in
 * ICU ([:alpha:]), so a set like "[:-]" threw when DiagnosticsPrivacy loaded, on devices only.
 * This scans the app's sources for regex literals containing "[:" (write "[-:]" or "(?::|-)").
 */
class RegexPortabilityTest {
    @Test
    fun noPosixLookalikesInRegexLiterals() {
        val roots = listOf(File("src/main/java"), File("../../adapters/android/jni/kotlin")).filter { it.isDirectory }
        assertTrue("source folders not found from ${File(".").absolutePath}", roots.isNotEmpty())
        val literal = Regex("""(?:Regex|toRegex|Pattern\.compile)\(\s*"((?:[^"\\]|\\.)*)"""")
        val bad = roots.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.flatMap { f ->
                literal.findAll(f.readText()).filter { "[:" in it.groupValues[1] }.map { "${f.path}: ${it.groupValues[1]}" }
            }
        }
        assertTrue("ICU reads \"[:\" as a POSIX class:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
