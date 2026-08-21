package com.clonemaster.cloning.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.clonemaster.cloning.models.AppInfo
import com.clonemaster.cloning.models.ProviderInfo
import java.io.File

/**
 * APK Parser – extracts AppInfo from installed app or apk file.
 * Uses PackageManager + optional apktool binary XML parsing for deep inspection.
 */
class ApkParser(private val context: Context) {

    fun parseInstalled(packageName: String): AppInfo {
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
        val appInfo = pkgInfo.applicationInfo ?: throw IllegalArgumentException("App not found")
        val apkPath = appInfo.sourceDir
        val splitPaths = appInfo.splitSourceDirs?.toList() ?: emptyList()

        val activities = pkgInfo.activities?.map { it.name } ?: emptyList()
        val services = pkgInfo.services?.map { it.name } ?: emptyList()
        val receivers = pkgInfo.receivers?.map { it.name } ?: emptyList()
        val providers = pkgInfo.providers?.map { ProviderInfo(it.name, it.authority ?: "") } ?: emptyList()
        val permissions = pkgInfo.requestedPermissions?.toList() ?: emptyList()

        // Heuristics for libraries / features
        val usesPlayServices = permissions.any { it.contains("gms") } || checkDexForString(apkPath, "com.google.android.gms")
        val usesBilling = checkDexForString(apkPath, "com.android.billingclient") || checkDexForString(apkPath, "com.android.vending.BILLING")
        val usesFirebaseAuth = checkDexForString(apkPath, "com.google.firebase.auth")
        val usesSafetyNet = checkDexForString(apkPath, "com.google.android.gms.safetynet") || checkDexForString(apkPath, "PlayIntegrity")
        val usesBiometric = checkDexForString(apkPath, "androidx.biometric") || permissions.contains("android.permission.USE_BIOMETRIC")

        val hasObb = File("/sdcard/Android/obb/$packageName").exists() || File("/storage/emulated/0/Android/obb/$packageName").exists()

        return AppInfo(
            packageName = packageName,
            appName = pm.getApplicationLabel(appInfo).toString(),
            versionName = pkgInfo.versionName ?: "1.0",
            versionCode = pkgInfo.longVersionCode,
            targetSdk = appInfo.targetSdkVersion,
            minSdk = appInfo.minSdkVersion,
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
            sizeBytes = File(apkPath).length()
        )
    }

    fun parseApkFile(apkFile: File): AppInfo {
        // For file-based parsing without install, use apk-parser lib (net.dongliu)
        // Fallback to minimal info
        return AppInfo(
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

    private fun checkDexForString(apkPath: String, needle: String): Boolean {
        return try {
            // naive check: unzip classes.dex and search? For performance, just check file contains string via binary search
            val file = File(apkPath)
            if (!file.exists()) return false
            // limit to first 10MB
            val bytes = file.inputStream().use { it.readNBytes(10 * 1024 * 1024) }
            String(bytes).contains(needle)
        } catch (e: Exception) { false }
    }

    private fun detectLibraries(apkPath: String): List<String> {
        val libs = mutableListOf<String>()
        try {
            java.util.zip.ZipFile(apkPath).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                        libs.add(entry.name)
                    }
                }
            }
        } catch (_: Exception) {}
        return libs.distinct()
    }
}
