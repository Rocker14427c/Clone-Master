package com.clonemaster.display

import android.app.Activity
import android.os.Build
import com.clonemaster.cloning.models.DisplayConfig

class DisplayCustomizer {

    fun apply(activity: Activity, config: DisplayConfig) {
        config.statusBarColor?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activity.window.statusBarColor = it
            }
        }
        config.navBarColor?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activity.window.navigationBarColor = it
            }
        }
        if (config.immersiveFullscreen) {
            activity.window.decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        if (config.keepScreenAwake) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (config.orientationLock != -1) {
            activity.requestedOrientation = config.orientationLock
        }
        // Dark mode, font, language, etc handled via hooks
    }

    object Hooks {
        fun install(config: DisplayConfig) {
            // Hook Resources.getConfiguration for locale
            // Hook Typeface for font
            // Hook WebSettings.setTextZoom for webview zoom
            // Hook AppCompatDelegate for dark mode
        }
    }
}
