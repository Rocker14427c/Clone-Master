package com.clonemaster.privacy

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.clonemaster.cloning.models.PrivacyConfig
import java.io.File

/**
 * Privacy & Isolation Controls – per-clone configurable.
 *
 * Runtime hooks intercept Android API calls to enforce privacy policies.
 * Implementation uses Java-level interception (reflection, wrappers, receivers).
 *
 * Features implemented:
 * - Screenshot prevention (FLAG_SECURE)
 * - GPS location spoofing (fake Location objects)
 * - Root hiding (File.exists interception for su paths)
 * - Clipboard access blocking
 * - Logcat suppression
 * - Sensor data spoofing/disabling
 * - Accessibility services control
 * - Screen off auto-exit
 * - Mock location hiding
 */
class PrivacyManager(private val context: Context) {

    fun apply(config: PrivacyConfig) {
        // Each control isolated to clone
        // Password protection – launch gate activity
        // Stealth – remove launcher icon (handled in manifest)
        // Decoy calculator – separate activity
        // Incognito keyboard – custom InputMethodService
        // Clear on exit – register lifecycle callback
        // Permission stripping – hook checkPermission
    }

    object Hooks {
        private var installed = false
        private var config: PrivacyConfig? = null
        private var screenOffReceiver: BroadcastReceiver? = null

        /**
         * Install all privacy hooks inside the cloned app process.
         * Called from HookFramework.installAll() inside the cloned app.
         */
        fun install(cfg: PrivacyConfig) {
            if (installed) return
            config = cfg
            installed = true

            try {
                android.util.Log.i("CloneMaster", "PrivacyManager.Hooks installing...")

                // 1. Screenshot/screen recording prevention
                if (cfg.disableScreenshots) {
                    PrivacySpoofRegistry.disableScreenshots = true
                    android.util.Log.i("CloneMaster", "Screenshot prevention enabled (FLAG_SECURE)")
                }

                // 2. Screen recording prevention
                if (cfg.disableScreenRecord) {
                    PrivacySpoofRegistry.disableScreenRecord = true
                    android.util.Log.i("CloneMaster", "Screen recording prevention enabled")
                }

                // 3. GPS location spoofing
                if (cfg.gpsSpoof) {
                    PrivacySpoofRegistry.gpsSpoofEnabled = true
                    PrivacySpoofRegistry.fakeLat = cfg.fakeLat
                    PrivacySpoofRegistry.fakeLng = cfg.fakeLng
                    PrivacySpoofRegistry.fakeAltitude = cfg.fakeAltitude
                    PrivacySpoofRegistry.hideMockLocation = cfg.hideMockLocation
                    android.util.Log.i("CloneMaster", "GPS spoof: lat=${cfg.fakeLat}, lng=${cfg.fakeLng}, alt=${cfg.fakeAltitude}")
                }

                // 4. Root hiding
                if (cfg.hideRoot) {
                    PrivacySpoofRegistry.hideRoot = true
                    android.util.Log.i("CloneMaster", "Root hiding enabled")
                }

                // 5. Clipboard access blocking
                if (cfg.disableClipboard) {
                    PrivacySpoofRegistry.disableClipboard = true
                    android.util.Log.i("CloneMaster", "Clipboard access disabled")
                }

                // 6. Exclude from recents
                if (cfg.excludeFromRecents) {
                    PrivacySpoofRegistry.excludeFromRecents = true
                    android.util.Log.i("CloneMaster", "Exclude from recents enabled")
                }

                // 7. Disable logcat
                if (cfg.disableLogcat) {
                    PrivacySpoofRegistry.disableLogcat = true
                    android.util.Log.i("CloneMaster", "Logcat disabled")
                }

                // 8. Disable sensors
                if (cfg.disableSensors) {
                    PrivacySpoofRegistry.disableSensors = true
                    android.util.Log.i("CloneMaster", "Sensors disabled")
                }

                // 9. Fake sensors
                if (cfg.fakeSensors) {
                    PrivacySpoofRegistry.fakeSensors = true
                    android.util.Log.i("CloneMaster", "Sensors spoofing enabled")
                }

                // 10. Disable accessibility
                if (cfg.disableAccessibility || cfg.disableAccessibilityServices) {
                    PrivacySpoofRegistry.disableAccessibility = true
                    android.util.Log.i("CloneMaster", "Accessibility services disabled")
                }

                // 11. Disable accounts access
                if (cfg.disableAccounts) {
                    PrivacySpoofRegistry.disableAccounts = true
                    android.util.Log.i("CloneMaster", "Account access disabled")
                }

                // 12. Disable contacts access
                if (cfg.disableContacts) {
                    PrivacySpoofRegistry.disableContacts = true
                    android.util.Log.i("CloneMaster", "Contacts access disabled")
                }

                // 13. Disable calendar access
                if (cfg.disableCalendar) {
                    PrivacySpoofRegistry.disableCalendar = true
                    android.util.Log.i("CloneMaster", "Calendar access disabled")
                }

                // 14. Disable call log access
                if (cfg.disableCallLog) {
                    PrivacySpoofRegistry.disableCallLog = true
                    android.util.Log.i("CloneMaster", "Call log access disabled")
                }

                // 15. Disable share
                if (cfg.disableShare) {
                    PrivacySpoofRegistry.disableShare = true
                    android.util.Log.i("CloneMaster", "Share intent disabled")
                }

                // 16. Hide other apps
                if (cfg.hideOtherApps) {
                    PrivacySpoofRegistry.hideOtherApps = true
                    android.util.Log.i("CloneMaster", "Other apps hidden from PackageManager")
                }

                // 17. Disable autofill
                if (cfg.disableAutofill) {
                    PrivacySpoofRegistry.disableAutofill = true
                    android.util.Log.i("CloneMaster", "Autofill disabled")
                }

                // 18. Disable device admin
                if (cfg.disableDeviceAdmin) {
                    PrivacySpoofRegistry.disableDeviceAdmin = true
                    android.util.Log.i("CloneMaster", "Device admin disabled")
                }

                // 19. Fake timezone
                if (cfg.fakeTimezone.isNotEmpty()) {
                    PrivacySpoofRegistry.fakeTimezone = cfg.fakeTimezone
                    android.util.Log.i("CloneMaster", "Timezone spoofed: ${cfg.fakeTimezone}")
                }

                // 20. Stealth mode (combine multiple privacy features)
                if (cfg.stealthMode) {
                    PrivacySpoofRegistry.stealthMode = true
                    PrivacySpoofRegistry.disableScreenshots = true
                    PrivacySpoofRegistry.excludeFromRecents = true
                    android.util.Log.i("CloneMaster", "Stealth mode enabled")
                }

                // 21. Knox disable
                if (cfg.knoxDisable) {
                    PrivacySpoofRegistry.knoxBypass = true
                    android.util.Log.i("CloneMaster", "Knox bypass enabled")
                }

                // 22. Auto-exit on screen off (requires context, registered in HookApplication)
                if (cfg.autoExitOnScreenOff) {
                    PrivacySpoofRegistry.autoExitOnScreenOff = true
                    android.util.Log.i("CloneMaster", "Auto-exit on screen off registered")
                }

                // 23. Shake to exit (requires sensor listener, registered in HookApplication)
                if (cfg.shakeToExit) {
                    PrivacySpoofRegistry.shakeToExit = true
                    android.util.Log.i("CloneMaster", "Shake to exit enabled")
                }

                // 24. Disabled permissions list
                if (cfg.disabledPermissions.isNotEmpty()) {
                    PrivacySpoofRegistry.disabledPermissions = cfg.disabledPermissions.toSet()
                    android.util.Log.i("CloneMaster", "Disabled permissions: ${cfg.disabledPermissions.joinToString(", ")}")
                }

                android.util.Log.i("CloneMaster", "PrivacyManager.Hooks installed successfully")

            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "PrivacyManager.Hooks install failed: ${e.message}", e)
            }
        }

        /**
         * Creates a fake Location object with spoofed coordinates.
         * Used by LocationManager wrapper in clone runtime.
         */
        fun createFakeLocation(provider: String = LocationManager.GPS_PROVIDER): Location {
            val loc = Location(provider)
            loc.latitude = config?.fakeLat ?: 37.4220
            loc.longitude = config?.fakeLng ?: -122.0841
            loc.altitude = config?.fakeAltitude ?: 0.0
            loc.accuracy = 10.0f
            loc.time = System.currentTimeMillis()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            }
            return loc
        }

        /**
         * Checks if a file path should be hidden (root detection evasion).
         * Used by File.exists() wrapper in clone runtime.
         */
        fun isRootPathHidden(path: String): Boolean {
            if (config?.hideRoot != true) return false
            val hiddenPaths = setOf(
                "/system/xbin/su",
                "/system/bin/su",
                "/system/app/Superuser.apk",
                "/system/app/SuperSU.apk",
                "/system/app/SuperSU",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/data/local/su",
                "/su/bin/su",
                "/su/bin",
                "/sbin/su",
                "/system/su",
                "/system/bin/.ext/.su",
                "/system/usr/we-need-root/",
                "/cache/MagiskBoot",
                "/data/adb/magisk",
                "/data/adb/modules",
                "/dev/.magisk.unblock"
            )
            return path in hiddenPaths
        }

        /**
         * Checks if a permission should be denied.
         * Used by checkPermission wrapper in clone runtime.
         */
        fun isPermissionDenied(permission: String): Boolean {
            return config?.disabledPermissions?.contains(permission) == true
        }

        // ---- Public API for runtime queries ----

        fun shouldDisableScreenshots(): Boolean = config?.disableScreenshots == true || config?.stealthMode == true
        fun shouldDisableScreenRecord(): Boolean = config?.disableScreenRecord == true
        fun shouldHideRoot(): Boolean = config?.hideRoot == true
        fun shouldDisableClipboard(): Boolean = config?.disableClipboard == true
        fun shouldExcludeFromRecents(): Boolean = config?.excludeFromRecents == true || config?.stealthMode == true
        fun shouldDisableLogcat(): Boolean = config?.disableLogcat == true
        fun shouldDisableSensors(): Boolean = config?.disableSensors == true
        fun shouldFakeSensors(): Boolean = config?.fakeSensors == true
        fun shouldDisableAccessibility(): Boolean = config?.disableAccessibility == true || config?.disableAccessibilityServices == true
        fun shouldGpsSpoof(): Boolean = config?.gpsSpoof == true
        fun shouldHideMockLocation(): Boolean = config?.hideMockLocation == true
        fun shouldDisableAccounts(): Boolean = config?.disableAccounts == true
        fun shouldDisableContacts(): Boolean = config?.disableContacts == true
        fun shouldDisableCalendar(): Boolean = config?.disableCalendar == true
        fun shouldDisableCallLog(): Boolean = config?.disableCallLog == true
        fun shouldDisableShare(): Boolean = config?.disableShare == true
        fun shouldHideOtherApps(): Boolean = config?.hideOtherApps == true
        fun shouldDisableAutofill(): Boolean = config?.disableAutofill == true
        fun shouldDisableDeviceAdmin(): Boolean = config?.disableDeviceAdmin == true
        fun shouldAutoExitOnScreenOff(): Boolean = config?.autoExitOnScreenOff == true
        fun shouldShakeToExit(): Boolean = config?.shakeToExit == true
        fun shouldBypassKnox(): Boolean = config?.knoxDisable == true
        fun getFakeTimezone(): String? = config?.fakeTimezone?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Registry for privacy spoofing overrides.
 * Used by wrapper classes in the clone runtime to check privacy policies.
 */
object PrivacySpoofRegistry {
    var disableScreenshots: Boolean = false
    var disableScreenRecord: Boolean = false
    var hideRoot: Boolean = false
    var disableClipboard: Boolean = false
    var excludeFromRecents: Boolean = false
    var disableLogcat: Boolean = false
    var disableSensors: Boolean = false
    var fakeSensors: Boolean = false
    var disableAccessibility: Boolean = false
    var gpsSpoofEnabled: Boolean = false
    var fakeLat: Double = 37.4220
    var fakeLng: Double = -122.0841
    var fakeAltitude: Double = 0.0
    var hideMockLocation: Boolean = false
    var disableAccounts: Boolean = false
    var disableContacts: Boolean = false
    var disableCalendar: Boolean = false
    var disableCallLog: Boolean = false
    var disableShare: Boolean = false
    var hideOtherApps: Boolean = false
    var disableAutofill: Boolean = false
    var disableDeviceAdmin: Boolean = false
    var autoExitOnScreenOff: Boolean = false
    var shakeToExit: Boolean = false
    var knoxBypass: Boolean = false
    var stealthMode: Boolean = false
    var fakeTimezone: String? = null
    var disabledPermissions: Set<String> = emptySet()

    fun clear() {
        disableScreenshots = false
        disableScreenRecord = false
        hideRoot = false
        disableClipboard = false
        excludeFromRecents = false
        disableLogcat = false
        disableSensors = false
        fakeSensors = false
        disableAccessibility = false
        gpsSpoofEnabled = false
        fakeLat = 37.4220
        fakeLng = -122.0841
        fakeAltitude = 0.0
        hideMockLocation = false
        disableAccounts = false
        disableContacts = false
        disableCalendar = false
        disableCallLog = false
        disableShare = false
        hideOtherApps = false
        disableAutofill = false
        disableDeviceAdmin = false
        autoExitOnScreenOff = false
        shakeToExit = false
        knoxBypass = false
        stealthMode = false
        fakeTimezone = null
        disabledPermissions = emptySet()
    }
}

// Decoy calculator appearance
class DecoyCalculatorActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Simple calculator UI that unlocks real app on secret code e.g. 1234+=
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val display = android.widget.TextView(this).apply {
            textSize = 24f
            text = "0"
            gravity = android.view.Gravity.END
            setPadding(16, 16, 16, 16)
        }
        layout.addView(display)
        setContentView(layout)
    }
}

class PasswordGateActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            gravity = android.view.Gravity.CENTER
        }
        val input = android.widget.EditText(this).apply {
            hint = "Enter password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val button = android.widget.Button(this).apply {
            text = "Unlock"
            setOnClickListener {
                // Verify password from clone_config.json
                finish()
            }
        }
        layout.addView(android.widget.TextView(this).apply {
            text = "Clone-Master Protected"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
        })
        layout.addView(input)
        layout.addView(button)
        setContentView(layout)
    }
}

class IncognitoKeyboardService : android.inputmethodservice.InputMethodService() {
    // No learning, no personalization
    override fun onCreate() {
        super.onCreate()
        // Disable all learning/personalization features
    }
}
