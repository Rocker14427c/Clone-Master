package com.clonemaster.analysis

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.clonemaster.cloning.engine.ApkParser
import com.clonemaster.cloning.engine.CompatibilityAnalyzer
import com.clonemaster.cloning.models.AppInfo
import com.clonemaster.cloning.models.CompatibilityReport
import com.clonemaster.cloning.models.ProviderInfo

/**
 * QA Fix: Previously listInstalledApps() called parser.parseInstalled() for every installed app,
 * which performed 5 sequential 5MB streaming searches per APK → gigabytes of I/O for 100+ apps, high latency.
 * Now: Fast path fetches metadata directly from PackageManager (~50ms), deep dex parsing deferred to detail screen.
 */
class AppAnalyzer(private val context: Context) {

    private val parser = ApkParser(context)
    private val compatibilityAnalyzer = CompatibilityAnalyzer()

    fun analyzeInstalled(packageName: String): Pair<AppInfo, CompatibilityReport> {
        // Deep analysis – only for single app detail screen
        val appInfo = parser.parseInstalled(packageName)
        val report = compatibilityAnalyzer.analyze(appInfo)
        return appInfo to report
    }

    fun listInstalledApps(includeSystem: Boolean = false): List<AppInfo> {
        val pm = context.packageManager
        return try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }

            packages.filter { includeSystem || (it.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) ?: 0) == 0 }
                .mapNotNull { pkgInfo ->
                    try {
                        val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                        // Fast path – no deep dex parsing, just metadata from PackageManager
                        AppInfo(
                            packageName = pkgInfo.packageName,
                            appName = try { pm.getApplicationLabel(appInfo).toString() } catch (ignored: Exception) { pkgInfo.packageName },
                            versionName = pkgInfo.versionName ?: "1.0",
                            versionCode = try { pkgInfo.longVersionCode } catch (ignored: Exception) {
                                @Suppress("DEPRECATION")
                                pkgInfo.versionCode.toLong()
                            },
                            targetSdk = appInfo.targetSdkVersion,
                            minSdk = try { appInfo.minSdkVersion } catch (ignored: Exception) { 21 },
                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            isSplit = appInfo.splitSourceDirs?.isNotEmpty() == true,
                            apkPath = appInfo.sourceDir ?: "",
                            splitPaths = appInfo.splitSourceDirs?.toList() ?: emptyList(),
                            activities = emptyList(), // Deferred to detail screen
                            services = emptyList(),
                            receivers = emptyList(),
                            providers = emptyList(),
                            permissions = pkgInfo.requestedPermissions?.toList() ?: emptyList(),
                            libraries = emptyList(),
                            hasObb = false, // Deferred
                            largeHeap = (appInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0,
                            usesBiometric = false, // Deferred to detail
                            usesFirebaseAuth = false,
                            usesPlayServices = false,
                            usesBilling = false,
                            usesSafetyNet = false,
                            sizeBytes = try { java.io.File(appInfo.sourceDir).length() } catch (ignored: Exception) { 0L }
                        )
                    } catch (ignored: Exception) {
                        null
                    }
                }.sortedBy { it.appName.lowercase() }

        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "listInstalledApps failed: ${ignored.message}", ignored)
            emptyList()
        }
    }

    fun getDetailedInfo(appInfo: AppInfo): Map<String, String> {
        return mapOf(
            "Package" to appInfo.packageName,
            "App Name" to appInfo.appName,
            "Version Name" to appInfo.versionName,
            "Version Code" to appInfo.versionCode.toString(),
            "Target SDK" to appInfo.targetSdk.toString(),
            "Min SDK" to appInfo.minSdk.toString(),
            "Size" to "${appInfo.sizeBytes / 1024 / 1024} MB",
            "Activities" to appInfo.activities.size.toString(),
            "Services" to appInfo.services.size.toString(),
            "Receivers" to appInfo.receivers.size.toString(),
            "Providers" to appInfo.providers.size.toString(),
            "Permissions" to appInfo.permissions.size.toString(),
            "Large Heap" to appInfo.largeHeap.toString(),
            "Biometric" to appInfo.usesBiometric.toString(),
            "Firebase Auth" to appInfo.usesFirebaseAuth.toString(),
            "Play Services" to appInfo.usesPlayServices.toString(),
            "Billing" to appInfo.usesBilling.toString(),
            "SafetyNet" to appInfo.usesSafetyNet.toString(),
            "OBB" to appInfo.hasObb.toString(),
            "Split APK" to appInfo.isSplit.toString()
        )
    }
}
