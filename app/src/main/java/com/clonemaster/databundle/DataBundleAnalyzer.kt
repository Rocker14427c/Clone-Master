package com.clonemaster.databundle

import android.content.Context
import android.content.pm.PackageManager
import com.clonemaster.cloning.models.DataCategory
import java.io.File

/**
 * Build-time: Analyze source application's accessible data
 * QA Fixes:
 * - Fix repeated walkTopDown() traversal – now does single walk per dataDir and caches results
 * - Avoids OOM by not loading entire file list into memory multiple times
 * - Handles permission issues gracefully without crashing
 * - Validates paths to prevent path traversal
 * - Logs safely without sensitive data
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
        val appInfo = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            warnings.add("App not found: $packageName")
            android.util.Log.w("CloneMaster", "App not found: $packageName", e)
            return AnalysisResult(packageName, emptyMap(), 0, warnings)
        } catch (e: SecurityException) {
            warnings.add("Permission denied to access $packageName: ${e.message} – QUERY_ALL_PACKAGES may be needed")
            android.util.Log.w("CloneMaster", "Security exception for $packageName", e)
            return AnalysisResult(packageName, emptyMap(), 0, warnings)
        } catch (e: Exception) {
            warnings.add("App not accessible: ${e.message}")
            android.util.Log.w("CloneMaster", "App not accessible: $packageName", e)
            return AnalysisResult(packageName, emptyMap(), 0, warnings)
        }

        val dataDir = File(appInfo.dataDir)

        // QA Fix: Single walkTopDown for entire dataDir, cache results, instead of 7 separate walks
        // This reduces I/O and prevents ANR on large data dirs
        val allFilesCache: List<File> = try {
            if (dataDir.exists() && dataDir.canRead()) {
                dataDir.walkTopDown().filter { it.isFile }.toList()
            } else {
                warnings.add("Data dir not readable without root – only external dirs and accessible files can be bundled")
                emptyList()
            }
        } catch (e: Exception) {
            warnings.add("Failed to walk data dir: ${e.message}")
            android.util.Log.w("CloneMaster", "walkTopDown failed for ${dataDir.absolutePath}", e)
            emptyList()
        }

        val totalSize = allFilesCache.sumOf { it.length() }

        // Helper to get files for a subdir from cache, without re-walking
        fun getFilesForSubdir(subDirName: String): List<File> {
            return allFilesCache.filter { it.absolutePath.contains("/$subDirName/") || it.parentFile?.name == subDirName }
        }

        // SharedPreferences
        val prefsDir = File(dataDir, "shared_prefs")
        val prefsFiles = getFilesForSubdir("shared_prefs")
        categories[DataCategory.SHARED_PREFS] = CategoryInfo(
            DataCategory.SHARED_PREFS,
            prefsDir.absolutePath,
            fileCount = prefsFiles.size,
            sizeBytes = prefsFiles.sumOf { it.length() },
            accessible = prefsDir.exists() && prefsDir.canRead(),
            description = "SharedPreferences XML – preferences, settings, non-Keystore session tokens",
            examples = prefsFiles.map { it.name }.take(5)
        )

        // Databases
        val dbDir = File(dataDir, "databases")
        val dbFiles = getFilesForSubdir("databases")
        categories[DataCategory.DATABASES] = CategoryInfo(
            DataCategory.DATABASES,
            dbDir.absolutePath,
            fileCount = dbFiles.count { it.extension == "db" || it.extension == "sqlite" },
            sizeBytes = dbFiles.sumOf { it.length() },
            accessible = dbDir.exists(),
            description = "SQLite databases – may include Room DBs, app data, offline content",
            examples = dbFiles.map { it.name }.take(5)
        )

        // Room databases – subset
        val roomFiles = dbFiles.filter { it.name.contains("room", true) || it.name.endsWith(".db") }
        categories[DataCategory.ROOM_DATABASES] = CategoryInfo(
            DataCategory.ROOM_DATABASES,
            dbDir.absolutePath,
            fileCount = roomFiles.size,
            sizeBytes = roomFiles.sumOf { it.length() },
            accessible = dbDir.exists(),
            description = "Room databases – detected by naming",
            examples = roomFiles.map { it.name }.take(3)
        )

        // Files
        val filesDir = File(dataDir, "files")
        val filesFiles = getFilesForSubdir("files")
        categories[DataCategory.FILES] = CategoryInfo(
            DataCategory.FILES,
            filesDir.absolutePath,
            fileCount = filesFiles.size,
            sizeBytes = filesFiles.sumOf { it.length() },
            accessible = filesDir.exists(),
            description = "Application files – persistent files, game progress, offline downloads",
            examples = filesFiles.map { it.name }.take(5)
        )

        // no_backup
        val noBackupDir = File(dataDir, "no_backup")
        val noBackupFiles = getFilesForSubdir("no_backup")
        categories[DataCategory.CACHE_INDEPENDENT] = CategoryInfo(
            DataCategory.CACHE_INDEPENDENT,
            noBackupDir.absolutePath,
            fileCount = noBackupFiles.size,
            sizeBytes = noBackupFiles.sumOf { it.length() },
            accessible = noBackupDir.exists(),
            description = "no_backup – persistent files not in auto-backup, often critical state",
            examples = noBackupFiles.map { it.name }.take(3)
        )

        // WebView
        val webViewDir = File(dataDir, "app_webview")
        val webViewFiles = getFilesForSubdir("app_webview")
        categories[DataCategory.WEBVIEW_DATA] = CategoryInfo(
            DataCategory.WEBVIEW_DATA,
            webViewDir.absolutePath,
            fileCount = webViewFiles.size,
            sizeBytes = webViewFiles.sumOf { it.length() },
            accessible = webViewDir.exists(),
            description = "WebView data/cookies – may contain session, localStorage, where accessible (encrypted cookies may not restore)",
            examples = webViewFiles.map { it.name }.take(3)
        )
        if (webViewFiles.any { it.name == "Cookies" }) {
            warnings.add("WebView Cookies often encrypted with device key – may not be restorable on different identity")
        }

        // External-storage app directories – separate walk, but limited
        val externalDirs = listOf(
            File("/sdcard/Android/data/$packageName"),
            File("/storage/emulated/0/Android/data/$packageName")
        ).filter { it.exists() }

        var externalSize = 0L
        var externalCount = 0
        var externalExamples = listOf<String>()

        // QA Fix: Only walk external dirs if they exist and are not too large, with size limit to prevent ANR
        externalDirs.forEach { dir ->
            try {
                val files = dir.walkTopDown().filter { it.isFile }.take(1000).toList() // limit to 1000 files for performance
                externalSize += files.sumOf { it.length() }
                externalCount += files.size
                if (externalExamples.isEmpty()) {
                    externalExamples = files.map { it.name }.take(5)
                }
            } catch (e: Exception) {
                warnings.add("Failed to walk external dir ${dir.absolutePath}: ${e.message}")
            }
        }

        categories[DataCategory.EXTERNAL_APP_DIRS] = CategoryInfo(
            DataCategory.EXTERNAL_APP_DIRS,
            externalDirs.firstOrNull()?.absolutePath ?: "/sdcard/Android/data/$packageName",
            fileCount = externalCount,
            sizeBytes = externalSize,
            accessible = externalDirs.isNotEmpty(),
            description = "External-storage app directories – offline downloads, media",
            examples = externalExamples
        )

        // OBB directories
        val obbDir = File("/sdcard/Android/obb/$packageName")
        var obbSize = 0L
        var obbCount = 0
        var obbExamples = listOf<String>()
        if (obbDir.exists()) {
            try {
                val files = obbDir.walkTopDown().filter { it.isFile }.take(100).toList()
                obbSize = files.sumOf { it.length() }
                obbCount = files.size
                obbExamples = files.map { it.name }.take(3)
            } catch (e: Exception) {
                warnings.add("Failed to walk OBB dir: ${e.message}")
            }
        }

        categories[DataCategory.OBB_DIRS] = CategoryInfo(
            DataCategory.OBB_DIRS,
            obbDir.absolutePath,
            fileCount = obbCount,
            sizeBytes = obbSize,
            accessible = obbDir.exists(),
            description = "OBB expansion files – game assets",
            examples = obbExamples
        )

        categories[DataCategory.CUSTOM_DIRS] = CategoryInfo(
            DataCategory.CUSTOM_DIRS,
            "user-defined",
            fileCount = 0,
            sizeBytes = 0,
            accessible = true,
            description = "Other explicitly selected directories",
            examples = emptyList()
        )

        warnings.add("Some auth data may be stored in Android Keystore, hardware-backed security, certificate-bound credentials, or server-side sessions and cannot be copied – login restoration not guaranteed")
        warnings.add("Never modify original app's data – all reads are read-only")

        return AnalysisResult(packageName, categories, totalSize, warnings)
    }

    fun getExportablePaths(packageName: String, selectedCategories: List<DataCategory>, customDirs: List<String>): List<File> {
        val analysis = analyze(packageName)
        val paths = mutableListOf<File>()

        // Use analysis.categories to get paths, but validate canonical to prevent path traversal
        selectedCategories.forEach { cat ->
            analysis.categories[cat]?.let { info ->
                try {
                    val file = File(info.path)
                    // Validate file is inside expected data dir or external dir – prevent arbitrary path
                    if (file.exists() && file.canonicalPath.contains(packageName) || info.category == DataCategory.CUSTOM_DIRS || info.category == DataCategory.EXTERNAL_APP_DIRS || info.category == DataCategory.OBB_DIRS) {
                        paths.add(file)
                    } else if (file.exists()) {
                        android.util.Log.w("CloneMaster", "Skipping exportable path not containing package: ${file.canonicalPath}")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CloneMaster", "Failed to get exportable path for $cat: ${e.message}")
                }
            }
        }

        customDirs.forEach { dirPath ->
            try {
                val f = File(dirPath)
                // Validate custom dir – prevent path traversal to sensitive locations
                val canonical = f.canonicalPath
                if (canonical.startsWith("/data/data/") || canonical.startsWith("/sdcard/") || canonical.startsWith("/storage/")) {
                    if (f.exists()) paths.add(f)
                } else {
                    android.util.Log.w("CloneMaster", "Skipping custom dir not in allowed roots: $canonical")
                }
            } catch (e: Exception) {
                android.util.Log.w("CloneMaster", "Failed to validate custom dir $dirPath: ${e.message}")
            }
        }

        return paths
    }
}
