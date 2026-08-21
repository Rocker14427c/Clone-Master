package com.clonemaster.notification

import android.app.Notification
import android.content.Context
import com.clonemaster.cloning.models.NotificationConfig

class NotificationManager(private val context: Context) {

    fun shouldFilter(notification: Notification, config: NotificationConfig): Boolean {
        if (config.silence) return true
        config.filterPatterns.forEach { pattern ->
            val text = notification.extras.getString(Notification.EXTRA_TEXT) ?: ""
            if (text.contains(pattern, ignoreCase = true)) return true
        }
        // Quiet hours
        config.quietHours?.let { (start, end) ->
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour in start..end || (start > end && (hour >= start || hour <= end))) return true
        }
        return false
    }

    fun modify(notification: Notification, config: NotificationConfig): Notification {
        // Custom color, vibration, etc
        config.color?.let { notification.color = it }
        config.ledColor?.let { /* set led */ }
        // Modify text
        if (config.modifyText) {
            // Replace text via extras
        }
        return notification
    }

    object Hooks {
        fun install(config: NotificationConfig) {
            // Hook NotificationManager.notify to filter/modify
            // Hook Toast.show to filter, change position, opacity, convert to notification
            // Toast position: hook Toast.setGravity
            // Toast opacity: hook WindowManager.LayoutParams.alpha
        }
    }
}

class ToastController {
    fun filterToast(text: String, config: NotificationConfig): Boolean {
        return config.toastFilter.any { text.contains(it, true) }
    }
}
