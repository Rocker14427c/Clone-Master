package com.clonemaster.navigation

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.clonemaster.cloning.models.NavigationConfig
import com.clonemaster.cloning.models.VolumeKeyAction
import java.lang.ref.WeakReference
import kotlin.math.sqrt

/**
 * Navigation Controls – per-clone configurable.
 *
 * Runtime hooks intercept navigation events to enforce clone's navigation policies.
 * Implementation uses gesture detectors, sensor listeners, and key event interception.
 *
 * Features implemented:
 * - Floating back button
 * - Confirm exit dialog
 * - Minimize on back (instead of finish)
 * - Shake to exit
 * - Swipe to back
 * - Long press back menu
 * - Popup blocker
 * - Activity blocker
 * - Kiosk mode
 * - Volume key mapping
 */
class NavigationControls {

    /**
     * Add floating back button overlay.
     * Called per-activity from HookApplication's lifecycle callbacks.
     */
    fun addFloatingBack(activity: Activity) {
        try {
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val btn = android.widget.Button(activity).apply {
                text = "←"
                textSize = 18f
                setPadding(16, 16, 16, 16)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                x = 16
                y = 100
            }
            btn.setOnClickListener {
                try {
                    activity.onBackPressed()
                } catch (e: Exception) {
                    android.util.Log.w("CloneMaster", "Floating back press failed: ${e.message}")
                }
            }
            wm.addView(btn, params)
            NavigationSpoofRegistry.floatingBackView = WeakReference(btn)
            NavigationSpoofRegistry.floatingBackWindow = WeakReference(wm)
            android.util.Log.d("CloneMaster", "Floating back button added")
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Failed to add floating back: ${e.message}")
        }
    }

    /**
     * Remove floating back button overlay.
     */
    fun removeFloatingBack() {
        try {
            val wm = NavigationSpoofRegistry.floatingBackWindow?.get()
            val view = NavigationSpoofRegistry.floatingBackView?.get()
            if (wm != null && view != null) {
                wm.removeView(view)
                android.util.Log.d("CloneMaster", "Floating back button removed")
            }
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Failed to remove floating back: ${e.message}")
        }
    }

    object Hooks {
        private var installed = false
        private var config: NavigationConfig? = null
        private var shakeDetector: ShakeDetector? = null

        /**
         * Install all navigation hooks inside the cloned app process.
         * Called from HookFramework.installAll() inside the cloned app.
         */
        fun install(cfg: NavigationConfig) {
            if (installed) return
            config = cfg
            installed = true

            try {
                android.util.Log.i("CloneMaster", "NavigationControls.Hooks installing...")

                // 1. Floating back button
                if (cfg.floatingBack) {
                    NavigationSpoofRegistry.floatingBack = true
                    android.util.Log.i("CloneMaster", "Floating back enabled")
                }

                // 2. Confirm exit
                if (cfg.confirmExit) {
                    NavigationSpoofRegistry.confirmExit = true
                    android.util.Log.i("CloneMaster", "Confirm exit enabled")
                }

                // 3. Minimize on back
                if (cfg.minimizeOnBack) {
                    NavigationSpoofRegistry.minimizeOnBack = true
                    android.util.Log.i("CloneMaster", "Minimize on back enabled")
                }

                // 4. Shake to exit
                if (cfg.shakeToExit) {
                    NavigationSpoofRegistry.shakeToExit = true
                    android.util.Log.i("CloneMaster", "Shake to exit enabled")
                }

                // 5. Swipe to back
                if (cfg.swipeToBack) {
                    NavigationSpoofRegistry.swipeToBack = true
                    android.util.Log.i("CloneMaster", "Swipe to back enabled")
                }

                // 6. Long press back menu
                if (cfg.longPressBackMenu) {
                    NavigationSpoofRegistry.longPressBackMenu = true
                    android.util.Log.i("CloneMaster", "Long press back menu enabled")
                }

                // 7. Popup blocker
                if (cfg.popupBlocker) {
                    NavigationSpoofRegistry.popupBlocker = true
                    android.util.Log.i("CloneMaster", "Popup blocker enabled")
                }

                // 8. Activity monitor
                if (cfg.activityMonitor) {
                    NavigationSpoofRegistry.activityMonitor = true
                    android.util.Log.i("CloneMaster", "Activity monitor enabled")
                }

                // 9. Blocked activities
                if (cfg.blockedActivities.isNotEmpty()) {
                    NavigationSpoofRegistry.blockedActivities = cfg.blockedActivities.toSet()
                    android.util.Log.i("CloneMaster", "Blocked activities: ${cfg.blockedActivities.joinToString(", ")}")
                }

                // 10. Kiosk mode
                if (cfg.kioskMode) {
                    NavigationSpoofRegistry.kioskMode = true
                    android.util.Log.i("CloneMaster", "Kiosk mode enabled")
                }

                // 11. Volume key action
                if (cfg.volumeKeyAction != VolumeKeyAction.DEFAULT) {
                    NavigationSpoofRegistry.volumeKeyAction = cfg.volumeKeyAction
                    NavigationSpoofRegistry.customVolumeMapping = cfg.customVolumeMapping.toMap()
                    android.util.Log.i("CloneMaster", "Volume key action: ${cfg.volumeKeyAction}")
                }

                // 12. Fingerprint actions
                if (cfg.fingerprintActions.isNotEmpty()) {
                    NavigationSpoofRegistry.fingerprintActions = cfg.fingerprintActions
                    android.util.Log.i("CloneMaster", "Fingerprint actions configured")
                }

                android.util.Log.i("CloneMaster", "NavigationControls.Hooks installed successfully")

            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "NavigationControls.Hooks install failed: ${e.message}", e)
            }
        }

        /**
         * Handle back button press.
         * Called from Activity.onBackPressed() wrapper.
         * @return true if the back press was handled (don't propagate), false otherwise
         */
        fun handleBackPress(activity: Activity): Boolean {
            val cfg = config ?: return false

            // Confirm exit
            if (cfg.confirmExit) {
                android.app.AlertDialog.Builder(activity)
                    .setTitle("Exit")
                    .setMessage("Are you sure you want to exit?")
                    .setPositiveButton("Yes") { _, _ -> activity.finish() }
                    .setNegativeButton("No", null)
                    .show()
                return true
            }

            // Minimize on back
            if (cfg.minimizeOnBack) {
                activity.moveTaskToBack(true)
                return true
            }

            return false
        }

        /**
         * Check if an activity should be blocked from launching.
         * Called from Activity.startActivity() wrapper.
         */
        fun isActivityBlocked(activityName: String): Boolean {
            return config?.blockedActivities?.any { blocked ->
                activityName.contains(blocked) || blocked == activityName
            } == true
        }

        /**
         * Check if kiosk mode should prevent leaving the app.
         * Called from Activity.onKeyDown() wrapper.
         */
        fun shouldBlockExit(keyCode: Int): Boolean {
            if (config?.kioskMode != true) return false
            // Block home button, back button, and recent apps
            return keyCode == KeyEvent.KEYCODE_HOME ||
                    keyCode == KeyEvent.KEYCODE_BACK ||
                    keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
                    keyCode == KeyEvent.KEYCODE_RECENTS
        }

        /**
         * Handle volume key press.
         * Called from Activity.onKeyDown() wrapper.
         * @return true if the key was handled (don't propagate), false otherwise
         */
        fun handleVolumeKey(activity: Activity, keyCode: Int): Boolean {
            val cfg = config ?: return false
            if (cfg.volumeKeyAction == VolumeKeyAction.DEFAULT) return false

            val keyName = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> "VOLUME_UP"
                KeyEvent.KEYCODE_VOLUME_DOWN -> "VOLUME_DOWN"
                KeyEvent.KEYCODE_VOLUME_MUTE -> "VOLUME_MUTE"
                else -> return false
            }

            // Check custom mapping first
            val mappedAction = cfg.customVolumeMapping[keyName]
            if (mappedAction != null) {
                executeVolumeAction(activity, mappedAction)
                return true
            }

            // Use default action for the volume key mode
            when (cfg.volumeKeyAction) {
                VolumeKeyAction.NAVIGATION -> {
                    when (keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> activity.onBackPressed()
                        KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            // Toggle menu or show options
                        }
                    }
                    return true
                }
                VolumeKeyAction.MEDIA -> {
                    // Let media handle it (return false)
                    return false
                }
                VolumeKeyAction.CUSTOM -> {
                    // Already handled by custom mapping above
                    return true
                }
                VolumeKeyAction.DEFAULT -> return false
            }

            return false
        }

        private fun executeVolumeAction(activity: Activity, action: String) {
            when (action.uppercase()) {
                "BACK" -> activity.onBackPressed()
                "HOME" -> activity.moveTaskToBack(true)
                "MENU" -> activity.openOptionsMenu()
                "SCREENSHOT" -> {
                    // Take screenshot (requires permission)
                    android.util.Log.d("CloneMaster", "Screenshot requested via volume key")
                }
                "TORCH" -> {
                    // Toggle torch
                    android.util.Log.d("CloneMaster", "Torch toggle requested via volume key")
                }
            }
        }

        /**
         * Register shake detector for shake-to-exit.
         * Called per-activity from lifecycle callbacks.
         */
        fun registerShakeDetector(activity: Activity) {
            if (config?.shakeToExit != true) return
            try {
                val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                shakeDetector = ShakeDetector {
                    // Shake detected – exit the clone
                    android.util.Log.i("CloneMaster", "Shake detected – exiting clone")
                    activity.finishAffinity()
                }
                val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (accelerometer != null) {
                    sensorManager.registerListener(
                        shakeDetector,
                        accelerometer,
                        SensorManager.SENSOR_DELAY_UI
                    )
                    NavigationSpoofRegistry.shakeDetector = shakeDetector
                    android.util.Log.d("CloneMaster", "Shake detector registered")
                }
            } catch (e: Exception) {
                android.util.Log.w("CloneMaster", "Failed to register shake detector: ${e.message}")
            }
        }

        /**
         * Unregister shake detector.
         */
        fun unregisterShakeDetector(context: Context) {
            try {
                val detector = shakeDetector
                if (detector != null) {
                    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                    sensorManager.unregisterListener(detector)
                    shakeDetector = null
                    android.util.Log.d("CloneMaster", "Shake detector unregistered")
                }
            } catch (e: Exception) {
                android.util.Log.w("CloneMaster", "Failed to unregister shake detector: ${e.message}")
            }
        }

        /**
         * Check if a popup/dialog should be blocked.
         * Called from AlertDialog.Builder.show() wrapper.
         */
        fun shouldBlockPopup(title: String?, message: String?): Boolean {
            if (config?.popupBlocker != true) return false
            // Block common ad/pop-up patterns
            val text = "${title.orEmpty()} ${message.orEmpty()}".lowercase()
            val blockPatterns = listOf(
                "rate us", "rate this app", "subscribe", "notification",
                "enable notifications", "special offer", "limited time"
            )
            return blockPatterns.any { text.contains(it) }
        }
    }
}

/**
 * Shake detector using accelerometer sensor.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var lastShakeTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastUpdate = 0L

    override fun onSensorChanged(event: SensorEvent) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdate < 100) return // Throttle to 10 checks/sec

        val diffTime = currentTime - lastUpdate
        lastUpdate = currentTime

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val speed = sqrt(
            (x - lastX) * (x - lastX) +
                    (y - lastY) * (y - lastY) +
                    (z - lastZ) * (z - lastZ)
        ) / diffTime * 10000

        if (speed > 800 && currentTime - lastShakeTime > 1000) {
            lastShakeTime = currentTime
            onShake()
        }

        lastX = x
        lastY = y
        lastZ = z
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}

/**
 * Registry for navigation spoofing state.
 * Used by wrapper classes in clone runtime to check navigation policies.
 */
object NavigationSpoofRegistry {
    var floatingBack: Boolean = false
    var floatingBackView: WeakReference<View>? = null
    var floatingBackWindow: WeakReference<WindowManager>? = null
    var confirmExit: Boolean = false
    var minimizeOnBack: Boolean = false
    var shakeToExit: Boolean = false
    var swipeToBack: Boolean = false
    var longPressBackMenu: Boolean = false
    var popupBlocker: Boolean = false
    var activityMonitor: Boolean = false
    var blockedActivities: Set<String> = emptySet()
    var kioskMode: Boolean = false
    var volumeKeyAction: VolumeKeyAction = VolumeKeyAction.DEFAULT
    var customVolumeMapping: Map<String, String> = emptyMap()
    var fingerprintActions: String = ""
    var shakeDetector: ShakeDetector? = null

    fun clear() {
        floatingBack = false
        floatingBackView = null
        floatingBackWindow = null
        confirmExit = false
        minimizeOnBack = false
        shakeToExit = false
        swipeToBack = false
        longPressBackMenu = false
        popupBlocker = false
        activityMonitor = false
        blockedActivities = emptySet()
        kioskMode = false
        volumeKeyAction = VolumeKeyAction.DEFAULT
        customVolumeMapping = emptyMap()
        fingerprintActions = ""
        shakeDetector = null
    }
}
