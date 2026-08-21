package com.clonemaster.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clonemaster.R
import com.clonemaster.analysis.AppAnalyzer
import com.clonemaster.cloning.engine.CloneEngine
import com.clonemaster.cloning.models.AppInfo
import com.clonemaster.cloning.models.CloneConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * QA Fix: Previously used CoroutineScope(Dispatchers.IO) which is not lifecycle-aware and leaks if activity destroyed.
 * Now uses lifecycleScope (requires androidx.lifecycle:lifecycle-runtime-ktx) which cancels on destroy.
 * Also handles errors gracefully instead of swallowing.
 */
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

        loadApps()
        loadClones()
    }

    private fun loadApps() {
        // QA Fix: Use lifecycleScope instead of CoroutineScope(Dispatchers.IO) to avoid leak
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apps = analyzer.listInstalledApps(false)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        installedApps = apps
                        // update RecyclerView safely
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "loadApps failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        // Show error to user
                    }
                }
            }
        }
    }

    private fun loadClones() {
        try {
            val dir = getExternalFilesDir("clones")
            val configDir = java.io.File(filesDir, "clone_configs")
            clones = configDir.listFiles()?.mapNotNull {
                try {
                    com.google.gson.Gson().fromJson(it.readText(), CloneConfig::class.java)
                } catch (e: Exception) {
                    android.util.Log.w("CloneMaster", "Failed to parse clone config ${it.name}: ${e.message}")
                    null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("CloneMaster", "loadClones failed: ${e.message}", e)
            clones = emptyList()
        }
    }

    fun onAppSelected(app: AppInfo) {
        try {
            val intent = android.content.Intent(this, AppAnalyzerActivity::class.java)
            intent.putExtra("package", app.packageName)
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("CloneMaster", "onAppSelected failed: ${e.message}", e)
        }
    }

    fun onCloneSelected(clone: CloneConfig) {
        try {
            val intent = android.content.Intent(this, CloneConfigActivity::class.java)
            intent.putExtra("clonePackage", clone.clonePackage)
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("CloneMaster", "onCloneSelected failed: ${e.message}", e)
        }
    }

    // Batch cloning – QA Fix: Use lifecycleScope, handle errors, check isFinishing
    fun batchClone(app: AppInfo, count: Int, template: String) {
        if (count <= 0 || count > 100) {
            android.util.Log.w("CloneMaster", "Invalid batch count: $count – must be 1-100")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            for (i in 1..count) {
                if (isFinishing || isDestroyed) {
                    android.util.Log.w("CloneMaster", "Activity destroyed, aborting batch clone at $i/$count")
                    break
                }
                try {
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
                    val result = cloneEngine.clone(config) { progress ->
                        // Ensure UI updates only if activity alive
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                // update progress
                            }
                        }
                    }
                    if (result.isFailure) {
                        android.util.Log.e("CloneMaster", "Batch clone $i/$count failed: ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CloneMaster", "Batch clone $i/$count exception: ${e.message}", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // lifecycleScope automatically cancels – no leak
    }
}
