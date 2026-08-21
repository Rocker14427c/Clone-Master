package com.clonemaster.notification

import android.app.NotificationChannel
import android.os.Build
import com.clonemaster.cloning.models.NotificationConfig

/**
 * Independent implementation for notification dots (badges)
 * Public feature reference: App Cloner lists "Add notification dots to app icons" under Notification options
 * Equivalent functionality: control whether launcher shows notification dots for clone
 * Functional parity with independent implementation
 */
class DotsController {

    data class DotsConfig(
        var showDots: Boolean? = null, // null = system default, true/false = override
        var enableDots: Boolean = true
    )

    fun applyToChannel(channel: NotificationChannel, config: DotsConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            config.showDots?.let { show ->
                channel.setShowBadge(show)
            }
        }
    }

    object Hooks {
        fun install(config: NotificationConfig) {
            // Hook NotificationChannel.setShowBadge and Notification.Builder.setShowBadge
            // Hook for launcher badge – some launchers use ShortcutBadger or Notification dots via NotificationChannel

            // If showDots == false, hook to return false for setShowBadge
            // Compatibility: Launcher may still show dots based on system settings – we can only control via channel, degraded gracefully
        }
    }
}
