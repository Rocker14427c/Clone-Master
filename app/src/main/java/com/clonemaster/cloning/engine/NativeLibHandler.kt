package com.clonemaster.cloning.engine

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Native library handling – preserve ABIs, inject hook libs from bundled assets.
 *
 * Uses the following assets bundled in the APK (copied from Next-Cloner reference):
 *   - jniLibs/{abi}/libappcloner.so  — core hooking library
 *   - jniLibs/{abi}/libpdnsd.so      — DNS proxy
 *   - jniLibs/{abi}/libtun2socks.so  — VPN tunnel to SOCKS
 *   - jniLibs/{abi}/libzstd-jni.so   — Zstandard compression
 *   - assets/libAppCloner.zip        — Hook library (extracted per ABI)
 *   - assets/libPine.zip             — ART inline hook (Android 9+)
 *   - assets/libByteHook.zip         — PLT hook for native functions
 *   - assets/libAndHook.zip          — Legacy hook
 *   - assets/libAliuHook.zip         — Alternative hook
 */
class NativeLibHandler(private val context: Context? = null) {

    companion object {
        /** Hook libraries available as zip assets (contain per-ABI subdirs). */
        val HOOK_LIB_ZIPS = listOf(
            "libAppCloner.zip",
            "libPine.zip",
            "libByteHook.zip",
            "libAndHook.zip",
            "libAliuHook.zip"
        )

        /** Native libs bundled directly in jniLibs (loaded by Clone-Master itself). */
        val BUNDLED_SO_LIBS = listOf(
            "libappcloner.so",
            "libpdnsd.so",
            "libtun2socks.so",
            "libzstd-jni.so"
        )
    }

    fun handle(libDir: File, config: com.clonemaster.cloning.models.CloneConfig, diagnostics: CloningDiagnostics) {
        if (!libDir.exists()) {
            diagnostics.log("No lib/ directory – app has no native libs, creating for hook injection")
            libDir.mkdirs()
        }

        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").map { File(libDir, it) }
        val existingAbis = libDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
        diagnostics.log("Found ABIs: ${existingAbis.map { it.name }}")

        // Process each existing ABI dir
        existingAbis.forEach { abiDir ->
            val abiName = abiDir.name
            diagnostics.log("Processing ABI: $abiName")

            // Validate existing .so files are not corrupted (size > 0)
            abiDir.listFiles { f -> f.extension == "so" }?.forEach { soFile ->
                if (soFile.length() == 0L) {
                    diagnostics.error("Corrupted native lib: ${soFile.name} in $abiName is 0 bytes – removing")
                    soFile.delete()
                }
            }
        }

        // Inject hook libraries from bundled assets
        if (context != null) {
            injectHookLibsFromAssets(libDir, diagnostics)
        } else {
            diagnostics.warn("No context – cannot inject hook libs from assets (apktool path)")
        }

        // ABI compatibility check
        val has64Bit = existingAbis.any { it.name == "arm64-v8a" || it.name == "x86_64" }
        val has32BitOnly = existingAbis.isNotEmpty() && existingAbis.all {
            it.name == "armeabi-v7a" || it.name == "armeabi" || it.name == "x86"
        }
        if (has32BitOnly && !has64Bit) {
            diagnostics.warn("App has only 32-bit native libs – may fail on 64-bit-only devices")
        }
    }

    /**
     * Extracts hook libraries from bundled zip assets and places them into
     * the clone's lib/{abi}/ directory. Each zip contains per-ABI subdirs:
     *   libAppCloner.zip -> arm64-v8a/libappcloner.so, armeabi-v7a/libappcloner.so, ...
     */
    private fun injectHookLibsFromAssets(libDir: File, diagnostics: CloningDiagnostics) {
        val assetManager = context?.assets ?: return

        HOOK_LIB_ZIPS.forEach { zipName ->
            try {
                val inputStream = assetManager.open(zipName)
                val zipStream = ZipInputStream(inputStream)
                var entry = zipStream.nextEntry
                var extractedCount = 0

                while (entry != null) {
                    val entryName = entry.name
                    // Zip contains entries like: arm64-v8a/libappcloner.so
                    if (!entry.isDirectory && entryName.contains("/")) {
                        val parts = entryName.split("/")
                        if (parts.size >= 2) {
                            val abi = parts[0]
                            val soName = parts.last()
                            val targetAbiDir = File(libDir, abi)

                            // Only inject into ABIs that the source APK actually supports
                            if (targetAbiDir.exists() || !libDir.listFiles().isNullOrEmpty()) {
                                targetAbiDir.mkdirs()
                                val targetFile = File(targetAbiDir, soName)
                                if (!targetFile.exists()) {
                                    targetFile.outputStream().use { out ->
                                        zipStream.copyTo(out)
                                    }
                                    extractedCount++
                                    diagnostics.log("Injected hook lib: $abi/$soName (${targetFile.length()} bytes) from $zipName")
                                }
                            }
                        }
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
                zipStream.close()
                if (extractedCount == 0) {
                    diagnostics.log("No hook libs extracted from $zipName (already present or no matching ABIs)")
                }
            } catch (e: Exception) {
                diagnostics.warn("Failed to inject hook libs from $zipName: ${e.message}")
            }
        }
    }

    /**
     * Returns the path to microsocks binary for the given ABI, bundled in assets.
     * Used by ProxyManager to start per-clone SOCKS proxy.
     */
    fun getMicrosocksPath(abi: String): String? {
        return try {
            context?.assets?.open("microsocks/$abi/microsocks")?.close()
            // The binary is in assets, must be extracted to files dir at runtime
            "microsocks/$abi/microsocks"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts a native binary from assets to the app's files directory.
     * Returns the extracted file path, or null on failure.
     */
    fun extractNativeBinary(assetPath: String, destName: String): File? {
        val ctx = context ?: return null
        return try {
            val destFile = File(ctx.filesDir, destName)
            if (destFile.exists() && destFile.length() > 0) return destFile

            ctx.assets.open(assetPath).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile.setExecutable(true, false)
            destFile.setReadable(true, false)
            destFile
        } catch (e: Exception) {
            null
        }
    }
}
