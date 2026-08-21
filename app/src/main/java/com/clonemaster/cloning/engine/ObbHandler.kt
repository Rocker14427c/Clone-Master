
package com.clonemaster.cloning.engine
import com.clonemaster.cloning.models.CloneConfig
import java.io.File
class ObbHandler {
    fun copyObb(originalPkg: String, clonePkg: String, diagnostics: CloningDiagnostics) {
        val src = File("/sdcard/Android/obb/$originalPkg")
        if (!src.exists()) return
        val dst = File("/sdcard/Android/obb/$clonePkg").apply { mkdirs() }
        src.listFiles()?.forEach { file ->
            try { file.copyTo(File(dst, file.name), overwrite=true); diagnostics.log("Copied OBB ${file.name}") } catch (e: Exception) { diagnostics.warn("OBB copy failed: ${e.message}") }
        }
    }
    fun bundleObb(originalPkg: String, decodedDir: File, diagnostics: CloningDiagnostics) {
        val src = File("/sdcard/Android/obb/$originalPkg")
        if (!src.exists()) return
        val assetsObb = File(decodedDir, "assets/obb").apply { mkdirs() }
        src.listFiles()?.forEach { file ->
            try { file.copyTo(File(assetsObb, file.name), overwrite=true) } catch (_: Exception) {}
        }
    }
}
