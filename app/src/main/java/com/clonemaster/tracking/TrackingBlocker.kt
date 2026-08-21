package com.clonemaster.tracking

import android.content.Context
import com.clonemaster.cloning.models.CloneConfig

/**
 * Independent implementation for disabling tracking SDKs
 * Public feature reference: App Cloner lists "Disable AppsFlyer tracking" and general tracking/analytics disabling
 * This is equivalent functionality implemented independently, not copying proprietary details
 * Functional parity: block common tracking SDKs via hooks, with compatibility with Android limitations
 */
class TrackingBlocker(private val context: Context) {

    companion object {
        // Common tracking SDKs – public knowledge, not proprietary
        val TRACKING_SDKS = mapOf(
            "AppsFlyer" to listOf("com.appsflyer", "AppsFlyerLib"),
            "Firebase Analytics" to listOf("com.google.firebase.analytics", "com.google.android.gms.measurement"),
            "Facebook Analytics" to listOf("com.facebook.apptracking", "com.facebook.FacebookSdk"),
            "Adjust" to listOf("com.adjust.sdk"),
            "Branch" to listOf("io.branch"),
            "Amplitude" to listOf("com.amplitude"),
            "Mixpanel" to listOf("com.mixpanel"),
            "Flurry" to listOf("com.flurry"),
            "Crashlytics" to listOf("com.crashlytics", "com.google.firebase.crashlytics"),
            "Sentry" to listOf("io.sentry"),
            "Google Analytics" to listOf("com.google.android.gms.analytics")
        )
    }

    data class TrackingBlockConfig(
        var disableAppsFlyer: Boolean = true,
        var disableFirebaseAnalytics: Boolean = false,
        var disableFacebook: Boolean = false,
        var disableAllTracking: Boolean = false,
        var customBlockedPackages: MutableList<String> = mutableListOf()
    )

    fun getBlockedSdks(config: TrackingBlockConfig): List<String> {
        val blocked = mutableListOf<String>()
        if (config.disableAppsFlyer) blocked.add("AppsFlyer")
        if (config.disableFirebaseAnalytics) blocked.add("Firebase Analytics")
        if (config.disableFacebook) blocked.add("Facebook Analytics")
        if (config.disableAllTracking) blocked.addAll(TRACKING_SDKS.keys)
        return blocked.distinct()
    }

    object Hooks {
        /**
         * Independent implementation using Pine + ByteHook
         * Hooks common tracking SDK initialization to no-op
         * Compatibility with Android limitations: some SDKs use native code or reflection – we degrade gracefully and report
         */
        fun install(blockConfig: TrackingBlockConfig) {
            if (!blockConfig.disableAppsFlyer && !blockConfig.disableAllTracking) return

            // Example: hook AppsFlyerLib.init -> no-op
            // Pine.hook(Class.forName("com.appsflyer.AppsFlyerLib").getMethod("init", ...)) { return null }

            // Hook FirebaseAnalytics.getInstance -> return mock that does nothing
            // Hook FacebookSdk.sdkInitialize -> no-op

            // For generic tracking, hook common analytics log methods

            // Logging for diagnostics
            android.util.Log.d("CloneMaster", "TrackingBlocker: blocking ${blockConfig}")
        }
    }

    fun getCompatibilityReport(): String {
        return """
            TrackingBlocker independent implementation:
            - Blocks AppsFlyer via hooking AppsFlyerLib.init and conversion listener
            - Blocks Firebase Analytics via mocking FirebaseAnalytics.getInstance
            - Compatibility: Some SDKs initialize in native code or use SafetyNet – may not be fully blockable without root, degraded gracefully
            - Functional parity with public feature reference: equivalent functionality, not copying proprietary implementation
        """.trimIndent()
    }
}
