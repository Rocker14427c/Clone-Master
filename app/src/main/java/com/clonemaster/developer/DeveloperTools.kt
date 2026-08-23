package com.clonemaster.developer

import android.content.Context
import com.clonemaster.cloning.models.DeveloperConfig

class DeveloperTools(private val context: Context) {

    object Hooks {
        private var installed = false
        fun install(cfg: DeveloperConfig) {
            if (installed) return
            installed = true
            try {
                android.util.Log.i("CloneMaster", "DeveloperTools.Hooks installing...")
                if (cfg.logcatViewer) { DevSpoofRegistry.logcatViewer = true; android.util.Log.i("CloneMaster", "Logcat viewer enabled") }
                if (cfg.hideDevMode) { DevSpoofRegistry.hideDevMode = true; android.util.Log.i("CloneMaster", "Dev mode hidden") }
                cfg.changeTargetSdk?.let {
                    DevSpoofRegistry.changeTargetSdk = it
                    android.util.Log.i("CloneMaster", "Target SDK spoofed: $it")
                }
                if (cfg.customBuildProps.isNotEmpty()) {
                    DevSpoofRegistry.customBuildProps = cfg.customBuildProps.toMap()
                    android.util.Log.i("CloneMaster", "Custom build props: ${cfg.customBuildProps.size}")
                }
                if (cfg.fileMonitoring) { DevSpoofRegistry.fileMonitoring = true; android.util.Log.i("CloneMaster", "File monitoring enabled") }
                if (cfg.urlMonitoring) { DevSpoofRegistry.urlMonitoring = true; android.util.Log.i("CloneMaster", "URL monitoring enabled") }
                if (cfg.httpHeaderMonitoring) { DevSpoofRegistry.httpHeaderMonitoring = true; android.util.Log.i("CloneMaster", "HTTP header monitoring enabled") }
                if (cfg.webViewInspection) { DevSpoofRegistry.webViewInspection = true; android.util.Log.i("CloneMaster", "WebView inspection enabled") }
                if (cfg.webViewJsInjection.isNotEmpty()) {
                    DevSpoofRegistry.webViewJsInjection = cfg.webViewJsInjection.toList()
                    android.util.Log.i("CloneMaster", "WebView JS injection: ${cfg.webViewJsInjection.size} scripts")
                }
                if (cfg.nativeHooksEnabled) { DevSpoofRegistry.nativeHooksEnabled = true; android.util.Log.i("CloneMaster", "Native hooks enabled") }
                if (cfg.safeMode) { DevSpoofRegistry.safeMode = true; android.util.Log.i("CloneMaster", "Safe mode enabled") }
                android.util.Log.i("CloneMaster", "DeveloperTools.Hooks installed")
            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "DeveloperTools.Hooks failed: ${e.message}", e)
            }
        }
    }
}

object HookOptionsManager {
    data class HookOptions(val nativeHooksEnabled: Boolean = false, val disableHooks: Boolean = false, val safeMode: Boolean = false)
    object Hooks {
        fun install(config: HookOptions) {
            android.util.Log.i("CloneMaster", "HookOptions: native=${config.nativeHooksEnabled}, disable=${config.disableHooks}, safe=${config.safeMode}")
        }
    }
}

object WebViewScriptManager {
    fun injectScripts(scripts: List<String>, webView: android.webkit.WebView) {
        scripts.forEach { script ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(script, null)
            }
        }
    }
}

object DevSpoofRegistry {
    var logcatViewer: Boolean = false
    var hideDevMode: Boolean = false
    var changeTargetSdk: Int? = null
    var customBuildProps: Map<String, String> = emptyMap()
    var fileMonitoring: Boolean = false
    var urlMonitoring: Boolean = false
    var httpHeaderMonitoring: Boolean = false
    var webViewInspection: Boolean = false
    var webViewJsInjection: List<String> = emptyList()
    var nativeHooksEnabled: Boolean = false
    var safeMode: Boolean = false
    fun clear() {
        logcatViewer = false; hideDevMode = false; changeTargetSdk = null
        customBuildProps = emptyMap(); fileMonitoring = false; urlMonitoring = false
        httpHeaderMonitoring = false; webViewInspection = false; webViewJsInjection = emptyList()
        nativeHooksEnabled = false; safeMode = false
    }
}
