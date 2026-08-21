package com.clonemaster.databundle

import android.content.Intent
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

/**
 * First-run import screen – shown when bundled data is present on first install
 * Displays progress bar and messages: "Importing application data...", "Restoring files...", etc.
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

        // Check if migration already completed
        if (restoreEngine.hasCompletedMigration()) {
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
            textStatus.text = "Invalid data bundle"
            buttonRetry.isEnabled = true
            buttonRetry.setOnClickListener { retry() }
            return
        }

        // Start restore
        startRestore()

        buttonRetry.setOnClickListener { retry() }
        buttonContinue.setOnClickListener { launchClonedApp() }
    }

    private fun loadManifest(): DataBundleManifest? {
        return try {
            // Try assets/manifest.json
            val manifestJson = assets.open("manifest.json").bufferedReader().readText()
            Gson().fromJson(manifestJson, DataBundleManifest::class.java)
        } catch (e: Exception) {
            try {
                // Try files dir manifest
                val manifestFile = File(filesDir, "manifest.json")
                if (manifestFile.exists()) {
                    Gson().fromJson(manifestFile.readText(), DataBundleManifest::class.java)
                } else null
            } catch (_: Exception) { null }
        }
    }

    private fun startRestore() {
        val archive = archiveFile ?: return
        val mf = manifest ?: return
        val config = DataBundleConfig() // would load from clone_config.json

        Thread {
            val result = restoreEngine.restore(archive, mf, config) { progress ->
                runOnUiThread {
                    updateProgress(progress)
                }
            }

            runOnUiThread {
                onRestoreComplete(result)
            }
        }.start()
    }

    private fun updateProgress(progress: DataRestoreEngine.RestoreProgress) {
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
        progressBar.progress = 100
        if (result.success) {
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

            // Auto-launch after 2 seconds if no warnings
            if (result.warnings.isEmpty()) {
                textDetail.postDelayed({ launchClonedApp() }, 2000)
            }
        } else {
            textStatus.text = "Import failed"
            textDetail.text = "Errors: ${result.errors.joinToString(", ")}"
            textLog.text = result.log
            buttonRetry.isEnabled = true
        }
    }

    private fun retry() {
        restoreEngine.allowRetry()
        textLog.text = ""
        buttonRetry.isEnabled = false
        buttonContinue.isEnabled = false
        startRestore()
    }

    private fun launchClonedApp() {
        // Launch main activity of cloned app
        // In real clone, this would be the original app's launcher activity
        // For now, just finish and let system launch default
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                // Avoid launching self again – launch original's main activity if known
                // For demo, just finish
                finish()
            } else {
                finish()
            }
        } catch (e: Exception) {
            finish()
        }
    }
}
