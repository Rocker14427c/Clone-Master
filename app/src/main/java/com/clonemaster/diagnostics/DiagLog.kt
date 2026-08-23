package com.clonemaster.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android facade of the diagnostics system. One call to [init] (from the
 * Application class) gives you, with zero further setup:
 *
 *  - a persistent on-device log of what the user did and what the app did
 *    (activity opens, option changes, clone steps, install results, errors),
 *  - a crash handler that stores the full stacktrace for the next launch,
 *  - a "previous run crashed" marker,
 *  - a one-tap shareable report (see [buildReport]) so a user can hand the
 *    whole story to a developer without logcat or adb.
 *
 * Storage: app-private (files needed: none → no runtime permissions), files
 * payload capped at ~1.5 MB by rotation. Master switch lives in
 * SharedPreferences "diag_settings" (default ON — this is app-side telemetry
 * for debugging, never injected into clones, so it does not touch the 0/N
 * clean-clone rule).
 *
 * Everything is fail-off: diagnostics must NEVER crash the host app.
 */
object DiagLog {

    const val PREFS = "diag_settings"
    const val KEY_ENABLED = "enabled"
    const val KEY_VERBOSE = "verbose"
    const val KEY_RUNTIME_FILE_LOG = "runtime_file_log"

    private val initialized = AtomicBoolean(false)
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private var writer: DiagLogCore.RotatingFileLog? = null

    // ------------------------------------------------------------------ init

    @Synchronized
    fun init(application: Application) {
        if (!initialized.compareAndSet(false, true)) return
        app = application
        prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val dir = diagDir()
            DiagLogCore.rotateForNewSession(dir)
            val w = DiagLogCore.RotatingFileLog(dir)
            writer = w
            w.append(DiagLogCore.sessionHeader(deviceFields(), System.currentTimeMillis()).trimEnd())
            val crashFlag = File(dir, DiagLogCore.CRASH_FLAG)
            if (crashFlag.exists()) {
                w.append(DiagLogCore.formatLine("W", "App",
                    "PREVIOUS RUN CRASHED at " + DiagLogCore.headerTimestamp(crashFlag.lastModified()) +
                            " – crash detail is included in the shareable report", System.currentTimeMillis()))
                crashFlag.delete()
            }
            installCrashHandler(dir)
            registerActivityLogger(application)
        } catch (t: Throwable) {
            Log.e("CM-Diag", "DiagLog init failed (logcat-only mode): ${t.message}")
        }
    }

    private fun diagDir(): File = File(app.filesDir, "diag").apply { mkdirs() }

    private fun deviceFields(): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        fields["app"] = try {
            val pi: PackageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            "${pi.packageName} v${pi.versionName} (${pi.longVersionCode})"
        } catch (t: Throwable) {
            "${app.packageName} (version unreadable: ${t.message})"
        }
        fields["device"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        fields["android"] = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        fields["fingerprint"] = Build.FINGERPRINT ?: "?"
        fields["abis"] = Build.SUPPORTED_ABIS.joinToString(",")
        try {
            val st = android.os.StatFs(app.filesDir.absolutePath)
            fields["storage.free"] = "${st.availableBytes / (1024 * 1024)} MB"
        } catch (ignored: Throwable) {}
        fields["pid"] = Process.myPid().toString()
        fields["diagnostics.enabled"] = isEnabled().toString()
        fields["diagnostics.verbose"] = isVerbose().toString()
        return fields
    }

    // ------------------------------------------------------------- crash hook

    private fun installCrashHandler(dir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // mark first so even a failed write still flags the next launch
                File(dir, DiagLogCore.CRASH_FLAG).writeText(thread.name)
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val report = buildString {
                    appendLine("Crash at: " + DiagLogCore.headerTimestamp(System.currentTimeMillis()))
                    appendLine("Thread: ${thread.name}")
                    for ((k, v) in deviceFields()) appendLine("$k: $v")
                    appendLine("---------------- stacktrace ----------------")
                    append(sw.toString())
                }
                File(dir, DiagLogCore.CRASH_FILE).writeText(report)
                writer?.append(DiagLogCore.formatLine("E", "Crash",
                    "FATAL on ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message}",
                    System.currentTimeMillis()))
            } catch (ignored: Throwable) {
            } finally {
                // chain to the platform handler so Android still shows/records the crash
                try { previous?.uncaughtException(thread, throwable) } catch (ignored: Throwable) {}
            }
        }
    }

    private fun registerActivityLogger(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                i("UI", "open ${activity.javaClass.simpleName}")
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    // ---------------------------------------------------------------- logging

    fun isEnabled(): Boolean = if (::prefs.isInitialized) prefs.getBoolean(KEY_ENABLED, true) else true
    fun isVerbose(): Boolean = if (::prefs.isInitialized) prefs.getBoolean(KEY_VERBOSE, false) else false
    fun isRuntimeFileLog(): Boolean = if (::prefs.isInitialized) prefs.getBoolean(KEY_RUNTIME_FILE_LOG, false) else false

    fun setEnabled(v: Boolean) { if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_ENABLED, v).apply(); i("Diag", "diagnostics logging ${if (v) "ENABLED" else "DISABLED"}") }
    fun setVerbose(v: Boolean) { if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_VERBOSE, v).apply(); i("Diag", "verbose logging ${if (v) "ON" else "OFF"}") }
    fun setRuntimeFileLog(v: Boolean) { if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_RUNTIME_FILE_LOG, v).apply(); i("Diag", "clone runtime file log ${if (v) "ON (applies to newly built clones)" else "OFF"}") }

    fun d(tag: String, msg: String) = write("D", tag, msg, verboseOnly = true)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write("E", tag,
        if (tr != null) "$msg\n${stackString(tr)}" else msg)

    private fun write(level: String, tag: String, msg: String, verboseOnly: Boolean = false) {
        // logcat mirror: always for W/E (existing debugging flow), else gated
        try {
            when (level) {
                "E" -> Log.e("CM-$tag", msg)
                "W" -> Log.w("CM-$tag", msg)
                else -> if (!verboseOnly || isVerbose()) Log.d("CM-$tag", msg)
            }
        } catch (ignored: Throwable) {}
        if (!isEnabled()) return
        if (verboseOnly && !isVerbose()) return
        try {
            writer?.append(DiagLogCore.formatLine(level, tag, DiagLogCore.sanitize(msg).take(MAX_LINE), System.currentTimeMillis()))
        } catch (ignored: Throwable) {}
    }

    private const val MAX_LINE = 4096

    private fun stackString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    // ------------------------------------------------ domain helpers (what the user did)

    fun logOptionChanged(optionId: String, fieldPath: String, newValue: Any?) {
        i("Option", "set $fieldPath = $newValue   [$optionId]")
    }

    fun logCloneStart(originalPackage: String, clonePackage: String, featuresEnabled: Boolean, configJson: String) {
        i("Clone", "START $originalPackage -> $clonePackage (optional features ${if (featuresEnabled) "ON" else "OFF"})")
        d("Clone", "config json: $configJson")
    }

    fun logCloneResult(ok: Boolean, detail: String) {
        if (ok) i("Clone", "SUCCESS: $detail") else e("Clone", "FAILED: $detail")
    }

    fun logInstall(statusText: String, msg: String) {
        if (statusText == "SUCCESS") i("Install", "install SUCCESS ($msg)")
        else e("Install", "install FAILED ($statusText): $msg")
    }

    // ---------------------------------------------------------------- report

    fun hasCrashReport(): Boolean = try { File(diagDir(), DiagLogCore.CRASH_FILE).exists() } catch (t: Throwable) { false }

    /** Builds the shareable one-file report into the cache dir (FileProvider-exposed). */
    @Synchronized
    fun buildReport(): File? {
        if (!::app.isInitialized) return null
        return try {
            val dir = diagDir()
            val sb = StringBuilder(64 * 1024)
            sb.appendLine("############ Clone-Master diagnostics report ############")
            sb.append(DiagLogCore.sessionHeader(deviceFields(), System.currentTimeMillis()))
            val crash = File(dir, DiagLogCore.CRASH_FILE)
            if (crash.exists()) {
                sb.appendLine()
                sb.appendLine("########## LAST CRASH ##########")
                sb.appendLine(DiagLogCore.readTail(crash, 64L * 1024L).trimEnd())
            }
            sb.appendLine()
            sb.appendLine("########## CURRENT SESSION ##########")
            writer?.let { w -> w.append(DiagLogCore.formatLine("I", "Diag", "report generated", System.currentTimeMillis())) }
            val rotated = File(dir, DiagLogCore.SESSION_ROTATED_FILE)
            if (rotated.exists()) {
                sb.appendLine("--- earlier (rotated) part of this session ---")
                sb.appendLine(DiagLogCore.sanitize(DiagLogCore.readTail(rotated, 256L * 1024L)))
            }
            sb.appendLine(DiagLogCore.sanitize(DiagLogCore.readTail(File(dir, DiagLogCore.SESSION_FILE), 512L * 1024L)))
            val prev = File(dir, DiagLogCore.PREVIOUS_FILE)
            if (prev.exists()) {
                sb.appendLine()
                sb.appendLine("########## PREVIOUS SESSION ##########")
                sb.appendLine(DiagLogCore.sanitize(DiagLogCore.readTail(prev, 384L * 1024L)))
            }
            val out = File(app.cacheDir, "clone-master-diagnostics.txt")
            out.writeText(sb.toString())
            out
        } catch (t: Throwable) {
            Log.e("CM-Diag", "buildReport failed: ${t.message}")
            null
        }
    }

    @Synchronized
    fun clearLogs() {
        try {
            writer?.close()
            val dir = diagDir()
            listOf(DiagLogCore.SESSION_FILE, DiagLogCore.SESSION_ROTATED_FILE,
                DiagLogCore.PREVIOUS_FILE, DiagLogCore.CRASH_FILE, DiagLogCore.CRASH_FLAG
            ).forEach { File(dir, it).delete() }
            val w = DiagLogCore.RotatingFileLog(dir)
            writer = w
            w.append(DiagLogCore.sessionHeader(deviceFields(), System.currentTimeMillis()).trimEnd())
            w.append(DiagLogCore.formatLine("I", "Diag", "logs cleared by user", System.currentTimeMillis()))
        } catch (t: Throwable) {
            Log.e("CM-Diag", "clearLogs failed: ${t.message}")
        }
    }

    /** Text for the on-screen viewer (tail of current session, newest last). */
    fun currentSessionText(maxBytes: Long = 256L * 1024L): String = try {
        DiagLogCore.readTail(File(diagDir(), DiagLogCore.SESSION_FILE), maxBytes)
    } catch (t: Throwable) {
        "(log unavailable: ${t.message})"
    }
}
