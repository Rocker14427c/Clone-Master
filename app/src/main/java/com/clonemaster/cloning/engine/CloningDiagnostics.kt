package com.clonemaster.cloning.engine

/**
 * Detailed cloning diagnostics – collects logs for rollback/error handling
 */
class CloningDiagnostics {
    private val logs = mutableListOf<LogEntry>()
    var hasError = false

    data class LogEntry(val level: Level, val message: String, val timestamp: Long = System.currentTimeMillis())
    enum class Level { INFO, WARN, ERROR, DEBUG }

    fun log(msg: String) { logs.add(LogEntry(Level.INFO, msg)) }
    fun warn(msg: String) { logs.add(LogEntry(Level.WARN, msg)) }
    fun error(msg: String) { hasError = true; logs.add(LogEntry(Level.ERROR, msg)) }
    fun debug(msg: String) { logs.add(LogEntry(Level.DEBUG, msg)) }

    fun getLogs(): List<LogEntry> = logs.toList()
    fun getReport(): String = buildString {
        appendLine("=== Clone-Master Diagnostics ===")
        logs.forEach { appendLine("[${it.level}] ${it.message}") }
        appendLine("HasError=$hasError")
    }

    fun clear() { logs.clear(); hasError = false }
}
