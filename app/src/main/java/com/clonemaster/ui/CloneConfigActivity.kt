package com.clonemaster.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.clonemaster.cloning.models.CloneConfig

class CloneConfigActivity : AppCompatActivity() {

    private lateinit var config: CloneConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.clonemaster.R.layout.activity_clone_config)

        val clonePackage = intent.getStringExtra("clonePackage")
        config = if (clonePackage != null) {
            loadConfig(clonePackage)
        } else {
            CloneConfig()
        }

        // Setup categorized options with search
        // Categories: Core, Identity, Privacy, Display, ViewMod, Media, Navigation, Storage, Launching, Networking, Notification, Game, TV/Wear, Automation, Developer, WebView, AI
        // Each category is a fragment
        // Feature search: EditText that filters all options by keyword (e.g., "GPS", "proxy", "clipboard", "dark mode", "WebView", "notification")
    }

    private fun loadConfig(pkg: String): CloneConfig {
        val file = java.io.File(filesDir, "clone_configs/$pkg.json")
        return try {
            com.google.gson.Gson().fromJson(file.readText(), CloneConfig::class.java)
        } catch (_: Exception) {
            CloneConfig(clonePackage = pkg)
        }
    }

    fun saveConfig() {
        val dir = java.io.File(filesDir, "clone_configs").apply { mkdirs() }
        val file = java.io.File(dir, "${config.clonePackage}.json")
        file.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config))
    }

    fun exportConfig() {
        // Export to external storage
        val exportDir = getExternalFilesDir("exports")
        exportDir?.mkdirs()
        val file = java.io.File(exportDir, "${config.clonePackage}_config.json")
        file.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config))
    }

    fun importConfig(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            config = com.google.gson.Gson().fromJson(text, CloneConfig::class.java)
        } catch (_: Exception) {}
    }
}

class AppAnalyzerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.clonemaster.R.layout.activity_analyzer)

        val pkg = intent.getStringExtra("package") ?: return
        val analyzer = com.clonemaster.analysis.AppAnalyzer(this)

        // Show AppInfo details + CompatibilityReport
        // Package, version, SDKs, activities, services, receivers, providers, permissions, libs, category, largeHeap, biometric, Firebase, warnings
    }
}

class ViewInspectorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show view hierarchy, search, modify
    }
}

class LogcatViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show logcat
    }
}
