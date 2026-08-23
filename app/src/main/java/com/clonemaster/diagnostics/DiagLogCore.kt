package com.clonemaster.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Pure-JVM core of the Clone-Master diagnostics log. Contains NO Android
 * imports so it is fully unit-testable on the desktop (see DiagLogCoreTest).
 * The Android facade (DiagLog) wires lifecycle hooks, logcat mirroring and the
 * crash handler around this core.
 *
 * On-disk layout under filesDir/diag/:
 *   session.log      current app session (rotates to session.1.log at maxBytes)
 *   session.1.log    previous rotation slot of the current session
 *   previous.log     the last session before process start (archived at init)
 *   crash-last.txt   stacktrace of the last uncaught exception (if any)
 *   crash.flag       marker written by the crash handler, read once at init
 */
object DiagLogCore {

    const val SESSION_FILE = "session.log"
    const val SESSION_ROTATED_FILE = "session.1.log"
    const val PREVIOUS_FILE = "previous.log"
    const val CRASH_FILE = "crash-last.txt"
    const val CRASH_FLAG = "crash.flag"

    private val LINE_TS = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val HEADER_TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)

    fun formatLine(level: String, tag: String, message: String, timestampMs: Long): String =
        synchronized(LINE_TS) { LINE_TS.format(Date(timestampMs)) } + " " + level + "/" + tag + ": " + message

    fun headerTimestamp(timestampMs: Long): String =
        synchronized(HEADER_TS) { HEADER_TS.format(Date(timestampMs)) }

    /**
     * Masks values of sensitive-looking JSON keys so a shared log never leaks
     * user secrets (encryption passwords, proxy credentials, API tokens...).
     * Conservative: any key containing a sensitive word is masked.
     */
    private val SENSITIVE_KEY: Pattern = Pattern.compile(
        "(\"[^\"]*(?:password|passwd|secret|token|api[_-]?key|private[_-]?key|credential|auth)[^\"]*\"\\s*:\\s*)" +
                "(\"(?:[^\"\\\\]|\\\\.)*\"|-?[0-9]+(?:\\.[0-9]+)?|true|false|null)",
        Pattern.CASE_INSENSITIVE
    )

    fun sanitize(text: String): String {
        if (text.isEmpty()) return text
        val m = SENSITIVE_KEY.matcher(text)
        val out = StringBuffer(text.length + 16)
        while (m.find()) {
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group(1) + "\"***\""))
        }
        m.appendTail(out)
        return out.toString()
    }

    /** Multi-line session header from ordered fields (device info, app info, ...). */
    fun sessionHeader(fields: Map<String, String>, timestampMs: Long): String = buildString {
        appendLine("================ Clone-Master diagnostic session ================")
        appendLine("session started: " + headerTimestamp(timestampMs))
        for ((k, v) in fields) appendLine("$k: $v")
        appendLine("=================================================================")
    }

    /**
     * Prepares a fresh session inside [dir]: the previous session's file moves
     * to previous.log (overwritten), stale rotation slots are removed. Never
     * throws — diagnostics must never break the app.
     */
    fun rotateForNewSession(dir: File) {
        val cur = File(dir, SESSION_FILE)
        val rot = File(dir, SESSION_ROTATED_FILE)
        val prev = File(dir, PREVIOUS_FILE)
        try {
            if (rot.exists()) {
                // keep the rotated tail of the previous session by pre-pending it below
                val tail = readTail(rot, 256L * 1024L)
                if (cur.exists()) {
                    prev.writeText(tail + "\n" + cur.readText())
                    cur.delete()
                } else {
                    prev.writeText(tail)
                }
                rot.delete()
            } else if (cur.exists()) {
                if (prev.exists()) prev.delete()
                if (!cur.renameTo(prev)) {
                    prev.writeText(cur.readText())
                    cur.delete()
                }
            }
        } catch (ignored: Throwable) {
            try { cur.delete() } catch (ignored2: Throwable) {}
        }
    }

    /** Reads only the last [maxBytes] of [file] (utf-8, safe for multi-byte: small head-skip tolerance). */
    fun readTail(file: File, maxBytes: Long): String {
        if (!file.exists() || file.length() == 0L) return ""
        val len = file.length()
        val skip = if (len > maxBytes) len - maxBytes else 0L
        val buf = ByteArray((len - skip).toInt())
        file.inputStream().use { input ->
            var skipped = skip
            while (skipped > 0) {
                val s = input.skip(skipped)
                if (s <= 0) break
                skipped -= s
            }
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            if (off < buf.size) return String(buf, 0, off, Charsets.UTF_8)
        }
        val text = String(buf, Charsets.UTF_8)
        return if (skip > 0) {
            val nl = text.indexOf('\n')
            (if (nl in 0 until text.length - 1) text.substring(nl + 1) else text)
                .let { "…(earlier lines truncated)…\n" + it }
        } else text
    }

    /**
     * Synchronized, append-only log file writer with size-triggered rotation
     * into a single spare slot. Flush-per-line: the log must survive a crash.
     * Any IO failure permanently disables the writer (fail-off, never throw).
     */
    class RotatingFileLog(
        private val dir: File,
        baseName: String = "session",
        private val maxBytes: Long = 512L * 1024L
    ) {
        private val current = File(dir, "$baseName.log")
        private val rotated = File(dir, "$baseName.1.log")
        private var out: java.io.BufferedWriter? = null
        private var bytes: Long = -1L

        @Volatile var broken = false
            private set
        @Volatile var rotates: Int = 0
            private set

        @Synchronized
        fun append(line: String) {
            if (broken) return
            try {
                ensureOpen()
                val cost = line.length + 1L
                if (bytes + cost > maxBytes && bytes > 0) rotate()
                out!!.write(line)
                out!!.newLine()
                out!!.flush()
                bytes += cost
            } catch (t: Throwable) {
                broken = true
                closeQuietly()
            }
        }

        @Synchronized
        fun close() = closeQuietly()

        private fun openWriter(append: Boolean): java.io.BufferedWriter =
            java.io.BufferedWriter(
                java.io.OutputStreamWriter(java.io.FileOutputStream(current, append), Charsets.UTF_8),
                16 * 1024
            )

        private fun ensureOpen() {
            if (out != null) return
            dir.mkdirs()
            val append = current.exists()
            out = openWriter(append)
            bytes = if (append) current.length() else 0L
        }

        private fun rotate() {
            closeQuietly()
            if (rotated.exists()) rotated.delete()
            current.renameTo(rotated)
            out = openWriter(false)
            bytes = 0L
            rotates++
        }

        private fun closeQuietly() {
            try { out?.flush(); out?.close() } catch (ignored: Throwable) {}
            out = null
        }
    }
}
