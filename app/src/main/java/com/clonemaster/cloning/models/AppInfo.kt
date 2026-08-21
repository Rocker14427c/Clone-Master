package com.clonemaster.cloning.models

data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val targetSdk: Int,
    val minSdk: Int,
    val compileSdk: Int? = null,
    val isSystemApp: Boolean = false,
    val isSplit: Boolean = false,
    val apkPath: String,
    val splitPaths: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    val receivers: List<String> = emptyList(),
    val providers: List<ProviderInfo> = emptyList(),
    val permissions: List<String> = emptyList(),
    val libraries: List<String> = emptyList(),
    val hasObb: Boolean = false,
    val obbPath: String? = null,
    val largeHeap: Boolean = false,
    val usesBiometric: Boolean = false,
    val usesFirebaseAuth: Boolean = false,
    val usesPlayServices: Boolean = false,
    val usesBilling: Boolean = false,
    val usesSafetyNet: Boolean = false,
    val category: String = "unknown",
    val iconUri: String? = null,
    val sizeBytes: Long = 0
)

data class ProviderInfo(
    val name: String,
    val authority: String
)

data class CompatibilityReport(
    val appInfo: AppInfo,
    val checks: List<CompatibilityCheck>,
    val overallStatus: CompatibilityStatus,
    val summary: String
)

data class CompatibilityCheck(
    val id: String,
    val name: String,
    val status: CompatibilityStatus,
    val description: String,
    val recommendation: String? = null
)

enum class CompatibilityStatus { OK, WARNING, BLOCKER, UNKNOWN }
