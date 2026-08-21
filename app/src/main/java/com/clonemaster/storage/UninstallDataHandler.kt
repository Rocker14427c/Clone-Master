package com.clonemaster.storage

import com.clonemaster.cloning.models.CloneConfig
import java.io.File

/**
 * Independent implementation for "Prompt to keep app data on uninstall"
 * Public feature reference: App Cloner lists "Prompt to keep app data on uninstall" under Storage options
 * Equivalent functionality: use android:hasFragileUserData and preserve data handling
 * Functional parity with independent implementation
 */
class UninstallDataHandler {

    data class UninstallConfig(
        var promptToKeepData: Boolean = false,
        var hasFragileUserData: Boolean = false, // API 29+ flag to prompt user to keep data on uninstall
        var preserveDataOnUninstall: Boolean = false // legacy field from StorageConfig
    )

    fun applyToManifest(manifestFile: File, config: UninstallConfig, diagnostics: com.clonemaster.cloning.engine.CloningDiagnostics) {
        var content = manifestFile.readText()

        if (config.promptToKeepData || config.hasFragileUserData) {
            // Add android:hasFragileUserData="true" to <application> – shows prompt on uninstall to keep data (Android 10+)
            if (content.contains("android:hasFragileUserData")) {
                content = content.replace(Regex("""android:hasFragileUserData="[^"]*""""), """android:hasFragileUserData="true"""")
            } else {
                content = content.replace("<application", """<application android:hasFragileUserData="true"""")
            }
            diagnostics.log("Set hasFragileUserData=true for uninstall data prompt")
        }

        manifestFile.writeText(content)
    }

    object Hooks {
        fun install(config: UninstallConfig) {
            // For preserveDataOnUninstall, hook uninstall flow – not directly possible, but we can backup data on app exit if needed
        }
    }
}
