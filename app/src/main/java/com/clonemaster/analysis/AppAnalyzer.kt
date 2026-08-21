package com.clonemaster.analysis

import android.content.Context
import android.content.pm.PackageManager
import com.clonemaster.cloning.engine.ApkParser
import com.clonemaster.cloning.engine.CompatibilityAnalyzer
import com.clonemaster.cloning.models.AppInfo
import com.clonemaster.cloning.models.CompatibilityReport

class AppAnalyzer(private val context: Context) {

    private val parser = ApkParser(context)
    private val compatibilityAnalyzer = CompatibilityAnalyzer()

    fun analyzeInstalled(packageName: String): Pair<AppInfo, CompatibilityReport> {
        val appInfo = parser.parseInstalled(packageName)
        val report = compatibilityAnalyzer.analyze(appInfo)
        return appInfo to report
    }

    fun listInstalledApps(includeSystem: Boolean = false): List<AppInfo> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        return packages.filter { includeSystem || (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .mapNotNull {
                try {
                    parser.parseInstalled(it.packageName)
                } catch (_: Exception) { null }
            }.sortedBy { it.appName.lowercase() }
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
