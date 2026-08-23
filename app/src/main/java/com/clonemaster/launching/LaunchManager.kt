package com.clonemaster.launching

import android.content.Context
import android.content.Intent
import com.clonemaster.cloning.models.LaunchingConfig
import com.clonemaster.cloning.models.StartEvent

class LaunchManager(private val context: Context) {

    fun handleSecretDialerCode(code: String, config: LaunchingConfig): Boolean {
        return config.secretDialerCode.isNotEmpty() && code == config.secretDialerCode
    }

    fun launchClone(clonePackage: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(clonePackage)
        launchIntent?.let { context.startActivity(it) }
    }

    fun registerEventTriggers(config: LaunchingConfig) {
        config.startOnEvents.forEach { event ->
            when (event) {
                StartEvent.SPEN -> { /* register S Pen listener */ }
                StartEvent.HEADPHONES -> { /* register headset plug receiver */ }
                StartEvent.POWER_CONNECTED -> { /* register power connected */ }
                StartEvent.NFC -> { /* register NFC tag */ }
                else -> {}
            }
        }
    }

    object Hooks {
        private var installed = false
        private var config: LaunchingConfig? = null

        fun install(cfg: LaunchingConfig) {
            if (installed) return
            config = cfg
            installed = true

            try {
                android.util.Log.i("CloneMaster", "LaunchManager.Hooks installing...")

                if (cfg.removeLauncherIcon) {
                    LaunchSpoofRegistry.removeLauncherIcon = true
                    android.util.Log.i("CloneMaster", "Launcher icon removed (manifest: activity-alias disabled)")
                }

                if (cfg.removeWidgets) {
                    LaunchSpoofRegistry.removeWidgets = true
                    android.util.Log.i("CloneMaster", "Widgets removed")
                }

                if (cfg.disableAutoStart) {
                    LaunchSpoofRegistry.disableAutoStart = true
                    android.util.Log.i("CloneMaster", "Auto-start disabled")
                }

                if (cfg.persistentMode) {
                    LaunchSpoofRegistry.persistentMode = true
                    android.util.Log.i("CloneMaster", "Persistent mode enabled")
                }

                if (cfg.disableBackgroundServices) {
                    LaunchSpoofRegistry.disableBackgroundServices = true
                    android.util.Log.i("CloneMaster", "Background services disabled")
                }

                if (cfg.quickTile) {
                    LaunchSpoofRegistry.quickTile = true
                    android.util.Log.i("CloneMaster", "Quick tile enabled")
                }

                if (cfg.disableWakeLocks) {
                    LaunchSpoofRegistry.disableWakeLocks = true
                    android.util.Log.i("CloneMaster", "Wake locks disabled")
                }

                cfg.fakeBatteryLevel?.let { level ->
                    LaunchSpoofRegistry.fakeBatteryLevel = level
                    android.util.Log.i("CloneMaster", "Battery level spoofed: $level%")
                }

                if (cfg.setAsHome) {
                    LaunchSpoofRegistry.setAsHome = true
                    android.util.Log.i("CloneMaster", "Set as home launcher")
                }

                if (cfg.setAsCamera) {
                    LaunchSpoofRegistry.setAsCamera = true
                    android.util.Log.i("CloneMaster", "Set as camera app")
                }

                if (cfg.setAsAssistant) {
                    LaunchSpoofRegistry.setAsAssistant = true
                    android.util.Log.i("CloneMaster", "Set as assistant")
                }

                if (cfg.secretDialerCode.isNotEmpty()) {
                    LaunchSpoofRegistry.secretDialerCode = cfg.secretDialerCode
                    android.util.Log.i("CloneMaster", "Secret dialer code set: ***${cfg.secretDialerCode.takeLast(2)}")
                }

                if (cfg.startOnEvents.isNotEmpty()) {
                    LaunchSpoofRegistry.startOnEvents = cfg.startOnEvents.toList()
                    android.util.Log.i("CloneMaster", "Start on events: ${cfg.startOnEvents.joinToString(", ")}")
                }

                android.util.Log.i("CloneMaster", "LaunchManager.Hooks installed successfully")
            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "LaunchManager.Hooks install failed: ${e.message}", e)
            }
        }

        fun shouldDisableWakeLocks(): Boolean = config?.disableWakeLocks == true
        fun getFakeBatteryLevel(): Int? = config?.fakeBatteryLevel
        fun shouldDisableAutoStart(): Boolean = config?.disableAutoStart == true
    }
}

class DialerLaunchReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val code = intent?.data?.host ?: return
        // Lookup config and launch clone
    }
}

class PersistentCloneService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?) = null
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("CloneMaster", "PersistentCloneService started")
        return START_STICKY
    }
}

object LaunchSpoofRegistry {
    var removeLauncherIcon: Boolean = false
    var removeWidgets: Boolean = false
    var disableAutoStart: Boolean = false
    var persistentMode: Boolean = false
    var disableBackgroundServices: Boolean = false
    var quickTile: Boolean = false
    var disableWakeLocks: Boolean = false
    var fakeBatteryLevel: Int? = null
    var setAsHome: Boolean = false
    var setAsCamera: Boolean = false
    var setAsAssistant: Boolean = false
    var secretDialerCode: String = ""
    var startOnEvents: List<StartEvent> = emptyList()
    fun clear() {
        removeLauncherIcon = false; removeWidgets = false; disableAutoStart = false
        persistentMode = false; disableBackgroundServices = false; quickTile = false
        disableWakeLocks = false; fakeBatteryLevel = null; setAsHome = false
        setAsCamera = false; setAsAssistant = false; secretDialerCode = ""
        startOnEvents = emptyList()
    }
}
