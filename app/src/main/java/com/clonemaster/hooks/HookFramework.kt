package com.clonemaster.hooks

import android.content.Context
import com.clonemaster.cloning.models.CloneConfig
import com.google.gson.Gson
import java.io.File

/**
 * Runtime hook framework inside generated clones.
 * This code is merged into clone's dex.
 * It reads assets/clone_config.json and installs all subsystems.
 */
object HookFramework {

    private var config: CloneConfig? = null

    fun init(context: Context) {
        try {
            val json = context.assets.open("clone_config.json").bufferedReader().readText()
            config = Gson().fromJson(json, CloneConfig::class.java)
            installAll(context, config!!)
        } catch (e: Exception) {
            android.util.Log.e("CloneMaster", "Failed to init hooks", e)
        }
    }

    private fun installAll(context: Context, cfg: CloneConfig) {
        // Check for bundled data first – if present and migration not completed, launch import activity
        try {
            val hasBundledData = try {
                context.assets.open("data/archive.zip").close()
                true
            } catch (ignored: Exception) {
                try { context.assets.open("data_manifest.json").close(); true } catch (ignored: Exception) { false }
            }
            val prefs = context.getSharedPreferences("clone_migration", Context.MODE_PRIVATE)
            val migrationCompleted = prefs.getBoolean("migration_completed", false)

            if (hasBundledData && !migrationCompleted && cfg.dataBundle.enabled) {
                // Launch FirstRunImportActivity on next activity creation
                (context.applicationContext as? android.app.Application)?.registerActivityLifecycleCallbacks(
                    object : android.app.Application.ActivityLifecycleCallbacks {
                        var launched = false
                        override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
                            if (!launched && a !is com.clonemaster.databundle.FirstRunImportActivity) {
                                launched = true
                                try {
                                    val intent = android.content.Intent(a, com.clonemaster.databundle.FirstRunImportActivity::class.java)
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    a.startActivity(intent)
                                } catch (e: Exception) {
                                    android.util.Log.w("CloneMaster", "Failed to launch import activity", e)
                                }
                            }
                        }
                        override fun onActivityStarted(a: android.app.Activity) {}
                        override fun onActivityResumed(a: android.app.Activity) {}
                        override fun onActivityPaused(a: android.app.Activity) {}
                        override fun onActivityStopped(a: android.app.Activity) {}
                        override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                        override fun onActivityDestroyed(a: android.app.Activity) {}
                    }
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Data bundle check failed", e)
        }

        // Order matters – environment spoofing first for consistency
        try {
            // Load device profile for coherent environment
            val profileJson = try { context.assets.open("device_profile.json").bufferedReader().readText() } catch (ignored: Exception) { null }
            val profile = if (profileJson != null) {
                Gson().fromJson(profileJson, com.clonemaster.cloning.models.DeviceProfile::class.java)
            } else null

            if (profile != null && cfg.environment.spoofPhysicalDeviceProfile) {
                com.clonemaster.environment.EnvironmentManager.Hooks.install(context, cfg.environment, profile)
            } else {
                // Fallback to default profile
                val defaultProfile = com.clonemaster.cloning.models.DeviceProfile()
                com.clonemaster.environment.EnvironmentManager.Hooks.install(context, cfg.environment, defaultProfile)
            }
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Environment hooks failed", e)
        }

        // Functional Parity – Independent Implementation for Public Feature Reference
        // Terms: functional parity, equivalent functionality, independent implementation, public feature reference, compatibility with Android limitations
        try {
            // Tracking blocker – public reference: Disable AppsFlyer tracking
            val trackingConfig = com.clonemaster.tracking.TrackingBlocker.TrackingBlockConfig(
                disableAppsFlyer = cfg.parityFeatures.trackingBlocker.disableAppsFlyer,
                disableAllTracking = cfg.parityFeatures.trackingBlocker.disableAllTracking
            )
            com.clonemaster.tracking.TrackingBlocker.Hooks.install(trackingConfig)

            // CPU/GPU hide – public reference: Hide CPU/GPU info
            val cpuGpuConfig = com.clonemaster.identity.CpuInfoSpoofer.CpuGpuConfig(
                hideCpuInfo = cfg.parityFeatures.cpuGpu.hideCpuInfo,
                hideGpuInfo = cfg.parityFeatures.cpuGpu.hideGpuInfo
            )
            val profileForCpu = try {
                val json = context.assets.open("device_profile.json").bufferedReader().readText()
                Gson().fromJson(json, com.clonemaster.cloning.models.DeviceProfile::class.java)
            } catch (ignored: Exception) { null }
            com.clonemaster.identity.CpuInfoSpoofer.Hooks.install(cpuGpuConfig, profileForCpu)

            // Hook options – public reference: Safe mode now called Disable hooks, Native hooks
            val hookOptions = com.clonemaster.developer.HookOptionsManager.HookOptions(
                nativeHooksEnabled = cfg.parityFeatures.hookOptions.nativeHooksEnabled,
                disableHooks = cfg.parityFeatures.hookOptions.disableHooks,
                safeMode = cfg.parityFeatures.hookOptions.safeMode
            )
            com.clonemaster.developer.HookOptionsManager.Hooks.install(hookOptions)

            // Privacy – Sneeze to exit, Knox warranty bit – public reference
            com.clonemaster.privacy.KnoxWarrantySpoofer.Hooks.install(
                com.clonemaster.privacy.KnoxWarrantySpoofer.KnoxConfig(
                    spoofWarrantyBit = cfg.parityFeatures.knoxWarranty.spoofWarrantyBit,
                    warrantyBitValue = cfg.parityFeatures.knoxWarranty.warrantyBitValue
                )
            )

            // Display – Screensaver, Support chat – public reference
            com.clonemaster.display.ScreensaverController.Hooks.install(
                com.clonemaster.display.ScreensaverController.ScreensaverConfig(
                    mode = try { com.clonemaster.display.ScreensaverController.ScreensaverMode.valueOf(cfg.parityFeatures.screensaver.mode) } catch (ignored: Exception) { com.clonemaster.display.ScreensaverController.ScreensaverMode.DEFAULT },
                    preventDream = cfg.parityFeatures.screensaver.preventDream
                )
            )

            // Media – Text on screen mute
            com.clonemaster.media.TextBasedAudioMute.Hooks.install(
                com.clonemaster.media.TextBasedAudioMute.TextMuteConfig(
                    enabled = cfg.parityFeatures.textMute.enabled,
                    muteTriggers = cfg.parityFeatures.textMute.muteTriggers
                )
            )

            // Storage – Uninstall data prompt
            // Manifest handling done at build time, hooks not needed

            // Launching – Disable screen on/off events
            com.clonemaster.launching.ScreenEventBlocker.Hooks.install(
                com.clonemaster.launching.ScreenEventBlocker.ScreenEventConfig(
                    disableScreenOnOffEvents = cfg.parityFeatures.screenEvents.disableScreenOnOffEvents
                )
            )

            // Networking – Notification toggle, Tunnel Manager, Proxy list
            com.clonemaster.networking.NotificationNetworkingToggle.Hooks.install(
                com.clonemaster.networking.NotificationNetworkingToggle.NotificationToggleConfig(
                    enabled = cfg.parityFeatures.notificationNetworkingToggle.enabled
                )
            )
            com.clonemaster.networking.TunnelManager.Hooks.install(
                com.clonemaster.networking.TunnelManager.TunnelManagerConfig(
                    enabled = cfg.parityFeatures.tunnelManager.enabled,
                    activeTunnelId = cfg.parityFeatures.tunnelManager.activeTunnelId
                )
            )

            // Notification – Dots
            com.clonemaster.notification.DotsController.Hooks.install(cfg.notification)

            // Locale improved
            com.clonemaster.display.LocaleManager.Hooks.install(
                com.clonemaster.display.LocaleManager.LocaleConfig(
                    customLocale = cfg.parityFeatures.locale.customLocale
                )
            )

            // WebView script inject mode
            // Handled via WebViewScriptManager

        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Parity features hooks failed", e)
        }

        // Identity & Privacy – now compatible with environment profile
        com.clonemaster.identity.IdentityManager.Hooks.install(cfg.identity)
        com.clonemaster.privacy.PrivacyManager.Hooks.install(cfg.privacy)
        com.clonemaster.display.DisplayCustomizer.Hooks.install(cfg.display)
        com.clonemaster.storage.StorageIsolation.Hooks.install(cfg.storage)
        com.clonemaster.networking.ProxyManager.Hooks.install(cfg.networking)
        com.clonemaster.media.MediaControls.Hooks.install(cfg.media)
        com.clonemaster.navigation.NavigationControls.Hooks.install(cfg.navigation)
        com.clonemaster.launching.LaunchManager.Hooks.install(cfg.launching)
        com.clonemaster.notification.NotificationManager.Hooks.install(cfg.notification)
        com.clonemaster.game.GameFeatures.Hooks.install(cfg.game)
        com.clonemaster.tvwear.TvWearManager.Hooks.install(cfg.tvWear)
        com.clonemaster.automation.AutomationEngine.Hooks.install(cfg.automation)
        com.clonemaster.developer.DeveloperTools.Hooks.install(cfg.developer)

        // ViewMod and WebView toolkit are activity-lifecycle based
        // Register ActivityLifecycleCallbacks to apply per-activity
        (context.applicationContext as? android.app.Application)?.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
                    com.clonemaster.display.DisplayCustomizer().apply(a, cfg.display)
                    com.clonemaster.viewmod.ViewModificationEngine().apply {
                        // load rules from cfg.viewMods
                        // apply(a)
                    }
                }
                override fun onActivityStarted(a: android.app.Activity) {}
                override fun onActivityResumed(a: android.app.Activity) {}
                override fun onActivityPaused(a: android.app.Activity) {}
                override fun onActivityStopped(a: android.app.Activity) {}
                override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: android.app.Activity) {}
            }
        )
    }
}
