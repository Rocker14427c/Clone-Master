package com.clonemaster.cloning.engine

import android.content.Context
import com.clonemaster.cloning.models.AppInfo
import com.clonemaster.cloning.models.CloneConfig
import com.clonemaster.cloning.models.CompatibilityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Main cloning engine – orchestrates entire pipeline.
 * This is the most important component per requirement 20.
 */
class CloneEngine(private val context: Context) {

    private val parser = ApkParser(context)
    private val compatibilityAnalyzer = CompatibilityAnalyzer()
    private val manifestTransformer = ManifestTransformer()
    private val resourceTransformer = ResourceTransformer()
    private val dexTransformer = DexTransformer()
    private val nativeHandler = NativeLibHandler()
    private val signingPipeline = SigningPipeline()
    private val diagnostics = CloningDiagnostics()

    suspend fun analyze(packageName: String): Pair<AppInfo, CompatibilityReport> = withContext(Dispatchers.IO) {
        val appInfo = parser.parseInstalled(packageName)
        val report = compatibilityAnalyzer.analyze(appInfo)
        appInfo to report
    }

    /**
     * Full clone – steps:
     * 1. Extract APK via apktool d
     * 2. Transform manifest, resources, dex, libs
     * 3. Bundle config, OBB, external data
     * 4. Build via apktool b
     * 5. Sign
     */
    suspend fun clone(config: CloneConfig, onProgress: (String) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        try {
            diagnostics.clear()
            onProgress("Starting clone for ${config.originalPackage} -> ${config.clonePackage}")

            // Step 0: Prepare temp dirs
            val workDir = File(context.cacheDir, "clone_${System.currentTimeMillis()}")
            workDir.mkdirs()
            val decodedDir = File(workDir, "decoded")
            val buildDir = File(workDir, "build")
            buildDir.mkdirs()

            // Step 1: apktool decode
            onProgress("Decoding APK...")
            val apkPath = getApkPath(config.originalPackage)
            if (!apkPath.exists()) throw IllegalStateException("APK not found: ${config.originalPackage}")

            val apktool = findApktool()
            if (apktool != null) {
                val proc = ProcessBuilder(apktool, "d", "-f", apkPath.absolutePath, "-o", decodedDir.absolutePath)
                    .redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                diagnostics.log("apktool decode: $out")
                if (!decodedDir.exists()) throw IllegalStateException("apktool decode failed: $out")
            } else {
                // Fallback: unzip
                diagnostics.warn("apktool not found, using unzip fallback")
                unzipApk(apkPath, decodedDir)
            }

            // Step 2: Manifest transform
            onProgress("Transforming manifest...")
            val manifestFile = File(decodedDir, "AndroidManifest.xml")
            if (!manifestFile.exists()) throw IllegalStateException("AndroidManifest.xml not found after decode")
            val manifestResult = manifestTransformer.transform(manifestFile, config)
            diagnostics.log("New package: ${manifestResult.newPackage}, authorities: ${manifestResult.authorityMap}")

            // Step 3: Resource transform
            onProgress("Transforming resources...")
            val resDir = File(decodedDir, "res")
            if (resDir.exists()) resourceTransformer.transform(resDir, config, diagnostics)

            // Step 4: Dex / smali transform
            onProgress("Transforming DEX...")
            dexTransformer.transform(decodedDir, config, manifestResult.authorityMap, diagnostics)

            // Step 5: Native libs
            onProgress("Handling native libs...")
            val libDir = File(decodedDir, "lib")
            nativeHandler.handle(libDir, config, diagnostics)

            // Step 6: Bundle clone_config.json + environment + device profile into assets
            onProgress("Bundling config & environment...")
            val assetsDir = File(decodedDir, "assets")
            assetsDir.mkdirs()
            val configJson = gsonConfig(config)
            File(assetsDir, "clone_config.json").writeText(configJson)
            File(assetsDir, "clone_identity.json").writeText(gsonIdentity(config))
            File(assetsDir, "environment_config.json").writeText(gsonEnvironment(config))

            // Bundle coherent physical device profile
            try {
                val envManager = com.clonemaster.environment.EnvironmentManager(context)
                val profile = envManager.getDeviceProfile(config.environment.physicalDeviceProfileId)
                val hooksConfig = envManager.generateHooksConfig(config.environment)
                File(assetsDir, "device_profile.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(profile))
                File(assetsDir, "environment_hooks.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(hooksConfig))
                // Also bundle as device_profile.json for HookFramework
                diagnostics.log("Bundled device profile ${profile.id} with ${hooksConfig.systemProps.size} spoofed props and ${hooksConfig.hidePaths.size} hidden paths")
            } catch (e: Exception) {
                diagnostics.warn("Failed to bundle device profile: ${e.message}")
            }

            // Step 7: OBB handling
            if (config.includeObb || config.game.bundleObb) {
                handleObb(config, decodedDir)
            }

            // Step 8: apktool build
            onProgress("Building APK...")
            val unsignedApk = File(buildDir, "unsigned.apk")
            if (apktool != null) {
                val proc = ProcessBuilder(apktool, "b", decodedDir.absolutePath, "-o", unsignedApk.absolutePath)
                    .redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                diagnostics.log("apktool build: $out")
                if (!unsignedApk.exists()) throw IllegalStateException("apktool build failed: $out")
            } else {
                // fallback zip
                zipDir(decodedDir, unsignedApk)
            }

            // Step 9: Signing
            onProgress("Signing APK...")
            val signedDir = File(context.filesDir, "signed")
            val keystore = getOrCreateKeystore()
            val signResult = signingPipeline.sign(unsignedApk, signedDir, keystore)
            diagnostics.log("Signed APK: ${signResult.signedApk.absolutePath}, verified=${signResult.verified}")

            // Step 10: Copy to output
            val outputDir = File(context.getExternalFilesDir(null), "clones")
            outputDir.mkdirs()
            val finalApk = File(outputDir, "${config.clonePackage}_${config.versionName}.apk")
            signResult.signedApk.copyTo(finalApk, overwrite = true)

            onProgress("Clone complete: ${finalApk.absolutePath}")

            // Save config for backup/restore
            saveCloneConfig(config)

            Result.success(finalApk)
        } catch (e: Exception) {
            diagnostics.error("Clone failed: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun getApkPath(packageName: String): File {
        val pm = context.packageManager
        return File(pm.getApplicationInfo(packageName, 0).sourceDir)
    }

    private fun findApktool(): String? {
        return try {
            val proc = ProcessBuilder("which", "apktool").start()
            proc.waitFor()
            val path = proc.inputStream.bufferedReader().readText().trim()
            if (path.isNotEmpty()) path else null
        } catch (_: Exception) { null }
    }

    private fun unzipApk(apk: File, outDir: File) {
        java.util.zip.ZipFile(apk).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val outFile = File(outDir, entry.name)
                if (entry.isDirectory) outFile.mkdirs()
                else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
                }
            }
        }
    }

    private fun zipDir(dir: File, outApk: File) {
        java.util.zip.ZipOutputStream(outApk.outputStream()).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(dir).path
                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                file.inputStream().copyTo(zos)
                zos.closeEntry()
            }
        }
    }

    private fun gsonConfig(config: CloneConfig): String {
        return try {
            com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config)
        } catch (e: Exception) {
            "{\"clonePackage\":\"${config.clonePackage}\"}"
        }
    }

    private fun gsonIdentity(config: CloneConfig): String {
        return com.google.gson.Gson().toJson(config.identity)
    }

    private fun gsonEnvironment(config: CloneConfig): String {
        return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config.environment)
    }

    private fun getOrCreateKeystore(): File {
        val ksDir = File(context.filesDir, "keystore")
        ksDir.mkdirs()
        val ksFile = File(ksDir, "clone-master.keystore")
        if (!ksFile.exists()) {
            ksFile.writeBytes(SigningPipeline().createDebugKeystore().readBytes())
        }
        return ksFile
    }

    private fun handleObb(config: CloneConfig, decodedDir: File) {
        try {
            val obbSrc = File("/sdcard/Android/obb/${config.originalPackage}")
            if (!obbSrc.exists()) return
            val assetsObbDir = File(decodedDir, "assets/obb")
            assetsObbDir.mkdirs()
            obbSrc.listFiles()?.forEach { f ->
                f.copyTo(File(assetsObbDir, f.name), overwrite = true)
            }
            diagnostics.log("Bundled OBB from ${obbSrc.absolutePath}")
        } catch (e: Exception) {
            diagnostics.warn("OBB bundling failed: ${e.message}")
        }
    }

    private fun saveCloneConfig(config: CloneConfig) {
        try {
            val dir = File(context.filesDir, "clone_configs")
            dir.mkdirs()
            val file = File(dir, "${config.clonePackage}.json")
            file.writeText(gsonConfig(config))
        } catch (_: Exception) {}
    }

    fun getDiagnostics(): CloningDiagnostics = diagnostics
}
