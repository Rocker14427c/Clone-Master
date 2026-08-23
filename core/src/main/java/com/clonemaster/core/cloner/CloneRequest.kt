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
    /**
     * Optional app label override (General > Clone App Name). Applied as a
     * literal string to <application android:label> and to labels of
     * MAIN+LAUNCHER activities/aliases that carry an explicit label.
     * Modify-only: attributes are never added, so missing labels are reported
     * as warnings instead.
     */
    val labelOverride: String? = null,
    /** Optional versionName override (manifest android:versionName; modify-only). */
    val versionNameOverride: String? = null,
    /** Optional versionCode override (manifest android:versionCode; modify-only). */
    val versionCodeOverride: Long? = null,
    /**
     * When true, known branding asset files (asset path contains "branding"
     * or "app_cloner", case-insensitive) are dropped from the clone.
     */
    val removeBranding: Boolean = false,
    /**
     * Runtime delivery: prebuilt, self-contained dex of the clone runtime
     * (classes only; no libraries). Required when [wrapApplication] is true.
     * Injected as classes(N+1).dex so the original dex set and order stay
     * untouched (PathClassLoader loads every classes*.dex in the APK).
     */
    val runtimeDex: ByteArray? = null,
    /**
     * When true (and a runtime is injected), the runtime meta JSON carries
     * "fileLog":true so the clone mirrors runtime events into
     * files/cloner/rt.log for logcat-free diagnosis. Default OFF — only ever
     * present in clones that already carry the optional-feature runtime.
     */
    val runtimeFileLog: Boolean = false
)
