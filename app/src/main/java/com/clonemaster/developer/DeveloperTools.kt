package com.clonemaster.developer

import android.content.Context
import com.clonemaster.cloning.models.DeveloperConfig
import java.io.File

class DeveloperTools(private val context: Context) {

    fun getLogcat(): String {
        return try {
            val proc = Runtime.getRuntime().exec("logcat -d -t 500")
            proc.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            "Logcat unavailable: ${e.message}"
        }
    }

    fun monitorFiles(path: String, callback: (String) -> Unit) {
        // Use FileObserver to monitor
        val observer = object : android.os.FileObserver(path, CREATE or MODIFY or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                callback("File event $event: $path")
            }
        }
        observer.startWatching()
    }

    fun monitorUrls(callback: (String) -> Unit) {
        // Hook URL.openConnection via Pine
    }

    object Hooks {
        fun install(config: DeveloperConfig) {
            if (config.fileMonitoring) {
                // Hook FileInputStream, FileOutputStream
            }
            if (config.urlMonitoring) {
                // Hook HttpURLConnection, OkHttp
            }
            if (config.httpHeaderMonitoring) {
                // Hook header adding
            }
            if (config.webViewInspection) {
                // Enable WebView debugging: WebView.setWebContentsDebuggingEnabled(true)
            }
            config.changeTargetSdk?.let { target ->
                // Hook ApplicationInfo.targetSdkVersion
            }
            if (config.hideDevMode) {
                // Hook Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
            }
            // Native hooks via Pine/ByteHook
            if (config.nativeHooksEnabled && !config.safeMode) {
                // Init Pine, ByteHook
            }
        }
    }
}

class WebViewToolkit {

    data class WebViewInfo(
        val url: String,
        val title: String,
        val userAgent: String,
        val source: String
    )

    fun inspect(webView: android.webkit.WebView): WebViewInfo {
        return WebViewInfo(
            url = webView.url ?: "",
            title = webView.title ?: "",
            userAgent = webView.settings.userAgentString,
            source = "" // would need JS injection to get source
        )
    }

    fun injectJs(webView: android.webkit.WebView, js: String) {
        webView.evaluateJavascript(js, null)
    }

    fun overrideNavigation(webView: android.webkit.WebView, overrides: Map<String, String>) {
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                overrides.forEach { (pattern, action) ->
                    if (url.contains(pattern)) {
                        when (action) {
                            "block" -> return true
                            "allow" -> return false
                            else -> {
                                // Custom handling
                            }
                        }
                    }
                }
                return false
            }
        }
    }

    fun persistentRules(context: Context): File {
        return File(context.filesDir, "webview_rules.json")
    }
}

class LogcatViewerActivity : android.app.Activity() {
    // Simple logcat viewer UI
}
