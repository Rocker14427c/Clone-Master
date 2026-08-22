package com.clonemaster.ui

import com.clonemaster.cloning.models.*

/**
 * Configuration presets – independent implementation
 * Public functional/UI reference: App Cloner has privacy presets, etc. – implemented independently with own logic
 * Presets only enable functionality that actually exists, user can modify after selecting
 */
enum class PresetType(val displayName: String, val description: String) {
    DEFAULT("Default", "Balanced clone with minimal changes – package rename + branding removal"),
    PRIVACY("Privacy", "Enable stealth, exclude recents, hide root, spoof Android ID, GPS spoof"),
    MAXIMUM_PRIVACY("Maximum Privacy", "All privacy features: incognito, clear on exit, disable clipboard/sensors/screenshots, hide other apps"),
    PERFORMANCE("Performance", "Keep screen awake, large heap, disable animations, disable background services"),
    COMPATIBILITY("Compatibility", "Minimal changes for apps that validate certificate – only package rename, no identity spoofing"),
    CLEAN_CLONE("Clean Clone", "Only rename, no branding, no extra hooks – minimal"),
    CUSTOM("Custom", "User custom configuration")
}

object PresetManager {

    fun applyPreset(config: CloneConfig, preset: PresetType): CloneConfig {
        // Return new config with preset applied – only enables existing functionality
        return when (preset) {
            PresetType.DEFAULT -> config.copy(
                // Default = minimal changes (package rename + branding removal). No optional features.
                removeBranding = true
            )

            PresetType.PRIVACY -> config.copy(
                removeBranding = true,
                privacy = config.privacy.copy(
                    stealthMode = true,
                    excludeFromRecents = true,
                    hideRoot = true,
                    hideOtherApps = true,
                    gpsSpoof = true,
                    hideMockLocation = true,
                    disableClipboard = true
                ),
                identity = config.identity.copy(spoofAndroidId = true),
                environment = config.environment.copy(hideRoot = true, hideEmulator = true, hideDeveloperOptions = true, hideMockLocation = true)
            )

            PresetType.MAXIMUM_PRIVACY -> config.copy(
                removeBranding = true,
                privacy = config.privacy.copy(
                    stealthMode = true,
                    excludeFromRecents = true,
                    incognitoMode = true,
                    incognitoKeyboard = true,
                    clearOnExit = true,
                    disableAccounts = true,
                    disableContacts = true,
                    disableCalendar = true,
                    disableCallLog = true,
                    disableClipboard = true,
                    disableSensors = true,
                    disableScreenshots = true,
                    disableScreenRecord = true,
                    hideRoot = true,
                    hideOtherApps = true,
                    disableLogcat = true,
                    disableShare = true,
                    gpsSpoof = true,
                    hideMockLocation = true,
                    fakeSensors = true
                ),
                identity = config.identity.copy(spoofAndroidId = true, spoofGsfId = true, spoofAdvertisingId = true, spoofWifiMac = true, spoofBtMac = true),
                environment = config.environment.copy(hideRoot = true, hideEmulator = true, hideDeveloperOptions = true, hideUsbAdb = true, hideMockLocation = true, spoofPhysicalDeviceProfile = true),
                storage = config.storage.copy(clearCacheOnExit = true, isolateStorage = true),
                parityFeatures = config.parityFeatures.copy(trackingBlocker = config.parityFeatures.trackingBlocker.copy(disableAppsFlyer = true, disableAllTracking = true))
            )

            PresetType.PERFORMANCE -> config.copy(
                removeBranding = true,
                display = config.display.copy(keepScreenAwake = true, multiWindow = true),
                storage = config.storage.copy(clearCacheOnExit = false),
                launching = config.launching.copy(disableBackgroundServices = true, disableWakeLocks = false),
                game = config.game.copy(fpsMonitor = true)
            )

            PresetType.COMPATIBILITY -> config.copy(
                removeBranding = false, // keep branding for compatibility? Actually remove branding may not affect compatibility, but keep minimal
                identity = IdentityConfig(), // no spoofing
                privacy = PrivacyConfig(hideRoot = false), // no root hide
                environment = EnvironmentConfig(hideRoot = false, hideEmulator = false, hideDeveloperOptions = false, hideUsbAdb = false, hideMockLocation = false, spoofPhysicalDeviceProfile = false),
                storage = StorageConfig(redirectExternalStorage = false, isolateStorage = true),
                networking = NetworkingConfig(), // no proxy
                parityFeatures = ParityFeaturesConfig() // no tracking blocker, no CPU/GPU hide
            )

            PresetType.CLEAN_CLONE -> config.copy(
                removeBranding = true,
                identity = IdentityConfig(),
                privacy = PrivacyConfig(),
                display = DisplayConfig(),
                media = MediaConfig(),
                navigation = NavigationConfig(),
                storage = StorageConfig(),
                launching = LaunchingConfig(),
                networking = NetworkingConfig(),
                notification = NotificationConfig(),
                game = GameConfig(),
                tvWear = TvWearConfig(),
                automation = AutomationConfig(),
                developer = DeveloperConfig(),
                environment = EnvironmentConfig(hideRoot = false, hideEmulator = false),
                dataBundle = DataBundleConfig(enabled = false),
                parityFeatures = ParityFeaturesConfig()
            )

            PresetType.CUSTOM -> config // keep as is
        }
    }

    fun getPresetForConfig(config: CloneConfig): PresetType {
        // Heuristic to detect which preset matches current config
        return when {
            config.privacy.incognitoMode && config.privacy.disableClipboard && config.privacy.disableSensors -> PresetType.MAXIMUM_PRIVACY
            config.privacy.stealthMode && config.privacy.excludeFromRecents -> PresetType.PRIVACY
            config.display.keepScreenAwake && config.game.fpsMonitor -> PresetType.PERFORMANCE
            config.identity.androidId.isEmpty() && !config.environment.hideRoot -> PresetType.COMPATIBILITY
            config.appName.isNotEmpty() && config.identity.androidId.isEmpty() && config.privacy == PrivacyConfig() -> PresetType.CLEAN_CLONE
            else -> PresetType.DEFAULT
        }
    }
}
