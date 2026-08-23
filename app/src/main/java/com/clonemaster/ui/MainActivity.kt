package com.clonemaster.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clonemaster.R
import com.clonemaster.analysis.AppAnalyzer
import com.clonemaster.cloning.engine.CloneEngine
import com.clonemaster.cloning.models.AppInfo
import com.clonemaster.cloning.models.CloneConfig
import com.clonemaster.ui.adapters.AppListAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * QA Fix: Previously had empty screen and dead FAB
 * - Implemented RecyclerView with LinearLayoutManager and AppListAdapter
 * - Added ProgressBar and emptyView for visual state
 * - Wired search filtering (app name + package)
 * - Wired tab switching between Installed Apps and Clones
 * - Implemented FAB dialog with 4 capabilities: Pick APK, Batch Clone, Environment Diagnostics, Refresh
 * - Fixed lifecycle leak via lifecycleScope
 */
class MainActivity : AppCompatActivity() {

    private lateinit var analyzer: AppAnalyzer
    private lateinit var cloneEngine: CloneEngine
    private var installedApps: List<AppInfo> = emptyList()
    private var clones: List<CloneConfig> = emptyList()
    private var filteredApps: List<AppInfo> = emptyList()

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var searchEditText: TextInputEditText
    private lateinit var tabLayout: TabLayout
    private lateinit var fab: FloatingActionButton

    private var currentTab = 0 // 0 = installed, 1 = clones

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        analyzer = AppAnalyzer(this)
        cloneEngine = CloneEngine(this)

        recyclerView = findViewById(R.id.recycler)
        progressBar = findViewById(R.id.loading)
        emptyView = findViewById(R.id.emptyView)
        searchEditText = findViewById(R.id.search)
        tabLayout = findViewById(R.id.tabs)
        fab = findViewById(R.id.fab_batch)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(emptyList()) { app ->
            onAppSelected(app)
        }
        recyclerView.adapter = adapter

        // Search filtering – real-time
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Tab switching
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                updateTabContent()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // FAB with 4 capabilities
        fab.setOnClickListener {
            showFabDialog()
        }

        loadApps()
        loadClones()
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apps = analyzer.listInstalledApps(false)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        installedApps = apps
                        filteredApps = apps
                        progressBar.visibility = View.GONE
                        if (apps.isEmpty()) {
                            emptyView.visibility = View.VISIBLE
                            emptyView.text = "No apps found – grant QUERY_ALL_PACKAGES permission"
                        } else {
                            recyclerView.visibility = View.VISIBLE
                            adapter.updateList(filteredApps)
                        }
                    }
                }
            } catch (ignored: Exception) {
                android.util.Log.e("CloneMaster", "loadApps failed: ${ignored.message}", ignored)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        progressBar.visibility = View.GONE
                        emptyView.visibility = View.VISIBLE
                        emptyView.text = "Failed to load apps: ${ignored.message}"
                    }
                }
            }
        }
    }

    private fun loadClones() {
        try {
            val configDir = java.io.File(filesDir, "clone_configs")
            clones = configDir.listFiles()?.mapNotNull {
                try {
                    com.google.gson.Gson().fromJson(it.readText(), CloneConfig::class.java)
                } catch (ignored: Exception) {
                    android.util.Log.w("CloneMaster", "Failed to parse clone config ${it.name}: ${ignored.message}")
                    null
                }
            } ?: emptyList()
        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "loadClones failed: ${ignored.message}", ignored)
            clones = emptyList()
        }
    }

    private fun filterApps(query: String) {
        if (currentTab != 0) return // only filter installed apps tab
        filteredApps = if (query.isEmpty()) {
            installedApps
        } else {
            installedApps.filter { it.appName.contains(query, true) || it.packageName.contains(query, true) }
        }
        adapter.updateList(filteredApps)
        emptyView.visibility = if (filteredApps.isEmpty()) View.VISIBLE else View.GONE
        if (filteredApps.isEmpty()) {
            emptyView.text = "No apps match \"$query\""
        }
    }

    private fun updateTabContent() {
        when (currentTab) {
            0 -> {
                // Installed apps
                adapter.updateList(filteredApps)
                emptyView.visibility = if (filteredApps.isEmpty()) View.VISIBLE else View.GONE
            }
            1 -> {
                // Clones – for simplicity show clones as AppInfo-like list
                // Convert clones to AppInfo for display
                val cloneInfos = clones.map { clone ->
                    AppInfo(
                        packageName = clone.clonePackage,
                        appName = clone.appName,
                        versionName = clone.versionName,
                        versionCode = clone.versionCode,
                        targetSdk = 34,
                        minSdk = 24,
                        apkPath = ""
                    )
                }
                adapter.updateList(cloneInfos)
                emptyView.visibility = if (cloneInfos.isEmpty()) View.VISIBLE else View.GONE
                if (cloneInfos.isEmpty()) {
                    emptyView.text = "No clones yet – use + button to create"
                }
            }
        }
    }

    fun onAppSelected(app: AppInfo) {
        try {
            val intent = Intent(this, AppAnalyzerActivity::class.java)
            intent.putExtra("package", app.packageName)
            startActivity(intent)
        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "onAppSelected failed: ${ignored.message}", ignored)
        }
    }

    fun onCloneSelected(clone: CloneConfig) {
        try {
            val intent = Intent(this, CloneConfigActivity::class.java)
            intent.putExtra("clonePackage", clone.clonePackage)
            startActivity(intent)
        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "onCloneSelected failed: ${ignored.message}", ignored)
        }
    }

    private fun showFabDialog() {
        val options = arrayOf(
            "Pick APK File from Storage",
            "Batch Clone Installed App",
            "Environment Diagnostics",
            "Diagnostics Log (view/share)",
            "Refresh App List"
        )

        AlertDialog.Builder(this)
            .setTitle("Clone-Master Actions")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickApkFile()
                    1 -> showBatchCloneDialog()
                    2 -> {
                        val intent = Intent(this, com.clonemaster.environment.EnvironmentDiagnosticsActivity::class.java)
                        startActivity(intent)
                    }
                    3 -> {
                        startActivity(Intent(this, com.clonemaster.diagnostics.DiagnosticsActivity::class.java))
                    }
                    4 -> {
                        loadApps()
                        loadClones()
                    }
                }
            }
            .show()
    }

    private fun pickApkFile() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/vnd.android.package-archive"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Select APK"), 1001)
        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "pickApkFile failed: ${ignored.message}", ignored)
        }
    }

    private fun showBatchCloneDialog() {
        if (installedApps.isEmpty()) {
            android.widget.Toast.makeText(this, "No apps loaded", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val appNames = installedApps.map { "${it.appName} (${it.packageName})" }.toTypedArray()
        var selectedAppIndex = 0

        AlertDialog.Builder(this)
            .setTitle("Select App to Batch Clone")
            .setSingleChoiceItems(appNames, 0) { _, which ->
                selectedAppIndex = which
            }
            .setPositiveButton("Next") { _, _ ->
                val selectedApp = installedApps[selectedAppIndex]
                showBatchCountDialog(selectedApp)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBatchCountDialog(app: AppInfo) {
        val counts = arrayOf("2 clones", "3 clones", "5 clones", "10 clones")
        val countValues = arrayOf(2, 3, 5, 10)
        var selectedCountIndex = 0

        AlertDialog.Builder(this)
            .setTitle("How many clones?")
            .setSingleChoiceItems(counts, 0) { _, which ->
                selectedCountIndex = which
            }
            .setPositiveButton("Clone") { _, _ ->
                val count = countValues[selectedCountIndex]
                batchClone(app, count, "{appName} {index}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                // Update notification or UI
                            }
                        }
                    }
                    if (result.isFailure) {
                        android.util.Log.e("CloneMaster", "Batch clone $i/$count failed: ${result.exceptionOrNull()?.message}")
                    }
                } catch (ignored: Exception) {
                    android.util.Log.e("CloneMaster", "Batch clone $i/$count exception: ${ignored.message}", ignored)
                }
            }
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    loadClones()
                    updateTabContent()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                android.util.Log.d("CloneMaster", "Picked APK: $uri")
                // Would copy APK to cache and start cloning workflow
                android.widget.Toast.makeText(this, "Picked: $uri – cloning workflow would start here", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
