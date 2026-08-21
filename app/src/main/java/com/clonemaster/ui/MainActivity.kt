package com.clonemaster.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.clonemaster.R
import com.clonemaster.analysis.AppAnalyzer
import com.clonemaster.cloning.engine.CloneEngine
import com.clonemaster.cloning.models.AppInfo
import com.clonemaster.cloning.models.CloneConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var analyzer: AppAnalyzer
    private lateinit var cloneEngine: CloneEngine
    private var installedApps: List<AppInfo> = emptyList()
    private var clones: List<CloneConfig> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        analyzer = AppAnalyzer(this)
        cloneEngine = CloneEngine(this)

        // Setup UI: tabs for Installed Apps, Clones, Settings
        // Search bar that searches across all cloning options ("GPS", "proxy", etc)
        // For brevity, minimal UI

        loadApps()
        loadClones()
    }

    private fun loadApps() {
        CoroutineScope(Dispatchers.IO).launch {
            val apps = analyzer.listInstalledApps(false)
            withContext(Dispatchers.Main) {
                installedApps = apps
                // update RecyclerView
            }
        }
    }

    private fun loadClones() {
        val dir = getExternalFilesDir("clones")
        val configDir = java.io.File(filesDir, "clone_configs")
        clones = configDir.listFiles()?.mapNotNull {
            try {
                com.google.gson.Gson().fromJson(it.readText(), CloneConfig::class.java)
            } catch (_: Exception) { null }
        } ?: emptyList()
    }

    fun onAppSelected(app: AppInfo) {
        // Open AppAnalyzerActivity + CloneConfigActivity
        val intent = android.content.Intent(this, AppAnalyzerActivity::class.java)
        intent.putExtra("package", app.packageName)
        startActivity(intent)
    }

    fun onCloneSelected(clone: CloneConfig) {
        val intent = android.content.Intent(this, CloneConfigActivity::class.java)
        intent.putExtra("clonePackage", clone.clonePackage)
        startActivity(intent)
    }

    // Batch cloning
    fun batchClone(app: AppInfo, count: Int, template: String) {
        CoroutineScope(Dispatchers.IO).launch {
            for (i in 1..count) {
                val config = CloneConfig(
                    originalPackage = app.packageName,
                    clonePackage = "${app.packageName}.clone$i",
                    cloneIndex = i,
                    appName = template.replace("{appName}", app.appName).replace("{index}", i.toString()),
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    isBatch = true,
                    batchCount = count,
                    batchNameTemplate = template
                )
                cloneEngine.clone(config) { progress ->
                    runOnUiThread { /* update progress */ }
                }
            }
        }
    }
}
