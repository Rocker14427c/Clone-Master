package com.clonemaster.cloning.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.clonemaster.cloning.models.AppInfo
import com.clonemaster.cloning.models.ProviderInfo
import java.io.File

/**
 * APK Parser – extracts AppInfo from installed app or apk file.
 * QA Hardened:
 * - Handles Android 13+ PackageManager API changes (GET_PERMISSIONS deprecated, use PackageInfoFlags)
 * - Avoids OOM by not reading 10MB into String for each heuristic – uses streaming search with buffer and early exit
 * - Handles missing permissions gracefully (QUERY_ALL_PACKAGES may be denied)
 * - Validates APK path exists before reading
 * - Compatible with Android 10+ scoped storage for OBB detection
 */
class ApkParser(private val context: Context) {

    fun parseInstalled(packageName: String): AppInfo {
        val pm = context.packageManager

        // Android 13+ compatibility: use PackageManager.PackageInfoFlags
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS).toLong()
                ))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("App not found: $packageName", e)
        } catch (e: SecurityException) {
            // QUERY_ALL_PACKAGES may be denied – try without permissions
            android.util.Log.w("CloneMaster", "PackageManager security exception for $packageName: ${e.message}, trying minimal info")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        }

        val appInfo = pkgInfo.applicationInfo ?: throw IllegalArgumentException("App not found: $packageName has no ApplicationInfo")
        val apkPath = appInfo.sourceDir
        val splitPaths = appInfo.splitSourceDirs?.toList() ?: emptyList()

        val activities = pkgInfo.activities?.map { it.name } ?: emptyList()
        val services = pkgInfo.services?.map { it.name } ?: emptyList()
        val receivers = pkgInfo.receivers?.map { it.name } ?: emptyList()
        val providers = pkgInfo.providers?.map { ProviderInfo(it.name, it.authority ?: "") } ?: emptyList()
        val permissions = pkgInfo.requestedPermissions?.toList() ?: emptyList()

        // Heuristics for libraries / features – QA: use efficient streaming search, not loading 10MB String each time
        val usesPlayServices = permissions.any { it.contains("gms") } || checkDexForStringEfficient(apkPath, "com.google.android.gms")
        val usesBilling = checkDexForStringEfficient(apkPath, "com.android.billingclient") || checkDexForStringEfficient(apkPath, "com.android.vending.BILLING")
        val usesFirebaseAuth = checkDexForStringEfficient(apkPath, "com.google.firebase.auth")
        val usesSafetyNet = checkDexForStringEfficient(apkPath, "com.google.android.gms.safetynet") || checkDexForStringEfficient(apkPath, "PlayIntegrity")
        val usesBiometric = checkDexForStringEfficient(apkPath, "androidx.biometric") || permissions.contains("android.permission.USE_BIOMETRIC")

        // OBB detection – handle scoped storage, check multiple possible paths
        val hasObb = checkObbExists(packageName)

        return AppInfo(
            packageName = packageName,
            appName = try { pm.getApplicationLabel(appInfo).toString() } catch (e: Exception) {
                android.util.Log.w("CloneMaster", "Failed to get app label for $packageName: ${e.message}")
                packageName
            },
            versionName = pkgInfo.versionName ?: "1.0",
            versionCode = try { pkgInfo.longVersionCode } catch (e: Exception) {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            },
            targetSdk = appInfo.targetSdkVersion,
            minSdk = try { appInfo.minSdkVersion } catch (ignored: Exception) { 21 },
            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            isSplit = splitPaths.isNotEmpty(),
            apkPath = apkPath,
            splitPaths = splitPaths,
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            permissions = permissions,
            libraries = detectLibraries(apkPath),
            hasObb = hasObb,
            largeHeap = (appInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0,
            usesBiometric = usesBiometric,
            usesFirebaseAuth = usesFirebaseAuth,
            usesPlayServices = usesPlayServices,
            usesBilling = usesBilling,
            usesSafetyNet = usesSafetyNet,
            sizeBytes = try { File(apkPath).length() } catch (ignored: Exception) { 0L }
        )
    }

    fun parseApkFile(apkFile: File): AppInfo {
        if (!apkFile.exists()) throw IllegalArgumentException("APK file does not exist: ${apkFile.absolutePath}")
        if (apkFile.length() == 0L) throw IllegalArgumentException("APK file is 0 bytes: ${apkFile.absolutePath}")

        // Real APK-file parsing with net.dongliu:apk-parser (JVM lib, works on Android).
        return try {
            net.dongliu.apk.parser.ApkFile(apkFile).use { apk ->
                val meta = apk.apkMeta
                AppInfo(
                    packageName = meta.packageName ?: "unknown",
                    appName = try { meta.label?.toString() ?: apkFile.nameWithoutExtension } catch (ignored: Exception) { apkFile.nameWithoutExtension },
                    versionName = meta.versionName ?: "1.0",
                    versionCode = (meta.versionCode ?: 1L),
                    targetSdk = meta.targetSdkVersion?.toIntOrNull() ?: 30,
                    minSdk = meta.minSdkVersion?.toIntOrNull() ?: 21,
                    apkPath = apkFile.absolutePath,
                    sizeBytes = apkFile.length()
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "parseApkFile failed for ${apkFile.name}: ${e.message}")
            AppInfo(
                packageName = "unknown",
                appName = apkFile.nameWithoutExtension,
                versionName = "1.0",
                versionCode = 1,
                targetSdk = 30,
                minSdk = 21,
                apkPath = apkFile.absolutePath,
                sizeBytes = apkFile.length()
            )
        }
    }

    /**
     * Efficient string search in APK without loading entire file into memory as String
     * QA Fix: Previously read 10MB and converted to String for each check – caused OOM and excessive allocations
     * Now: streams file with 8KB buffer, searches for needle, early exit on found, limits to first 5MB
     */
    private fun checkDexForStringEfficient(apkPath: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        val file = File(apkPath)
        if (!file.exists() || file.length() == 0L) return false

        return try {
            file.inputStream().use { fis ->
                val needleBytes = needle.toByteArray()
                val buffer = ByteArray(8192)
                var totalRead = 0L
                val maxRead = 5L * 1024 * 1024 // limit to 5MB for performance
                var bytesRead: Int
                var overlap = ByteArray(0)

                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    totalRead += bytesRead
                    if (totalRead > maxRead) break

                    // Combine overlap + buffer for search across chunk boundaries
                    val searchBuffer = overlap + buffer.copyOf(bytesRead)

                    // Simple byte search
                    if (searchBuffer.indexOf(needleBytes) != -1) {
                        return true
                    }

                    // Keep last (needle length -1) bytes as overlap for next iteration
                    overlap = if (searchBuffer.size >= needleBytes.size) {
                        searchBuffer.copyOfRange(searchBuffer.size - needleBytes.size + 1, searchBuffer.size)
                    } else searchBuffer
                }
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "checkDexForStringEfficient failed for $needle: ${e.message}")
            false
        }
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty() || this.size < needle.size) return -1
        outer@ for (i in 0..this.size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun checkObbExists(packageName: String): Boolean {
        return try {
            val possiblePaths = listOf(
                File("/sdcard/Android/obb/$packageName"),
                File("/storage/emulated/0/Android/obb/$packageName"),
                File(context.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile?.absolutePath ?: "", "obb/$packageName")
            )
            possiblePaths.any { it.exists() && it.listFiles()?.isNotEmpty() == true }
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "OBB check failed for $packageName: ${e.message}")
            false
        }
    }

    private fun detectLibraries(apkPath: String): List<String> {
        val libs = mutableListOf<String>()
        try {
            val file = File(apkPath)
            if (!file.exists() || file.length() == 0L) return emptyList()
            java.util.zip.ZipFile(file).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                        libs.add(entry.name)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "detectLibraries failed for $apkPath: ${e.message}")
        }
        return libs.distinct()
    }
}
