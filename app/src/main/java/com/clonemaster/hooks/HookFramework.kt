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
        // Order matters
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
