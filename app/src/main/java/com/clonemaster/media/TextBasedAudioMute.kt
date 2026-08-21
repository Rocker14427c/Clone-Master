package com.clonemaster.media

import android.app.Activity
import android.view.ViewGroup
import android.widget.TextView
import com.clonemaster.cloning.models.MediaConfig
import com.clonemaster.viewmod.ViewInspector

/**
 * Independent implementation for "Mute while app in foreground or for text on screen"
 * Public feature reference: App Cloner lists "Mute while app in foreground or for text on screen" under Media options
 * Equivalent functionality: mute audio when specific text appears on screen, using view hierarchy inspection
 * Functional parity with independent implementation
 */
class TextBasedAudioMute {

    data class TextMuteConfig(
        var enabled: Boolean = false,
        var muteTriggers: MutableList<String> = mutableListOf(), // text that triggers mute, e.g., "Ad", "Advertisement"
        var muteDurationMs: Long = 5000,
        var muteOnAnyText: Boolean = false
    )

    private var isMuted = false

    fun startMonitoring(activity: Activity, config: TextMuteConfig, mediaConfig: MediaConfig) {
        if (!config.enabled) return

        val viewInspector = ViewInspector()

        // Monitor view hierarchy for text triggers
        val rootView = activity.window.decorView as ViewGroup
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                val hierarchy = viewInspector.dumpHierarchy(activity)
                val shouldMute = checkForMuteTrigger(hierarchy, config)
                if (shouldMute && !isMuted) {
                    muteAudio(activity)
                } else if (!shouldMute && isMuted) {
                    unmuteAudio(activity)
                }
            } catch (_: Exception) {}
        }
    }

    private fun checkForMuteTrigger(node: ViewInspector.ViewNode, config: TextMuteConfig): Boolean {
        if (config.muteOnAnyText && node.text.isNotEmpty()) return true
        if (config.muteTriggers.any { trigger -> node.text.contains(trigger, ignoreCase = true) || node.idName.contains(trigger, true) }) {
            return true
        }
        return node.children.any { checkForMuteTrigger(it, config) }
    }

    private fun muteAudio(activity: Activity) {
        try {
            val am = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamMute(android.media.AudioManager.STREAM_MUSIC, true)
            isMuted = true
        } catch (_: Exception) {}
    }

    private fun unmuteAudio(activity: Activity) {
        try {
            val am = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamMute(android.media.AudioManager.STREAM_MUSIC, false)
            isMuted = false
        } catch (_: Exception) {}
    }

    object Hooks {
        fun install(config: TextMuteConfig) {
            // Hook would be installed in clone – monitors view hierarchy via WindowManager
        }
    }
}
