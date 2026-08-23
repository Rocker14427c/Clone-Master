package com.clonemaster.ui

/**
 * Pure value converters between UI dropdown labels and config values.
 * Extracted so they are JVM-testable without Android (device-verified defect:
 * "Portrait (1)".toIntOrNull() == null silently mapped every orientation
 * choice back to -1 = off, and a stored 1 no longer matched any label).
 */
object OptionValueParsers {

    val ORIENTATION_LABELS = listOf("Default (-1)", "Portrait (1)", "Landscape (0)", "Sensor (4)")

    /** "Portrait (1)" -> 1 ; "1" -> 1 ; junk -> -1 (off). */
    fun parseOrientation(value: String): Int {
        val paren = value.substringAfterLast('(', "").removeSuffix(")")
        return paren.toIntOrNull() ?: value.toIntOrNull() ?: -1
    }

    /** Stored int -> the matching label (for pre-selecting the dropdown). */
    fun orientationLabel(code: Int): String =
        ORIENTATION_LABELS.firstOrNull { it.endsWith("($code)") } ?: ORIENTATION_LABELS[0]

    /** Index of the label matching a stored value (raw toString of config). */
    fun orientationIndex(stored: String): Int {
        val code = stored.toIntOrNull() ?: return 0
        return ORIENTATION_LABELS.indexOfFirst { it.endsWith("($code)") }.coerceAtLeast(0)
    }
}
