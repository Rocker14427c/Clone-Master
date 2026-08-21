package com.clonemaster.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
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
            resultApk?.let { apk ->
                try {
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(androidx.core.content.FileProvider.getUriForFile(this@BuildProgressActivity, "${packageName}.fileprovider", apk), "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(installIntent)
                } catch (ignored: Exception) {
                    android.util.Log.e("CloneMaster", "Install failed: ${ignored.message}", ignored)
                    textDetail.text = "Install failed: ${ignored.message} – grant REQUEST_INSTALL_PACKAGES permission"
                }
            }
        }

        buttonExport.setOnClickListener {
            resultApk?.let { apk ->
                try {
                    val exportDir = getExternalFilesDir("exports")
                    exportDir?.mkdirs()
                    val exportFile = File(exportDir, apk.name)
                    apk.copyTo(exportFile, overwrite = true)
                    android.widget.Toast.makeText(this, "Exported to ${exportFile.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                } catch (ignored: Exception) {
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
                            updateProgress(BuildStage.COMPLETE, "Build complete: ${resultApk?.absolutePath}")
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
}
