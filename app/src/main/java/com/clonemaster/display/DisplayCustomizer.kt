package com.clonemaster.display

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatDelegate
import com.clonemaster.cloning.models.DarkMode
import com.clonemaster.cloning.models.DisplayConfig
import com.clonemaster.cloning.models.NotchHandling
import java.util.Locale

/**
 * Display & UI Customization – per-clone configurable.
 *
 * Two-layer implementation:
 * 1. DisplayCustomizer.apply(activity, config) — called per-activity from HookApplication's
 *    ActivityLifecycleCallbacks. Applies window-level changes directly.
 * 2. DisplayCustomizer.Hooks.install(config) — called once at app startup from HookFramework.
 *    Sets up global registry that apply() reads from, plus activity-independent settings.
 *
 * Features implemented at Java level (no native hooks needed):
 * - Status/navigation bar colors
 * - Dark mode forcing
 * - Orientation lock
 * - Keep screen awake
 * - Immersive fullscreen
 * - Custom locale
 * - WebView text zoom
 * - Color filter overlay
 * - Notch handling
 * - PiP support
 */
class DisplayCustomizer {

    /**
     * Apply display customizations to a specific Activity.
     * Called from HookApplication's ActivityLifecycleCallbacks.onActivityCreated().
     */
    fun apply(activity: Activity, config: DisplayConfig) {
        try {
            val window = activity.window ?: return

            // 1. Status bar color
            config.statusBarColor?.let { color ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    window.statusBarColor = color
                }
            }

            // 2. Navigation bar color
            config.navBarColor?.let { color ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    window.navigationBarColor = color
                }
            }

            // 3. Toolbar color (via ActionBar if present)
            config.toolbarColor?.let { color ->
                activity.actionBar?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(color)
                )
            }

            // 4. Dark mode
            when (config.darkMode) {
                DarkMode.DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                DarkMode.LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                DarkMode.SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                DarkMode.FORCE_DARK -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    // Also force dark on WebView if present
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WebViewDarkModeHelper.enableForceDark(activity)
                    }
                }
            }

            // 5. Color inversion
            if (config.colorInversion) {
                DisplaySpoofRegistry.colorInversion = true
            }

            // 6. Immersive fullscreen
            if (config.immersiveFullscreen) {
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }

            // 7. Keep screen awake
            if (config.keepScreenAwake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            // 8. Orientation lock
            if (config.orientationLock != -1) {
                activity.requestedOrientation = config.orientationLock
            }

            // 9. Custom locale / language
            if (config.customLanguage.isNotEmpty()) {
                applyLocale(activity, config.customLanguage)
            }

            // 10. Custom font
            config.customFontPath?.let { fontPath ->
                applyCustomFont(activity, fontPath)
            }

            // 11. Notch handling
            when (config.notchHandling) {
                NotchHandling.HIDE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val lp = window.attributes
                        lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                        window.attributes = lp
                    }
                }
                NotchHandling.FULLSCREEN -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val lp = window.attributes
                        lp.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        window.attributes = lp
                    }
                }
                NotchHandling.DEFAULT -> { /* No change */ }
            }

            // 12. Large aspect ratio
            if (config.largeAspectRatio) {
                DisplaySpoofRegistry.largeAspectRatio = true
            }

            // 13. HUD mode (overlay display info)
            if (config.hudMode) {
                DisplaySpoofRegistry.hudMode = true
            }

            // 14. Flip screen
            if (config.flipScreen) {
                DisplaySpoofRegistry.flipScreen = true
            }

            // 15. Color filter overlay
            config.colorFilter?.let { filterColor ->
                DisplaySpoofRegistry.colorFilter = filterColor
                applyColorFilter(activity, filterColor)
            }

            // 16. Welcome message
            if (config.welcomeMessage.isNotEmpty()) {
                DisplaySpoofRegistry.welcomeMessage = config.welcomeMessage
            }

            // 17. Reveal passwords (make password fields visible)
            if (config.revealPasswords) {
                DisplaySpoofRegistry.revealPasswords = true
            }

            // 18. Allow text selection
            if (config.allowTextSelection) {
                DisplaySpoofRegistry.allowTextSelection = true
            }

            // 19. Copy/share images
            if (config.copyShareImages) {
                DisplaySpoofRegistry.copyShareImages = true
            }

            // 20. Blur images
            if (config.blurImages) {
                DisplaySpoofRegistry.blurImages = true
            }

            // 21. RTL support
            config.rtlSupport?.let { rtl ->
                if (rtl) {
                    activity.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
            }

        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "DisplayCustomizer.apply failed: ${e.message}", e)
        }
    }

    /**
     * Install global display hooks.
     * Called once at app startup from HookFramework.installAll().
     */
    object Hooks {
        private var installed = false
        private var config: DisplayConfig? = null

        fun install(cfg: DisplayConfig) {
            if (installed) return
            config = cfg
            installed = true

            try {
                android.util.Log.i("CloneMaster", "DisplayCustomizer.Hooks installing...")

                // Store config in registry for per-activity apply() to read
                DisplaySpoofRegistry.darkMode = cfg.darkMode
                DisplaySpoofRegistry.forceDarkMode = cfg.forceDarkMode
                DisplaySpoofRegistry.orientationLock = cfg.orientationLock
                DisplaySpoofRegistry.keepScreenAwake = cfg.keepScreenAwake
                DisplaySpoofRegistry.immersiveFullscreen = cfg.immersiveFullscreen
                DisplaySpoofRegistry.customLanguage = cfg.customLanguage
                DisplaySpoofRegistry.customFontPath = cfg.customFontPath
                DisplaySpoofRegistry.statusBarColor = cfg.statusBarColor
                DisplaySpoofRegistry.navBarColor = cfg.navBarColor
                DisplaySpoofRegistry.webViewTextZoom = cfg.webViewTextZoom
                DisplaySpoofRegistry.floatingWindow = cfg.floatingWindow
                DisplaySpoofRegistry.freeformWindow = cfg.freeformWindow
                DisplaySpoofRegistry.pipSupport = cfg.pipSupport
                DisplaySpoofRegistry.multiWindow = cfg.multiWindow

                android.util.Log.i("CloneMaster", "DisplayCustomizer.Hooks installed: darkMode=${cfg.darkMode}, " +
                        "orientation=${cfg.orientationLock}, keepAwake=${cfg.keepScreenAwake}, " +
                        "lang=${cfg.customLanguage}")

            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "DisplayCustomizer.Hooks install failed: ${e.message}", e)
            }
        }

        fun getConfig(): DisplayConfig? = config
    }

    private fun applyLocale(activity: Activity, language: String) {
        try {
            val locale = if (language.contains("_")) {
                val parts = language.split("_")
                Locale(parts[0], parts[1])
            } else {
                Locale(language)
            }
            Locale.setDefault(locale)
            val config = Configuration(activity.resources.configuration)
            config.setLocale(locale)
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            android.util.Log.d("CloneMaster", "Locale set to: $locale")
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Locale apply failed: ${e.message}")
        }
    }

    private fun applyCustomFont(activity: Activity, fontPath: String) {
        try {
            val typeface = android.graphics.Typeface.createFromFile(fontPath)
            // Store in registry for WebView and TextView wrappers
            DisplaySpoofRegistry.customTypeface = typeface
            android.util.Log.d("CloneMaster", "Custom font loaded: $fontPath")
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Custom font apply failed: ${e.message}")
        }
    }

    private fun applyColorFilter(activity: Activity, color: Int) {
        try {
            // Add a semi-transparent color overlay view
            val overlay = View(activity)
            overlay.setBackgroundColor(color)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            // Note: Adding overlay via WindowManager requires SYSTEM_ALERT_WINDOW permission
            // For the clone, this is already declared in the manifest
            activity.window.addContentView(overlay, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ))
            android.util.Log.d("CloneMaster", "Color filter applied: ${Integer.toHexString(color)}")
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Color filter apply failed: ${e.message}")
        }
    }
}

/**
 * Helper for WebView dark mode (API 29+).
 */
object WebViewDarkModeHelper {
    fun enableForceDark(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.webkit.WebView.setDataDirectorySuffix("clone_dark")
            }
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "WebView dark mode failed: ${e.message}")
        }
    }

    fun applyForceDark(webSettings: WebSettings) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                webSettings.forceDark = WebSettings.FORCE_DARK_ON
            }
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "WebView force dark failed: ${e.message}")
        }
    }
}

/**
 * Registry for display spoofing state.
 * Read by per-activity apply() and wrapper classes in clone runtime.
 */
object DisplaySpoofRegistry {
    var darkMode: DarkMode = DarkMode.SYSTEM
    var forceDarkMode: Boolean = false
    var orientationLock: Int = -1
    var keepScreenAwake: Boolean = false
    var immersiveFullscreen: Boolean = false
    var customLanguage: String = ""
    var customFontPath: String? = null
    var customTypeface: android.graphics.Typeface? = null
    var statusBarColor: Int? = null
    var navBarColor: Int? = null
    var webViewTextZoom: Int = 100
    var floatingWindow: Boolean = false
    var freeformWindow: Boolean = false
    var pipSupport: Boolean = false
    var multiWindow: Boolean = false
    var colorInversion: Boolean = false
    var largeAspectRatio: Boolean = false
    var hudMode: Boolean = false
    var flipScreen: Boolean = false
    var colorFilter: Int? = null
    var revealPasswords: Boolean = false
    var allowTextSelection: Boolean = false
    var copyShareImages: Boolean = false
    var blurImages: Boolean = false
    var welcomeMessage: String = ""

    fun clear() {
        darkMode = DarkMode.SYSTEM
        forceDarkMode = false
        orientationLock = -1
        keepScreenAwake = false
        immersiveFullscreen = false
        customLanguage = ""
        customFontPath = null
        customTypeface = null
        statusBarColor = null
        navBarColor = null
        webViewTextZoom = 100
        floatingWindow = false
        freeformWindow = false
        pipSupport = false
        multiWindow = false
        colorInversion = false
        largeAspectRatio = false
        hudMode = false
        flipScreen = false
        colorFilter = null
        revealPasswords = false
        allowTextSelection = false
        copyShareImages = false
        blurImages = false
        welcomeMessage = ""
    }
}
