package com.clonemaster.tvwear

import android.content.Context
import com.clonemaster.cloning.models.TvWearConfig

class TvWearManager(private val context: Context) {

    object Hooks {
        private var installed = false
        fun install(cfg: TvWearConfig) {
            if (installed) return
            installed = true
            try {
                android.util.Log.i("CloneMaster", "TvWearManager.Hooks installing...")
                if (cfg.tvLauncher) { TvSpoofRegistry.tvLauncher = true; android.util.Log.i("CloneMaster", "TV launcher mode") }
                if (cfg.joystickPointer) { TvSpoofRegistry.joystickPointer = true; android.util.Log.i("CloneMaster", "Joystick pointer enabled") }
                if (cfg.pip) { TvSpoofRegistry.pip = true; android.util.Log.i("CloneMaster", "PiP enabled for TV/Wear") }
                if (cfg.removeWearComponents) { TvSpoofRegistry.removeWear = true; android.util.Log.i("CloneMaster", "Wear components removed") }
                if (cfg.watchVariant) { TvSpoofRegistry.watchVariant = true; android.util.Log.i("CloneMaster", "Watch variant enabled") }
                android.util.Log.i("CloneMaster", "TvWearManager.Hooks installed")
            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "TvWearManager.Hooks failed: ${e.message}", e)
            }
        }
    }
}

object TvSpoofRegistry {
    var tvLauncher: Boolean = false
    var joystickPointer: Boolean = false
    var pip: Boolean = false
    var removeWear: Boolean = false
    var watchVariant: Boolean = false
    fun clear() { tvLauncher = false; joystickPointer = false; pip = false; removeWear = false; watchVariant = false }
}
