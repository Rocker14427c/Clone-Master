package com.clonemaster.display

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Independent implementation for improved Change locale option
 * Public feature reference: WhatsNew 3.6.7 "Improved 'Change locale' option"
 * Equivalent functionality: per-clone locale spoofing with Android 13+ per-app locale support, independent implementation
 */
class LocaleManager {

    data class LocaleConfig(
        var customLocale: String = "", // e.g., "fr", "de", "ja", "en_US"
        var customLanguage: String = "", // legacy field from DisplayConfig
        var usePerAppLocale: Boolean = true, // Android 13+ per-app locale
        var overrideSystemLocale: Boolean = false
    )

    fun applyLocale(context: Context, config: LocaleConfig): Context {
        if (config.customLocale.isEmpty() && config.customLanguage.isEmpty()) return context

        val localeStr = if (config.customLocale.isNotEmpty()) config.customLocale else config.customLanguage
        val locale = parseLocale(localeStr)

        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && config.usePerAppLocale) {
            // Android 13+ per-app locale – use LocaleManager.setApplicationLocales()
            try {
                val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as android.app.LocaleManager
                localeManager.applicationLocales = LocaleList(locale)
            } catch (_: Exception) {}
            // Also update configuration for compatibility
            configuration.setLocale(locale)
            context.createConfigurationContext(configuration)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
            configuration.setLocales(LocaleList(locale))
            context.createConfigurationContext(configuration)
        } else {
            configuration.locale = locale
            context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
            context
        }
    }

    private fun parseLocale(localeStr: String): Locale {
        return try {
            if (localeStr.contains("_")) {
                val parts = localeStr.split("_")
                Locale(parts[0], parts[1])
            } else if (localeStr.contains("-")) {
                Locale.forLanguageTag(localeStr)
            } else {
                Locale(localeStr)
            }
        } catch (_: Exception) {
            Locale(localeStr)
        }
    }

    object Hooks {
        fun install(config: LocaleConfig) {
            if (config.customLocale.isEmpty() && config.customLanguage.isEmpty()) return

            // Hook Resources.getConfiguration() to return spoofed locale
            // Hook Locale.getDefault() to return custom locale
            // Hook for WebView: WebView uses Accept-Language header – hook via WebSettings

            // Compatibility: Some apps cache locale at startup – need to hook early in Application.attachBaseContext
        }
    }
}
