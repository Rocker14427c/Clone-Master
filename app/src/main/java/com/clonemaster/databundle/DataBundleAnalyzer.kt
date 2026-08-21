package com.clonemaster.databundle

import android.content.Context
import android.content.pm.PackageManager
import com.clonemaster.cloning.models.DataCategory
import java.io.File

/**
 * Build-time: Analyze source application's accessible data
 * Identifies exportable data where technically permitted
 */
class DataBundleAnalyzer(private val context: Context) {

    data class AnalysisResult(
        val packageName: String,
        val categories: Map<DataCategory, CategoryInfo>,
        val totalSize: Long,
        val warnings: List<String>
    )

    data class CategoryInfo(
        val category: DataCategory,
        val path: String,
        val fileCount: Int,
        val sizeBytes: Long,
        val accessible: Boolean,
        val description: String,
        val examples: List<String> = emptyList()
    )

    fun analyze(packageName: String): AnalysisResult {
        val warnings = mutableListOf<String>()
        val categories = mutableMapOf<DataCategory, CategoryInfo>()

        val pm = context.packageManager
        val appInfo = try { pm.getApplicationInfo(packageName, 0) } catch (e: Exception) {
            warnings.add("App not found or not accessible: ${e.message}")
            return AnalysisResult(packageName, emptyMap(), 0, warnings)
        }

        val dataDir = File(appInfo.dataDir)
        val totalSize = dataDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()

        // SharedPreferences
        val prefsDir = File(dataDir, "shared_prefs")
        categories[DataCategory.SHARED_PREFS] = CategoryInfo(
            DataCategory.SHARED_PREFS,
            prefsDir.absolutePath,
            fileCount = prefsDir.listFiles()?.size ?: 0,
            sizeBytes = prefsDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum(),
            accessible = prefsDir.exists() && prefsDir.canRead(),
            description = "SharedPreferences XML files – preferences, settings, session tokens (non-Keystore)",
            examples = prefsDir.listFiles()?.map { it.name }?.take(5) ?: emptyList()
        )

        // Databases (SQLite)
        val dbDir = File(dataDir, "databases")
        categories[DataCategory.DATABASES] = CategoryInfo(
            DataCategory.DATABASES,
            dbDir.absolutePath,
            fileCount = dbDir.listFiles()?.count { it.extension == "db" || it.extension == "sqlite" } ?: 0,
            sizeBytes = dbDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum(),
            accessible = dbDir.exists(),
            description = "SQLite databases – may include Room DBs, app data, offline content",
            examples = dbDir.listFiles()?.map { it.name }?.take(5) ?: emptyList()
        )

        // Room databases – subset of databases, detect via journal files or naming
        categories[DataCategory.ROOM_DATABASES] = CategoryInfo(
            DataCategory.ROOM_DATABASES,
            dbDir.absolutePath,
            fileCount = dbDir.listFiles()?.count { it.name.contains("room") || it.extension == "db" } ?: 0,
            sizeBytes = dbDir.walkTopDown().filter { it.isFile && it.name.contains("room") }.map { it.length() }.sum(),
            accessible = dbDir.exists(),
            description = "Room databases – detected by naming or associated files",
            examples = dbDir.listFiles()?.filter { it.name.contains("room") }?.map { it.name }?.take(3) ?: emptyList()
        )

        // Files
        val filesDir = File(dataDir, "files")
        categories[DataCategory.FILES] = CategoryInfo(
            DataCategory.FILES,
            filesDir.absolutePath,
            fileCount = filesDir.walkTopDown().filter { it.isFile }.count(),
            sizeBytes = filesDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum(),
            accessible = filesDir.exists(),
            description = "Application files – persistent files, game progress, offline downloads",
            examples = filesDir.listFiles()?.map { it.name }?.take(5) ?: emptyList()
        )

        // Cache-independent persistent files – no_backup, etc
        val noBackupDir = File(dataDir, "no_backup")
        categories[DataCategory.CACHE_INDEPENDENT] = CategoryInfo(
            DataCategory.CACHE_INDEPENDENT,
            noBackupDir.absolutePath,
            fileCount = noBackupDir.walkTopDown().filter { it.isFile }.count(),
            sizeBytes = noBackupDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum(),
            accessible = noBackupDir.exists(),
            description = "no_backup directory – persistent files not included in auto-backup, often contains critical state",
            examples = noBackupDir.listFiles()?.map { it.name }?.take(3) ?: emptyList()
        )

        // WebView data/cookies
        val webViewDir = File(dataDir, "app_webview")
        val webViewCookies = File(dataDir, "app_webview/Cookies")
        categories[DataCategory.WEBVIEW_DATA] = CategoryInfo(
            DataCategory.WEBVIEW_DATA,
            webViewDir.absolutePath,
            fileCount = webViewDir.walkTopDown().filter { it.isFile }.count(),
            sizeBytes = webViewDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum(),
            accessible = webViewDir.exists(),
            description = "WebView data/cookies – may contain session, localStorage, where technically accessible (encrypted cookies may not be restorable)",
            examples = listOf("Cookies", "Local Storage", "Session Storage").filter { File(webViewDir, it).exists() }
        )
        if (webViewCookies.exists()) {
            warnings.add("WebView Cookies are often encrypted with device key – may not be restorable on different identity")
        }

        // External-storage app directories
        val externalDirs = listOf(
            File("/sdcard/Android/data/$packageName"),
            File("/storage/emulated/0/Android/data/$packageName"),
            context.getExternalFilesDir(null)?.let { File(it.parentFile?.parentFile?.parentFile, packageName) } // best effort
        ).filterNotNull().filter { it.exists() }

        val externalSize = externalDirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() }
        val externalCount = externalDirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.count() }

        categories[DataCategory.EXTERNAL_APP_DIRS] = CategoryInfo(
            DataCategory.EXTERNAL_APP_DIRS,
            externalDirs.firstOrNull()?.absolutePath ?: "/sdcard/Android/data/$packageName",
            fileCount = externalCount,
            sizeBytes = externalSize,
            accessible = externalDirs.isNotEmpty(),
            description = "External-storage app directories – offline downloads, media, exports",
            examples = externalDirs.flatMap { it.listFiles()?.map { f -> f.name } ?: emptyList() }.take(5)
        )

        // OBB directories
        val obbDir = File("/sdcard/Android/obb/$packageName")
        categories[DataCategory.OBB_DIRS] = CategoryInfo(
            DataCategory.OBB_DIRS,
            obbDir.absolutePath,
            fileCount = obbDir.listFiles()?.size ?: 0,
            sizeBytes = obbDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum(),
            accessible = obbDir.exists(),
            description = "OBB expansion files – game assets",
            examples = obbDir.listFiles()?.map { it.name }?.take(3) ?: emptyList()
        )

        // Custom dirs – initially empty, user will add
        categories[DataCategory.CUSTOM_DIRS] = CategoryInfo(
            DataCategory.CUSTOM_DIRS,
            "user-defined",
            fileCount = 0,
            sizeBytes = 0,
            accessible = true,
            description = "Other explicitly selected directories",
            examples = emptyList()
        )

        // Warnings for Keystore / hardware-backed
        warnings.add("Some authentication data may be stored in Android Keystore, hardware-backed security, certificate-bound credentials, or server-side sessions and cannot be copied – login restoration not guaranteed")
        warnings.add("Never modify original app's data – all reads are read-only")
        if (!dataDir.canRead()) warnings.add("Data dir not readable without root – only external dirs and accessible files can be bundled")

        return AnalysisResult(packageName, categories, totalSize, warnings)
    }

    fun getExportablePaths(packageName: String, selectedCategories: List<DataCategory>, customDirs: List<String>): List<File> {
        val analysis = analyze(packageName)
        val paths = mutableListOf<File>()
        selectedCategories.forEach { cat ->
            analysis.categories[cat]?.let { info ->
                val file = File(info.path)
                if (file.exists()) paths.add(file)
            }
        }
        customDirs.forEach { dirPath ->
            val f = File(dirPath)
            if (f.exists()) paths.add(f)
        }
        return paths
    }
}
