package com.clonemaster.automation

import android.content.Context
import android.provider.Settings
import com.clonemaster.cloning.models.AutomationConfig
import com.clonemaster.cloning.models.SequencedAction

class AutomationEngine(private val context: Context) {

    fun executeOnStart(config: AutomationConfig) {
        config.brightnessOnStart?.let { brightness ->
            try {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
                android.util.Log.i("CloneMaster", "Brightness set to $brightness")
            } catch (ignored: Exception) {}
        }
        config.autoRotateToggle?.let { enabled ->
            try {
                Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (enabled) 1 else 0)
                android.util.Log.i("CloneMaster", "Auto-rotate set to $enabled")
            } catch (ignored: Exception) {}
        }
        if (config.clipboardOnStart.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("auto", config.clipboardOnStart))
            android.util.Log.i("CloneMaster", "Clipboard set on start")
        }
    }

    object Hooks {
        private var installed = false
        fun install(cfg: AutomationConfig) {
            if (installed) return
            installed = true
            try {
                android.util.Log.i("CloneMaster", "AutomationEngine.Hooks installing...")
                cfg.brightnessOnStart?.let {
                    AutoSpoofRegistry.brightnessOnStart = it
                    android.util.Log.i("CloneMaster", "Brightness on start: $it")
                }
                cfg.dndToggle?.let {
                    AutoSpoofRegistry.dndToggle = it
                    android.util.Log.i("CloneMaster", "DND toggle: $it")
                }
                if (cfg.apiAutomation) {
                    AutoSpoofRegistry.apiAutomation = true
                    android.util.Log.i("CloneMaster", "API automation enabled")
                }
                if (cfg.autoScroll) {
                    AutoSpoofRegistry.autoScroll = true
                    AutoSpoofRegistry.autoScrollInterval = cfg.autoScrollInterval
                    android.util.Log.i("CloneMaster", "Auto-scroll enabled (${cfg.autoScrollInterval}ms)")
                }
                if (cfg.flashlightWhileOpen) {
                    AutoSpoofRegistry.flashlightWhileOpen = true
                    android.util.Log.i("CloneMaster", "Flashlight while open")
                }
                if (cfg.autoPressButtons.isNotEmpty()) {
                    AutoSpoofRegistry.autoPressButtons = cfg.autoPressButtons.toList()
                    android.util.Log.i("CloneMaster", "Auto-press buttons: ${cfg.autoPressButtons.size} rules")
                }
                if (cfg.eventTriggers.isNotEmpty()) {
                    AutoSpoofRegistry.eventTriggers = cfg.eventTriggers.toList()
                    android.util.Log.i("CloneMaster", "Event triggers: ${cfg.eventTriggers.size}")
                }
                if (cfg.sequencedActions.isNotEmpty()) {
                    AutoSpoofRegistry.sequencedActions = cfg.sequencedActions.toList()
                    android.util.Log.i("CloneMaster", "Sequenced actions: ${cfg.sequencedActions.size}")
                }
                android.util.Log.i("CloneMaster", "AutomationEngine.Hooks installed")
            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "AutomationEngine.Hooks failed: ${e.message}", e)
            }
        }
    }
}

class SequencedActionExecutor {
    fun execute(actions: List<SequencedAction>, context: Context) {
        actions.sortedBy { it.order }.forEach { action ->
            android.util.Log.d("CloneMaster", "Executing action ${action.order}: ${action.action}")
        }
    }
}

object AutoSpoofRegistry {
    var brightnessOnStart: Int? = null
    var dndToggle: Boolean? = null
    var apiAutomation: Boolean = false
    var autoScroll: Boolean = false
    var autoScrollInterval: Long = 2000
    var flashlightWhileOpen: Boolean = false
    var autoPressButtons: List<com.clonemaster.cloning.models.AutoPressRule> = emptyList()
    var eventTriggers: List<com.clonemaster.cloning.models.EventTrigger> = emptyList()
    var sequencedActions: List<SequencedAction> = emptyList()
    fun clear() {
        brightnessOnStart = null; dndToggle = null; apiAutomation = false
        autoScroll = false; autoScrollInterval = 2000; flashlightWhileOpen = false
        autoPressButtons = emptyList(); eventTriggers = emptyList(); sequencedActions = emptyList()
    }
}
