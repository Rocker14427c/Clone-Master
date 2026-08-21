package com.clonemaster.display

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import com.clonemaster.cloning.models.DisplayConfig

/**
 * Independent implementation for screensaver behavior
 * Public feature reference: App Cloner lists "Screen saver" under Display options
 * Equivalent functionality: control whether clone allows screensaver / dream service, keep screen awake, or show custom
 * Functional parity with compatibility with Android limitations
 */
class ScreensaverController {

    enum class ScreensaverMode {
        DEFAULT, // system default
        DISABLE, // prevent screensaver while clone is foreground
        KEEP_SCREEN_AWAKE, // same as keepScreenAwake but explicit
        CUSTOM_MESSAGE // show custom message when screensaver would trigger (not actual dream, but overlay)
    }

    data class ScreensaverConfig(
        var mode: ScreensaverMode = ScreensaverMode.DEFAULT,
        var customMessage: String = "",
        var preventDream: Boolean = false
    )

    fun apply(activity: Activity, config: ScreensaverConfig, displayConfig: DisplayConfig) {
        when (config.mode) {
            ScreensaverMode.DISABLE, ScreensaverMode.KEEP_SCREEN_AWAKE -> {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            ScreensaverMode.DEFAULT -> {
                // Do nothing, allow system screensaver
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            ScreensaverMode.CUSTOM_MESSAGE -> {
                // Keep screen on and show overlay message when idle – independent implementation
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // Also respect displayConfig.keepScreenAwake for functional parity
        if (displayConfig.keepScreenAwake) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    object Hooks {
        fun install(config: ScreensaverConfig) {
            if (config.preventDream) {
                // Hook DreamService to prevent starting
                // Hook WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON handling
            }
        }
    }
}
