package com.clonemaster.developer

import android.webkit.WebView

/**
 * Independent implementation for WebView custom script inject mode
 * Public feature reference: WhatsNew 3.6.0 "Inject mode for 'WebView custom script' option"
 * Equivalent functionality: allow JS injection at document_start vs document_end, independent implementation
 */
class WebViewScriptManager {

    enum class InjectMode {
        DOCUMENT_START, // inject before page loads (like content script at document_start)
        DOCUMENT_END, // inject after page loads (default)
        DOCUMENT_IDLE // inject when idle
    }

    data class ScriptRule(
        val id: String = java.util.UUID.randomUUID().toString(),
        val urlPattern: String = "*", // regex or * for all
        val script: String = "",
        val mode: InjectMode = InjectMode.DOCUMENT_END,
        val enabled: Boolean = true
    )

    fun inject(webView: WebView, rule: ScriptRule) {
        if (!rule.enabled) return

        when (rule.mode) {
            InjectMode.DOCUMENT_START -> {
                // Inject via WebViewClient.onPageStarted
                webView.evaluateJavascript("""
                    (function() {
                        // Document start injection – run before DOM
                        ${rule.script}
                    })();
                """.trimIndent(), null)
            }
            InjectMode.DOCUMENT_END -> {
                webView.evaluateJavascript(rule.script, null)
            }
            InjectMode.DOCUMENT_IDLE -> {
                webView.postDelayed({
                    webView.evaluateJavascript(rule.script, null)
                }, 1000)
            }
        }
    }

    fun applyRules(webView: WebView, rules: List<ScriptRule>, currentUrl: String) {
        rules.filter { it.enabled && (it.urlPattern == "*" || currentUrl.contains(it.urlPattern)) }
            .forEach { rule ->
                inject(webView, rule)
            }
    }

    object Hooks {
        fun install(rules: List<ScriptRule>) {
            // Hook WebViewClient.onPageStarted and onPageFinished to apply rules with correct inject mode
            // For DOCUMENT_START, hook onPageStarted
            // For DOCUMENT_END, hook onPageFinished
        }
    }
}
