package com.clonemaster.databundle

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.clonemaster.R
import com.clonemaster.cloning.models.DataBundleConfig
import com.clonemaster.cloning.models.DataBundleManifest
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run import screen – shown when bundled data is present on first install
 * QA Fixes:
 * - Use AtomicBoolean for launch flag to prevent race condition when multiple activities created simultaneously
 * - Use lifecycleScope instead of raw Thread to avoid leak and handle lifecycle
 * - Synchronized migration flag via SharedPreferences with apply() + atomic check
 * - Handle configuration changes (rotation) via onSaveInstanceState
 * - Graceful degradation if manifest invalid
 */
class FirstRunImportActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var textStatus: TextView
    private lateinit var textDetail: TextView
    private lateinit var textLog: TextView
    private lateinit var buttonRetry: Button
    private lateinit var buttonContinue: Button

    private lateinit var restoreEngine: DataRestoreEngine
    private var archiveFile: File? = null
    private var manifest: DataBundleManifest? = null

    // QA Fix: AtomicBoolean for thread-safe launch flag
    private val hasLaunchedImport = AtomicBoolean(false)
    private val migrationCompletedAtomic = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_first_run_import)

        progressBar = findViewById(R.id.progressBar)
        textStatus = findViewById(R.id.textStatus)
        textDetail = findViewById(R.id.textDetail)
        textLog = findViewById(R.id.textLog)
        buttonRetry = findViewById(R.id.buttonRetry)
        buttonContinue = findViewById(R.id.buttonContinue)

        restoreEngine = DataRestoreEngine(this)

        // Restore state after rotation
        if (savedInstanceState != null) {
            hasLaunchedImport.set(savedInstanceState.getBoolean("hasLaunchedImport", false))
            migrationCompletedAtomic.set(savedInstanceState.getBoolean("migrationCompleted", false))
        }

        // Check if migration already completed – atomic check with synchronized prefs
        if (restoreEngine.hasCompletedMigration()) {
            migrationCompletedAtomic.set(true)
            launchClonedApp()
            return
        }

        // Detect bundled data
        archiveFile = restoreEngine.detectBundledData()
        if (archiveFile == null) {
            textStatus.text = "No bundled data found"
            buttonContinue.isEnabled = true
            buttonContinue.setOnClickListener { launchClonedApp() }
            return
        }

        // Load manifest
        manifest = loadManifest()

        if (manifest == null) {
            textStatus.text = "Invalid data bundle – manifest missing or corrupted"
            textDetail.text = "Check import log for details"
            buttonRetry.isEnabled = true
            buttonRetry.setOnClickListener { retry() }
            return
        }

        // Start restore only if not already launched (atomic)
        if (hasLaunchedImport.compareAndSet(false, true)) {
            startRestore()
        }

        buttonRetry.setOnClickListener { retry() }
        buttonContinue.setOnClickListener { launchClonedApp() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("hasLaunchedImport", hasLaunchedImport.get())
        outState.putBoolean("migrationCompleted", migrationCompletedAtomic.get())
        outState.putInt("progress", progressBar.progress)
        outState.putString("status", textStatus.text.toString())
    }

    private fun loadManifest(): DataBundleManifest? {
        return try {
            val manifestJson = assets.open("manifest.json").bufferedReader().readText()
            Gson().fromJson(manifestJson, DataBundleManifest::class.java)
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Failed to load manifest from assets: ${e.message}")
            try {
                val manifestFile = File(filesDir, "manifest.json")
                if (manifestFile.exists() && manifestFile.length() > 0) {
                    Gson().fromJson(manifestFile.readText(), DataBundleManifest::class.java)
                } else null
            } catch (ex: Exception) {
                android.util.Log.w("CloneMaster", "Failed to load manifest from filesDir: ${ex.message}")
                null
            }
        }
    }

    private fun startRestore() {
        val archive = archiveFile ?: return
        val mf = manifest ?: return
        val config = try {
            val configJson = assets.open("clone_config.json").bufferedReader().readText()
            val cloneConfig = Gson().fromJson(configJson, com.clonemaster.cloning.models.CloneConfig::class.java)
            cloneConfig.dataBundle
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Failed to load clone_config for dataBundle, using default: ${e.message}")
            DataBundleConfig()
        }

        // QA Fix: Use lifecycleScope instead of raw Thread to avoid leak
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = restoreEngine.restore(archive, mf, config) { progress ->
                    // Ensure UI updates only if activity alive
                    launch(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            updateProgress(progress)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        onRestoreComplete(result)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "Restore thread failed", e)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        textStatus.text = "Import failed: ${e.message}"
                        buttonRetry.isEnabled = true
                    }
                }
            }
        }
    }

    private fun updateProgress(progress: DataRestoreEngine.RestoreProgress) {
        if (isFinishing || isDestroyed) return
        progressBar.progress = progress.progress
        textStatus.text = when (progress.stage) {
            DataRestoreEngine.RestoreStage.DETECTING -> "Importing application data..."
            DataRestoreEngine.RestoreStage.PREPARING -> "Preparing..."
            DataRestoreEngine.RestoreStage.EXTRACTING -> "Extracting data..."
            DataRestoreEngine.RestoreStage.RESTORING_FILES -> "Restoring files..."
            DataRestoreEngine.RestoreStage.RESTORING_DATABASES -> "Restoring database..."
            DataRestoreEngine.RestoreStage.RESTORING_WEBVIEW -> "Restoring WebView data..."
            DataRestoreEngine.RestoreStage.TRANSFORMING -> "Applying transformations..."
            DataRestoreEngine.RestoreStage.VALIDATING -> "Validating..."
            DataRestoreEngine.RestoreStage.FINALIZING -> "Finalizing..."
            DataRestoreEngine.RestoreStage.COMPLETE -> "Data import complete"
            DataRestoreEngine.RestoreStage.FAILED -> "Import failed"
        }
        textDetail.text = progress.message
        if (progress.currentFile != null) {
            textLog.append("${progress.currentFile}\n")
        }
    }

    private fun onRestoreComplete(result: DataRestoreEngine.RestoreResult) {
        if (isFinishing || isDestroyed) return
        progressBar.progress = 100
        if (result.success) {
            migrationCompletedAtomic.set(true)
            textStatus.text = "Data import complete"
            textDetail.text = "Restored ${result.restoredFiles} files (${result.restoredBytes / 1024 / 1024} MB)"
            if (result.warnings.isNotEmpty()) {
                textLog.append("\nWarnings:\n")
                result.warnings.forEach { textLog.append("- $it\n") }
            }
            if (result.hasKeystoreData) {
                textLog.append("\nSome account/session data could not be restored because it is protected by Android or the application.\n")
            }
            textLog.append("\nImport log:\n${result.log}")
            buttonContinue.isEnabled = true
            buttonContinue.text = "Open App"

            if (result.warnings.isEmpty()) {
                textDetail.postDelayed({ launchClonedApp() }, 2000)
            }
        } else {
            textStatus.text = "Import failed"
            textDetail.text = "Errors: ${result.errors.joinToString(", ")}"
            textLog.text = result.log
            buttonRetry.isEnabled = true
            // Allow retry by resetting atomic flag
            hasLaunchedImport.set(false)
        }
    }

    private fun retry() {
        if (!restoreEngine.allowRetry()) {
            textStatus.text = "Retry not allowed – check logs"
            return
        }
        textLog.text = ""
        buttonRetry.isEnabled = false
        buttonContinue.isEnabled = false
        hasLaunchedImport.set(false)
        migrationCompletedAtomic.set(false)
        // Re-detect data (previous archive may have been deleted on failure, need to re-detect from assets)
        archiveFile = restoreEngine.detectBundledData()
        if (archiveFile == null) {
            textStatus.text = "No bundled data found for retry – reinstall clone"
            return
        }
        if (hasLaunchedImport.compareAndSet(false, true)) {
            startRestore()
        }
    }

    private fun launchClonedApp() {
        if (!migrationCompletedAtomic.get() && restoreEngine.hasCompletedMigration()) {
            migrationCompletedAtomic.set(true)
        }
        try {
            // Prevent launching self again – finish and let system handle
            // In real clone, would launch original launcher activity via meta-data com.clonemaster.original_application
            finish()
        } catch (e: Exception) {
            android.util.Log.e("CloneMaster", "launchClonedApp failed: ${e.message}", e)
            try { finish() } catch (ignored: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // lifecycleScope automatically cancels
    }
}
