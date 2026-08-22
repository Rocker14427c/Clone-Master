package com.clonemaster.core.cloner

/** Diagnostics collector – honest reporting, no fake claims. */
class CloneDiag {
    val logs = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val errors = mutableListOf<String>()

    fun log(msg: String) { logs.add(msg) }
    fun warn(msg: String) { warnings.add(msg) }
    fun error(msg: String) { errors.add(msg) }

    val hasErrors: Boolean get() = errors.isNotEmpty()

    fun summary(): String = buildString {
        append("logs=${logs.size} warnings=${warnings.size} errors=${errors.size}")
        if (errors.isNotEmpty()) append(" ERRORS: ").append(errors.joinToString(" | "))
    }
}

/**
 * Pure, Android-free description of one clone build request.
 * The app module maps CloneConfig -> CloneRequest.
 */
data class CloneRequest(
    /** Original application package (matches manifest package attribute). */
    val originalPackage: String,
    /** New package for the clone (manifest package attribute). */
    val clonePackage: String,
    /** Authority rewrites: original authority -> new authority. */
    val authorityMap: Map<String, String> = emptyMap(),
    /** Additional entries to place under assets/ (name -> bytes). */
    val extraAssets: Map<String, ByteArray> = emptyMap(),
    /** When true, the application class is wrapped with the hook application class. (Phase 2) */
    val wrapApplication: Boolean = false,
    /** Optional app label override (not yet supported in native path). */
    val labelOverride: String? = null
)
