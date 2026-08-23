package com.clonemaster.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clonemaster.R
import com.clonemaster.cloning.engine.CloneEngine
import com.clonemaster.cloning.models.CloneConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Build progress screen – shows actual progress: Analyze → Transform manifest → Transform resources → Transform DEX → Process native libraries → Apply hooks → Bundle data → Sign → Verify → Complete
 * Shows meaningful errors rather than generic failure
 */
class BuildProgressActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var textStage: TextView
    private lateinit var textDetail: TextView
    private lateinit var textLog: TextView
    private lateinit var buttonInstall: Button
    private lateinit var buttonExport: Button

    private lateinit var cloneEngine: CloneEngine
    private lateinit var config: CloneConfig

    private var resultApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_build_progress)

        progressBar = findViewById(R.id.progressBar)
        textStage = findViewById(R.id.textStage)
        textDetail = findViewById(R.id.textDetail)
        textLog = findViewById(R.id.textLog)
        buttonInstall = findViewById(R.id.buttonInstall)
        buttonExport = findViewById(R.id.buttonExport)

        cloneEngine = CloneEngine(this)

        val configJson = intent.getStringExtra("configJson") ?: run {
            textStage.text = "Invalid configuration"
            return
        }

        config = try {
            Gson().fromJson(configJson, CloneConfig::class.java)
        } catch (ignored: Exception) {
            textStage.text = "Failed to parse config: ${ignored.message}"
            return
        }

        buttonInstall.isEnabled = false
        buttonExport.isEnabled = false

        buttonInstall.setOnClickListener {
            resultApk?.let { apk -> installApk(apk) }
        }

        buttonExport.setOnClickListener {
            resultApk?.let { apk ->
                try {
                    val exportDir = getExternalFilesDir("exports")
                    exportDir?.mkdirs()
                    val exportFile = File(exportDir, apk.name)
                    apk.copyTo(exportFile, overwrite = true)
                    com.clonemaster.diagnostics.DiagLog.i("Export", "APK exported to ${exportFile.absolutePath}")
                    android.widget.Toast.makeText(this, "Exported to ${exportFile.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                } catch (ignored: Exception) {
                    com.clonemaster.diagnostics.DiagLog.e("Export", "export failed: ${ignored.message}", ignored)
                    android.widget.Toast.makeText(this, "Export failed: ${ignored.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        startBuild()
    }

    private fun startBuild() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = cloneEngine.clone(config) { progress ->
                    // Map progress messages to stages
                    val stage = mapProgressToStage(progress)
                    launch(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            updateProgress(stage, progress)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        if (result.isSuccess) {
                            resultApk = result.getOrNull()
                            // Honest wording: build+validation verified; INSTALLATION not verified yet
                            updateProgress(BuildStage.COMPLETE, "Build complete & validated (ZIP/manifest/DEX/alignment/CRC/signature checks passed). Installation not yet verified – tap Install.")
                            buttonInstall.isEnabled = true
                            buttonExport.isEnabled = true
                            textLog.append("\nDiagnostics:\n${cloneEngine.getDiagnostics().getReport()}")
                        } else {
                            val error = result.exceptionOrNull()
                            updateProgress(BuildStage.FAILED, "Build failed: ${error?.message}")
                            textLog.append("\nError: ${error?.stackTraceToString()?.take(2000)}\n")
                            textLog.append("\nDiagnostics:\n${cloneEngine.getDiagnostics().getReport()}")
                        }
                    }
                }

            } catch (ignored: Exception) {
                android.util.Log.e("CloneMaster", "Build failed", ignored)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        updateProgress(BuildStage.FAILED, "Build failed: ${ignored.message}")
                        textLog.text = ignored.stackTraceToString()
                    }
                }
            }
        }
    }

    enum class BuildStage(val displayName: String, val progressPercent: Int) {
        ANALYZE("Analyze", 10),
        TRANSFORM_MANIFEST("Transform manifest", 20),
        TRANSFORM_RESOURCES("Transform resources", 30),
        TRANSFORM_DEX("Transform DEX", 45),
        PROCESS_NATIVE_LIBS("Process native libraries", 55),
        APPLY_HOOKS("Apply hooks", 65),
        BUNDLE_DATA("Bundle data", 75),
        SIGN("Sign", 85),
        VERIFY("Verify", 95),
        COMPLETE("Complete", 100),
        FAILED("Failed", 0)
    }

    private fun mapProgressToStage(progressMessage: String): BuildStage {
        val lower = progressMessage.lowercase()
        return when {
            lower.contains("analyzing") || lower.contains("starting") || lower.contains("decoding") -> BuildStage.ANALYZE
            lower.contains("manifest") -> BuildStage.TRANSFORM_MANIFEST
            lower.contains("resources") -> BuildStage.TRANSFORM_RESOURCES
            lower.contains("dex") || lower.contains("smali") -> BuildStage.TRANSFORM_DEX
            lower.contains("native") || lower.contains("lib") -> BuildStage.PROCESS_NATIVE_LIBS
            lower.contains("hooks") || lower.contains("bundling config") || lower.contains("device profile") -> BuildStage.APPLY_HOOKS
            lower.contains("bundling") || lower.contains("obb") || lower.contains("data") && !lower.contains("config") -> BuildStage.BUNDLE_DATA
            lower.contains("building apk") -> BuildStage.APPLY_HOOKS
            lower.contains("signing") -> BuildStage.SIGN
            lower.contains("verified") || lower.contains("verify") -> BuildStage.VERIFY
            lower.contains("complete") -> BuildStage.COMPLETE
            lower.contains("failed") -> BuildStage.FAILED
            else -> BuildStage.ANALYZE
        }
    }

    private fun updateProgress(stage: BuildStage, detail: String) {
        progressBar.progress = stage.progressPercent
        textStage.text = stage.displayName
        textDetail.text = detail
        textLog.append("$detail\n")
    }

    // ---------------------------------------------------------------- install

    private var installReceiver: BroadcastReceiver? = null

    override fun onResume() {
        super.onResume()
        if (installReceiver == null) {
            installReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                    val msg = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    when (status) {
                        PackageInstaller.STATUS_SUCCESS -> {
                            textDetail.text = "Installed successfully (PackageInstaller OK)."
                            com.clonemaster.diagnostics.DiagLog.logInstall("SUCCESS", config.clonePackage)
                            Toast.makeText(this@BuildProgressActivity, "Clone installed ✓", Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            // Surface the REAL Android failure instead of a generic message
                            val statusText = statusText(status)
                            textDetail.text = "Install FAILED ($statusText): $msg — the generated APK was rejected by the device."
                            textLog.append("\n[Install] FAILED ($statusText): $msg\n")
                            com.clonemaster.diagnostics.DiagLog.logInstall(statusText, "$msg (package=${config.clonePackage})")
                            android.util.Log.e("CloneMaster", "PackageInstaller FAILED status=$status msg=$msg")
                        }
                    }
                }
            }

            // CRASH FIX (device: SecurityException "One of RECEIVER_EXPORTED or
            // RECEIVER_NOT_EXPORTED should be specified ..." on Android 14+):
            // This receiver ONLY handles our own internal install-result broadcast
            // ("com.clonemaster.INSTALL_RESULT"), stemmed from a PendingIntent that
            // THIS app created for PackageInstaller.session.commit(). It must never
            // receive broadcasts from other apps, and does not need to be visible to
            // them -> RECEIVER_NOT_EXPORTED is the correct (and secure) behavior.
            //
            // ContextCompat.registerReceiver applies the right call per API level:
            //   - API 33+ : passes Context.RECEIVER_NOT_EXPORTED to framework
            //   - API <33 : plain registerReceiver (flags not required there)
            // so the app keeps working across its whole minSdk 24 .. targetSdk 34
            // range without deprecated or version-broken patterns.
            try {
                androidx.core.content.ContextCompat.registerReceiver(
                    this,
                    installReceiver,
                    IntentFilter("com.clonemaster.INSTALL_RESULT"),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } catch (e: Exception) {
                // Never crash the activity: registration failure only disables
                // install-result feedback, not the build itself.
                android.util.Log.e("CloneMaster", "Install receiver registration failed: ${e.message}", e)
                textDetail.text = "Install-result feedback unavailable (${e.message}); use Export + adb install."
            }
        }
    }

    override fun onPause() {
        super.onPause()
        installReceiver?.let { unregisterReceiver(it) }
        installReceiver = null
    }

    private fun statusText(status: Int?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "FAILURE_ABORTED"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "FAILURE_BLOCKED"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "FAILURE_CONFLICT"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILURE_INCOMPATIBLE"
        PackageInstaller.STATUS_FAILURE_INVALID -> "FAILURE_INVALID"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "FAILURE_STORAGE"
        else -> "UNKNOWN($status)"
    }

    /**
     * Installs the generated (validated) APK via PackageInstaller so the real
     * Android error is captured and shown. Falls back to the system package
     * installer (ACTION_VIEW + FileProvider) if the session API is unavailable.
     */
    private fun installApk(apk: File) {
        if (!apk.exists() || apk.length() == 0L) {
            textDetail.text = "Install failed: APK missing or empty (${apk.absolutePath})"
            return
        }
        // Android 8+: request "install unknown apps" permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            textDetail.text = "Allow 'Install unknown apps' for Clone-Master, then tap Install again."
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ignored: Exception) {
                textDetail.text = "Could not open app-install settings: ${ignored.message}"
            }
            return
        }
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(config.clonePackage)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out) }
                }
            } catch (e: Exception) {
                try { session.abandon() } catch (ignored: Exception) {}
                throw e
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, 1001,
                Intent("com.clonemaster.INSTALL_RESULT").setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(pendingIntent.intentSender)
            textDetail.text = "Installing ${apk.name}…"
        } catch (e: Exception) {
            android.util.Log.e("CloneMaster", "PackageInstaller path failed: ${e.message}", e)
            // Fallback: system package installer via FileProvider
            try {
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        androidx.core.content.FileProvider.getUriForFile(this@BuildProgressActivity, "${packageName}.fileprovider", apk),
                        "application/vnd.android.package-archive"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(installIntent)
                textDetail.text = "Opened system package installer (no status feedback available). If it fails, use `adb install` and report the error."
            } catch (ex: Exception) {
                textDetail.text = "Install failed: ${ex.message}"
            }
        }
    }
}
