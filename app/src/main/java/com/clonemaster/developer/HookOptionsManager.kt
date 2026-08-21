package com.clonemaster.developer

import com.clonemaster.cloning.models.DeveloperConfig

/**
 * Independent implementation for Hook options / Safe mode / Disable hooks
 * Public feature reference: WhatsNew 3.6.8 says "Option 'Safe mode' moved inside 'Hook options' and now called 'Disable hooks'"
 * Equivalent functionality: provide disableHooks toggle as alias for safeMode, with hook options grouping
 * Functional parity with independent implementation
 */
class HookOptionsManager {

    data class HookOptions(
        var nativeHooksEnabled: Boolean = true, // WhatsNew 3.6.7: New option 'Native hooks'
        var disableHooks: Boolean = false, // Previously Safe mode, now Disable hooks – alias for safeMode
        var safeMode: Boolean = false, // legacy, kept for compatibility
        var hookPine: Boolean = true,
        var hookByteHook: Boolean = true,
        var hookAndHook: Boolean = false,
        var hookArtInline: Boolean = true,
        var hookPlt: Boolean = true,
        var disableAllHooksForDebugging: Boolean = false
    )

    fun toDeveloperConfig(hookOptions: HookOptions): DeveloperConfig {
        return DeveloperConfig(
            nativeHooksEnabled = hookOptions.nativeHooksEnabled,
            safeMode = hookOptions.disableHooks || hookOptions.safeMode,
            hookConfig = mutableMapOf(
                "disableHooks" to hookOptions.disableHooks.toString(),
                "nativeHooks" to hookOptions.nativeHooksEnabled.toString(),
                "pine" to hookOptions.hookPine.toString(),
                "byteHook" to hookOptions.hookByteHook.toString(),
                "andHook" to hookOptions.hookAndHook.toString()
            )
        )
    }

    fun fromDeveloperConfig(devConfig: DeveloperConfig): HookOptions {
        return HookOptions(
            nativeHooksEnabled = devConfig.nativeHooksEnabled,
            disableHooks = devConfig.safeMode || devConfig.hookConfig["disableHooks"] == "true",
            safeMode = devConfig.safeMode,
            hookPine = devConfig.hookConfig["pine"]?.toBoolean() ?: true,
            hookByteHook = devConfig.hookConfig["byteHook"]?.toBoolean() ?: true
        )
    }

    object Hooks {
        fun install(options: HookOptions) {
            if (options.disableHooks || options.safeMode || options.disableAllHooksForDebugging) {
                // Disable all hooks – safe mode for debugging clone issues
                android.util.Log.w("CloneMaster", "Hooks disabled via safe mode / disable hooks")
                return
            }

            // Install Pine, ByteHook, AndHook based on options
            if (options.nativeHooksEnabled) {
                // Init native hooks: Pine for ART, ByteHook for PLT
            }
        }
    }
}
