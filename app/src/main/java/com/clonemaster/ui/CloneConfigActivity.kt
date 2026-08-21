package com.clonemaster.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clonemaster.R
import com.clonemaster.cloning.engine.CloneEngine
import com.clonemaster.cloning.models.CloneConfig
import com.clonemaster.databundle.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * QA Fix: Previously only had placeholder comments for categories and search
 * Now: Implements actual UI wiring for Save & Clone, Export Config, Backup APK with progress updates
 * Also implements AppAnalyzerActivity detail presentation and navigation to CloneConfigActivity
 */
class CloneConfigActivity : AppCompatActivity() {

    private lateinit var config: CloneConfig
    private lateinit var cloneEngine: CloneEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clone_config)

        cloneEngine = CloneEngine(this)

        val clonePackage = intent.getStringExtra("clonePackage")
        val originalPackage = intent.getStringExtra("originalPackage") ?: intent.getStringExtra("package")

        config = when {
            clonePackage != null -> loadConfig(clonePackage)
            originalPackage != null -> CloneConfig(
                originalPackage = originalPackage,
                clonePackage = "$originalPackage.clone1",
                cloneIndex = 1,
                appName = "${originalPackage.substringAfterLast('.')} (Clone)"
            )
            else -> CloneConfig()
        }

        // Setup UI elements
        val textClonePackage = findViewById<TextView>(R.id.clonePackage)
        val textAppName = findViewById<TextView>(R.id.appName)
        val textCategories = findViewById<TextView>(R.id.categories)
        val buttonSave = findViewById<Button>(R.id.save)
        val buttonExport = findViewById<Button>(R.id.export)
        val buttonBackup = findViewById<Button>(R.id.backup)

        textClonePackage.text = config.clonePackage
        textAppName.text = config.appName
        textCategories.text = """
            Categories: Core, Identity, Privacy, Display, ViewMod, Media, Navigation, Storage, Launching, Networking, Notification, Game, TV/Wear, Automation, Developer, WebView, AI, Environment Spoofing, Data Bundling, Functional Parity
            Search across all options: GPS, proxy, clipboard, dark mode, WebView, notification, root, emulator, AppsFlyer, CPU/GPU, etc.
        """.trimIndent()

        // Save & Clone – triggers CloneEngine.clone() with live progress updates on button
        buttonSave.setOnClickListener {
            buttonSave.isEnabled = false
            buttonSave.text = "Cloning... 0%"

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    saveConfig()
                    val result = cloneEngine.clone(config) { progress ->
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                buttonSave.text = progress.take(50)
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            if (result.isSuccess) {
                                buttonSave.text = "Clone Complete: ${result.getOrNull()?.name}"
                                android.widget.Toast.makeText(this@CloneConfigActivity, "Clone created: ${result.getOrNull()?.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                buttonSave.text = "Clone Failed – Tap to Retry"
                                buttonSave.isEnabled = true
                                android.widget.Toast.makeText(this@CloneConfigActivity, "Clone failed: ${result.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                } catch (ignored: Exception) {
                    android.util.Log.e("CloneMaster", "Save & Clone failed: ${ignored.message}", ignored)
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            buttonSave.text = "Clone Failed: ${ignored.message}"
                            buttonSave.isEnabled = true
                        }
                    }
                }
            }
        }

        // Export Config – exports JSON to external storage
        buttonExport.setOnClickListener {
            try {
                exportConfig()
                android.widget.Toast.makeText(this, "Config exported to ${getExternalFilesDir("exports")}", android.widget.Toast.LENGTH_SHORT).show()
            } catch (ignored: Exception) {
                android.util.Log.e("CloneMaster", "Export config failed: ${ignored.message}", ignored)
                android.widget.Toast.makeText(this, "Export failed: ${ignored.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Backup APK – creates full APK backup via BackupManager
        buttonBackup.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val backupManager = BackupManager(this@CloneConfigActivity)
                    val apkPath = packageManager.getApplicationInfo(config.originalPackage, 0).sourceDir
                    val apkFile = File(apkPath)
                    val outputDir = getExternalFilesDir("backups") ?: File(filesDir, "backups")

                    val backupFile = backupManager.exportCloneAndData(
                        cloneConfig = config,
                        apkFile = apkFile,
                        dataArchive = null,
                        outputDir = outputDir,
                        encrypt = false,
                        onProgress = { msg ->
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    buttonBackup.text = msg.take(40)
                                }
                            }
                        }
                    )

                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            buttonBackup.text = "Backup Complete"
                            android.widget.Toast.makeText(this@CloneConfigActivity, "Backup: ${backupFile.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }

                } catch (ignored: Exception) {
                    android.util.Log.e("CloneMaster", "Backup APK failed: ${ignored.message}", ignored)
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            buttonBackup.text = "Backup Failed"
                            android.widget.Toast.makeText(this@CloneConfigActivity, "Backup failed: ${ignored.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun loadConfig(pkg: String): CloneConfig {
        val file = File(filesDir, "clone_configs/$pkg.json")
        return try {
            if (file.exists() && file.length() > 0) {
                com.google.gson.Gson().fromJson(file.readText(), CloneConfig::class.java)
            } else {
                CloneConfig(clonePackage = pkg)
            }
        } catch (ignored: Exception) {
            android.util.Log.w("CloneMaster", "Failed to load config $pkg: ${ignored.message}")
            CloneConfig(clonePackage = pkg)
        }
    }

    fun saveConfig() {
        try {
            val dir = File(filesDir, "clone_configs").apply { mkdirs() }
            val file = File(dir, "${config.clonePackage}.json")
            file.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config))
            android.util.Log.d("CloneMaster", "Config saved: ${file.absolutePath}")
        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "saveConfig failed: ${ignored.message}", ignored)
        }
    }

    fun exportConfig() {
        val exportDir = getExternalFilesDir("exports") ?: File(filesDir, "exports")
        exportDir.mkdirs()
        val file = File(exportDir, "${config.clonePackage}_config.json")
        file.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config))
    }

    fun importConfig(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            config = com.google.gson.Gson().fromJson(text, CloneConfig::class.java)
            android.util.Log.d("CloneMaster", "Config imported from $uri")
        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "importConfig failed: ${ignored.message}", ignored)
        }
    }
}

class AppAnalyzerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analyzer)

        val pkg = intent.getStringExtra("package") ?: intent.getStringExtra("originalPackage") ?: run {
            android.util.Log.w("CloneMaster", "AppAnalyzerActivity: no package extra")
            return
        }

        val analyzer = com.clonemaster.analysis.AppAnalyzer(this)

        val textAppName = findViewById<TextView>(R.id.appName)
        val textPackage = findViewById<TextView>(R.id.packageName)
        val textVersion = findViewById<TextView>(R.id.version)
        val textDetails = findViewById<TextView>(R.id.details)
        val textCompatSummary = findViewById<TextView>(R.id.compatSummary)
        val buttonClone = findViewById<Button>(R.id.cloneBtn)

        try {
            // Use fast path for list, deep for detail
            val (appInfo, report) = analyzer.analyzeInstalled(pkg)

            textAppName.text = appInfo.appName
            textPackage.text = appInfo.packageName
            textVersion.text = "v${appInfo.versionName} (${appInfo.versionCode}) – Target SDK ${appInfo.targetSdk}"

            val detailsMap = analyzer.getDetailedInfo(appInfo)
            textDetails.text = detailsMap.entries.joinToString("\n") { "${it.key}: ${it.value}" }

            textCompatSummary.text = report.summary + "\n\nChecks:\n" + report.checks.joinToString("\n") {
                "${it.status}: ${it.name} – ${it.description}"
            }

            buttonClone.setOnClickListener {
                val intent = android.content.Intent(this, CloneConfigActivity::class.java).apply {
                    putExtra("originalPackage", appInfo.packageName)
                    putExtra("package", appInfo.packageName)
                    putExtra("clonePackage", "${appInfo.packageName}.clone1")
                }
                startActivity(intent)
            }

        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "AppAnalyzerActivity failed for $pkg: ${ignored.message}", ignored)
            textAppName.text = "Failed to analyze $pkg"
            textDetails.text = ignored.stackTraceToString()
        }
    }
}

class ViewInspectorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analyzer)
        findViewById<TextView>(R.id.appName).text = "View Inspector – Layout Inspector V2"
        findViewById<TextView>(R.id.details).text = "Inspect view hierarchy, search views, inspect properties – independent implementation"
    }
}

class LogcatViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analyzer)
        findViewById<TextView>(R.id.appName).text = "Logcat Viewer"

        val logText = findViewById<TextView>(R.id.details)
        try {
            val process = Runtime.getRuntime().exec("logcat -d -t 200 -s CloneMaster:V")
            val logs = process.inputStream.bufferedReader().readText()
            logText.text = logs.ifEmpty { "No CloneMaster logs found – check adb logcat -s CloneMaster:V" }
        } catch (ignored: Exception) {
            logText.text = "Failed to get logcat: ${ignored.message} – requires READ_LOGS permission on rooted device or use adb logcat"
        }
    }
}
