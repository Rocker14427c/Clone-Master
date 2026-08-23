package com.clonemaster.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the pure diagnostics core: line formatting, rotation policy,
 * session archiving, secret sanitization. No Android imports involved.
 */
class DiagLogCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `formatLine produces level tag message with timestamp`() {
        val line = DiagLogCore.formatLine("I", "Clone", "hello world", 0L)
        assertTrue(line.endsWith(" I/Clone: hello world"))
        assertTrue("timestamp prefix 'MM-dd HH:mm:ss.SSS' expected: $line",
            line.matches(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} I/Clone: hello world")))
    }

    @Test
    fun `sanitize masks sensitive json values`() {
        val json = """{"clonePackage":"x.clone1","dataBundle":{"encryptionPassword":"s3cret!"},"proxy":{"password": "hunter2","auth":"tok"},"count":3}"""
        val out = DiagLogCore.sanitize(json)
        assertFalse("raw password leaked: $out", out.contains("s3cret"))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("\"tok\""))
        assertTrue("non-sensitive value must survive", out.contains("x.clone1"))
        assertTrue(out.contains("\"count\":3") || out.contains("\"count\" : 3"))
        assertTrue(out.contains("\"***\""))
    }

    @Test
    fun `rotating writer rotates at size cap and survives reopen`() {
        val dir = tmp.newFolder("diag")
        val w = DiagLogCore.RotatingFileLog(dir, maxBytes = 256)
        repeat(40) { w.append("line-$it " + "x".repeat(20)) }
        w.close()
        assertTrue("rotation must have happened", w.rotates >= 1)
        val rotated = File(dir, "session.1.log")
        val current = File(dir, "session.log")
        assertTrue(rotated.exists())
        assertTrue(current.exists())
        assertTrue(current.length() <= 256 + 100)
        assertFalse(w.broken)
    }

    @Test
    fun `rotateForNewSession archives previous session`() {
        val dir = tmp.newFolder("diag")
        File(dir, "session.log").writeText("old-session-content\n")
        DiagLogCore.rotateForNewSession(dir)
        assertFalse(File(dir, "session.log").exists())
        assertEquals("old-session-content\n", File(dir, "previous.log").readText())
        // second rotation with an existing previous.log must not throw and keeps newest
        File(dir, "session.log").writeText("newer\n")
        DiagLogCore.rotateForNewSession(dir)
        assertEquals("newer\n", File(dir, "previous.log").readText())
    }

    @Test
    fun `readTail returns tail of oversized file with truncation marker`() {
        val f = tmp.newFile("big.log")
        val sb = StringBuilder()
        repeat(200) { sb.append("row-$it padded padded padded\n") }
        f.writeText(sb.toString())
        val tail = DiagLogCore.readTail(f, 500)
        assertTrue(tail.length <= 600)
        assertTrue(tail.contains("row-199"))
        assertTrue(tail.startsWith("…(earlier lines truncated)…"))
        assertFalse(tail.contains("row-0 "))
    }

    @Test
    fun `session header contains fields`() {
        val h = DiagLogCore.sessionHeader(linkedMapOf("device" to "Pixel 7", "android" to "14"), 0L)
        assertTrue(h.contains("device: Pixel 7"))
        assertTrue(h.contains("android: 14"))
        assertTrue(h.contains("Clone-Master diagnostic session"))
    }
}
