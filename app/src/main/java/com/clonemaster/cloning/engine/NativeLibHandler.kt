package com.clonemaster.cloning.engine

import java.io.File

/**
 * Native library handling – preserve ABIs, inject libappcloner.so
 */
class NativeLibHandler {

    fun handle(libDir: File, config: com.clonemaster.cloning.models.CloneConfig, diagnostics: CloningDiagnostics) {
        if (!libDir.exists()) {
            diagnostics.log("No lib/ directory")
            return
        }

        val abis = libDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        diagnostics.log("Found ABIs: ${abis.map { it.name }}")

        // Inject libappcloner.so placeholder if not exists
        abis.forEach { abiDir ->
            val targetSo = File(abiDir, "libappcloner.so")
            if (!targetSo.exists()) {
                // In real build, copy from assets/libAppCloner.zip (from Next-Cloner reference)
                // For now create placeholder
                targetSo.writeBytes(ByteArray(0))
                diagnostics.log("Injected placeholder libappcloner.so for ${abiDir.name}")
            }

            // Also ensure other hook libs if needed: libPine, libByteHook, etc
            // Copy from reference if available
            val refLibs = listOf("libPine.so", "libByteHook.so", "libAndHook.so")
            refLibs.forEach { libName ->
                val f = File(abiDir, libName)
                if (!f.exists()) {
                    // placeholder
                    diagnostics.debug("Would inject $libName for ${abiDir.name}")
                }
            }
        }
    }
}
