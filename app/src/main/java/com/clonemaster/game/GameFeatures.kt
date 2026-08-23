package com.clonemaster.game

import android.content.Context
import com.clonemaster.cloning.models.GameConfig
import java.io.File

class GameFeatures(private val context: Context) {

    fun handleObb(originalPackage: String, clonePackage: String, config: GameConfig) {
        val obbDir = File("/sdcard/Android/obb/$originalPackage")
        if (!obbDir.exists()) return
        if (config.copyObb) {
            val dest = File("/sdcard/Android/obb/$clonePackage")
            dest.mkdirs()
            obbDir.listFiles()?.forEach { f ->
                try { f.copyTo(File(dest, f.name), overwrite = true) } catch (ignored: Exception) {}
            }
        }
    }

    fun startFpsMonitor() {
        // Use Choreographer to calculate FPS + overlay TextView via WindowManager
    }

    fun initKeyMapper(mappings: Map<String, String>) {
        // Key mapper: overlay buttons that inject MotionEvent/KeyEvent
    }

    object Hooks {
        private var installed = false
        fun install(cfg: GameConfig) {
            if (installed) return
            installed = true
            try {
                android.util.Log.i("CloneMaster", "GameFeatures.Hooks installing...")
                if (cfg.bundleObb || cfg.copyObb || cfg.supportObb) {
                    GameSpoofRegistry.obbEnabled = true
                    android.util.Log.i("CloneMaster", "OBB support enabled")
                }
                if (cfg.keyMapperEnabled) {
                    GameSpoofRegistry.keyMapperEnabled = true
                    GameSpoofRegistry.keyMappings = cfg.keyMappings.toMap()
                    android.util.Log.i("CloneMaster", "Key mapper enabled: ${cfg.keyMappings.size} mappings")
                }
                if (cfg.fpsMonitor) {
                    GameSpoofRegistry.fpsMonitor = true
                    android.util.Log.i("CloneMaster", "FPS monitor enabled")
                }
                android.util.Log.i("CloneMaster", "GameFeatures.Hooks installed")
            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "GameFeatures.Hooks failed: ${e.message}", e)
            }
        }
    }
}

object GameSpoofRegistry {
    var obbEnabled: Boolean = false
    var keyMapperEnabled: Boolean = false
    var keyMappings: Map<String, String> = emptyMap()
    var fpsMonitor: Boolean = false
    fun clear() { obbEnabled = false; keyMapperEnabled = false; keyMappings = emptyMap(); fpsMonitor = false }
}
