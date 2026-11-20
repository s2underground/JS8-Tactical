package com.js8call.example

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReportArchiveTest {
    @Test
    fun repeatedReportsAppearOnce() {
        val crashA = "===== CRASH on thread 'main'\nWhen: 00:21Z\nboom A\n\tat x"
        val exitA = "===== Android exit record: Kotlin/Java crash\nWhen: 00:21Z"
        val crashB = "===== CRASH on thread 'main'\nWhen: 23:13Z\nboom B"
        val archive = listOf(
            "##### Report shown 01:10Z\nJS8-Tactical 1.1 (4)\n", crashA, exitA,
            "##### Report shown 01:11Z\nJS8-Tactical 1.1 (4)\n", crashA, exitA,
            "##### Report shown 23:24Z\nJS8-Tactical 1.1 (4, x)\n", crashB,
        ).joinToString("\n\n")
        val out = CrashReporter.distinctReports(archive)
        assertEquals(listOf(crashA, exitA, crashB).joinToString("\n\n", postfix = "\n"), out)
    }
}
