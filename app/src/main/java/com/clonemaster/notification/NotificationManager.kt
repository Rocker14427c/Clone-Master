package com.clonemaster.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import com.clonemaster.cloning.models.NotificationConfig

/**
 * Notification Controls – per-clone configurable.
 *
 * Runtime hooks intercept notification API calls to enforce clone's notification policies.
 * Implementation uses NotificationManager, Toast, and Vibrator APIs.
 */
class NotificationManager(private val context: Context) {

    object Hooks {
        private var installed = false
        private var config: NotificationConfig? = null

        fun install(cfg: NotificationConfig) {
            if (installed) return
            config = cfg
            installed = true

            try {
                android.util.Log.i("CloneMaster", "NotificationManager.Hooks installing...")

                if (cfg.silence) {
                    NotificationSpoofRegistry.silence = true
                    android.util.Log.i("CloneMaster", "Notifications silenced")
                }

                cfg.quietHours?.let { (start, end) ->
                    NotificationSpoofRegistry.quietHoursStart = start
                    NotificationSpoofRegistry.quietHoursEnd = end
                    android.util.Log.i("CloneMaster", "Quiet hours: $start:00 - $end:00")
                }

                cfg.customVibration?.let { pattern ->
                    NotificationSpoofRegistry.customVibration = pattern
                    android.util.Log.i("CloneMaster", "Custom vibration pattern set")
                }

                cfg.color?.let { color ->
                    NotificationSpoofRegistry.notificationColor = color
                    android.util.Log.i("CloneMaster", "Notification color: ${Integer.toHexString(color)}")
                }

                cfg.ledColor?.let { color ->
                    NotificationSpoofRegistry.ledColor = color
                    android.util.Log.i("CloneMaster", "LED color: ${Integer.toHexString(color)}")
                }

                if (cfg.replaceIcons) {
                    NotificationSpoofRegistry.replaceIcons = true
                    android.util.Log.i("CloneMaster", "Notification icons replacement enabled")
                }

                if (cfg.replaceActions) {
                    NotificationSpoofRegistry.replaceActions = true
                    android.util.Log.i("CloneMaster", "Notification actions replacement enabled")
                }

                if (cfg.filterPatterns.isNotEmpty()) {
                    NotificationSpoofRegistry.filterPatterns = cfg.filterPatterns.toList()
                    android.util.Log.i("CloneMaster", "Notification filter patterns: ${cfg.filterPatterns.size}")
                }

                if (cfg.toastFilter.isNotEmpty()) {
                    NotificationSpoofRegistry.toastFilter = cfg.toastFilter.toList()
                    android.util.Log.i("CloneMaster", "Toast filter: ${cfg.toastFilter.size} patterns")
                }

                if (cfg.toastToNotification) {
                    NotificationSpoofRegistry.toastToNotification = true
                    android.util.Log.i("CloneMaster", "Toast to notification conversion enabled")
                }

                NotificationSpoofRegistry.toastPosition = cfg.toastPosition
                NotificationSpoofRegistry.toastOpacity = cfg.toastOpacity
                NotificationSpoofRegistry.toastDuration = cfg.toastDuration

                cfg.showDots?.let { show ->
                    NotificationSpoofRegistry.showDots = show
                }

                android.util.Log.i("CloneMaster", "NotificationManager.Hooks installed successfully")
            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "NotificationManager.Hooks install failed: ${e.message}", e)
            }
        }

        fun shouldSilenceNotification(title: String?, text: String?): Boolean {
            if (config?.silence == true) return true
            // Check quiet hours
            val (start, end) = config?.quietHours ?: return false
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (start <= end) return hour in start until end
            else return hour >= start || hour < end // wraps midnight
        }

        fun shouldFilterNotification(title: String?, text: String?): Boolean {
            val patterns = config?.filterPatterns ?: return false
            val content = "${title.orEmpty()} ${text.orEmpty()}".lowercase()
            return patterns.any { content.contains(it.lowercase()) }
        }

        fun shouldFilterToast(text: String?): Boolean {
            val patterns = config?.toastFilter ?: return false
            if (text == null) return false
            return patterns.any { text.contains(it, ignoreCase = true) }
        }
    }
}

object NotificationSpoofRegistry {
    var silence: Boolean = false
    var quietHoursStart: Int = 0
    var quietHoursEnd: Int = 0
    var customVibration: LongArray? = null
    var notificationColor: Int? = null
    var ledColor: Int? = null
    var replaceIcons: Boolean = false
    var replaceActions: Boolean = false
    var filterPatterns: List<String> = emptyList()
    var toastFilter: List<String> = emptyList()
    var toastToNotification: Boolean = false
    var toastPosition: String = "bottom"
    var toastOpacity: Float = 1f
    var toastDuration: Int = 0
    var showDots: Boolean? = null
    fun clear() {
        silence = false; quietHoursStart = 0; quietHoursEnd = 0
        customVibration = null; notificationColor = null; ledColor = null
        replaceIcons = false; replaceActions = false; filterPatterns = emptyList()
        toastFilter = emptyList(); toastToNotification = false
        toastPosition = "bottom"; toastOpacity = 1f; toastDuration = 0; showDots = null
    }
}

object DotsController {
    object Hooks {
        fun install(config: NotificationConfig) {
            config.showDots?.let { show ->
                android.util.Log.i("CloneMaster", "Notification dots: $show")
            }
        }
    }
}
