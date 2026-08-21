package com.clonemaster.tvwear

import android.content.Context
import com.clonemaster.cloning.models.TvWearConfig

class TvWearManager(private val context: Context) {

    fun apply(config: TvWearConfig) {
        if (config.joystickPointer) {
            // Enable joystick pointer for non-TV apps
        }
        if (config.removeWearComponents) {
            // Already handled in manifest: remove <uses-feature android:name="android.hardware.type.watch" />
        }
    }

    object Hooks {
        fun install(config: TvWearConfig) {
            if (config.joystickPointer) {
                // Hook to show pointer overlay
            }
            if (config.pip) {
                // Hook Activity.enterPictureInPictureMode
            }
        }
    }
}
