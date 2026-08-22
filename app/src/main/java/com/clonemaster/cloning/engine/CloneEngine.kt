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
            if (apktool == null) {
                // apktool is a JVM tool and CANNOT run on Android; the previous
                // "unzip fallback" produced install-invalid APKs (binary manifest
                // corrupt, DEX unpatched, unsigned). Use the native pipeline that
                // builds a valid, v2-signed clone without any external tools.
                return@withContext cloneNative(config, onProgress, apkPath)
            } else {
                val proc = ProcessBuilder(apktool, "d", "-f", apkPath.absolutePath, "-o", decodedDir.absolutePath)
                    .redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                diagnostics.log("apktool decode: $out")
                if (!decodedDir.exists()) throw IllegalStateException("apktool decode failed: $out")
            }

            // Step 2: Manifest transform – independent implementation, functional parity with public feature reference
            onProgress("Transforming manifest...")
            val manifestFile = File(decodedDir, "AndroidManifest.xml")
            if (!manifestFile.exists()) throw IllegalStateException("AndroidManifest.xml not found after decode")
            val manifestResult = manifestTransformer.transform(manifestFile, config)
            diagnostics.log("New package: ${manifestResult.newPackage}, authorities: ${manifestResult.authorityMap}")

            // Manifest options – public reference: App category and Large heap moved to Manifest & resource options
            // Independent implementation for functional parity
            try {
                val manifestCategoryHandler = ManifestCategoryHandler()
                val manifestOptions = ManifestCategoryHandler.ManifestOptions(
                    appCategory = try { ManifestCategoryHandler.AppCategory.valueOf(config.parityFeatures.manifestOptions.appCategory.uppercase()) } catch (ignored: Exception) { ManifestCategoryHandler.AppCategory.UNDEFINED },
                    largeHeap = config.parityFeatures.manifestOptions.largeHeap
                )
                manifestCategoryHandler.apply(manifestFile, manifestOptions, config, diagnostics)

                // Storage – Prompt to keep app data on uninstall – public reference: Prompt to keep app data on uninstall
                // Equivalent functionality via hasFragileUserData
                val uninstallHandler = com.clonemaster.storage.UninstallDataHandler()
                val uninstallConfig = com.clonemaster.storage.UninstallDataHandler.UninstallConfig(
                    promptToKeepData = config.parityFeatures.uninstallData.promptToKeepData || config.storage.preserveDataOnUninstall,
                    hasFragileUserData = config.parityFeatures.uninstallData.hasFragileUserData || config.parityFeatures.uninstallData.promptToKeepData
                )
                uninstallHandler.applyToManifest(manifestFile, uninstallConfig, diagnostics)

            } catch (e: Exception) {
                diagnostics.warn("Manifest parity options failed: ${e.message}")
            }

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

            // Bundle coherent physical device profile – ONLY when environment
            // spoofing features are actually enabled (defaults are OFF).
            if (config.environment.hideRoot || config.environment.hideEmulator ||
                config.environment.spoofPhysicalDeviceProfile || config.environment.hideDeveloperOptions ||
                config.environment.hideUsbAdb || config.environment.hideMockLocation
            ) try {
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

            // Step 7b: Data Bundling – Bundle App Data feature
            var dataArchiveFile: File? = null
            var dataManifest: com.clonemaster.cloning.models.DataBundleManifest? = null
            if (config.dataBundle.enabled) {
                onProgress("Analyzing app data for bundling...")
                try {
                    val dataAnalyzer = com.clonemaster.databundle.DataBundleAnalyzer(context)
                    val analysis = dataAnalyzer.analyze(config.originalPackage)
                    diagnostics.log("Data analysis: ${analysis.categories.size} categories, ${analysis.totalSize / 1024 / 1024} MB total")
                    analysis.warnings.forEach { diagnostics.warn(it) }

                    // Let user choose – config already contains selectedCategories and customDirs
                    val selectedFiles = dataAnalyzer.getExportablePaths(
                        config.originalPackage,
                        config.dataBundle.selectedCategories,
                        config.dataBundle.customDirs
                    )

                    // Filter by excludeDirs
                    val filteredFiles = selectedFiles.filter { file ->
                        config.dataBundle.excludeDirs.none { exclude -> file.absolutePath.contains(exclude) }
                    }

                    if (filteredFiles.isNotEmpty()) {
                        onProgress("Bundling ${filteredFiles.size} data directories...")
                        val dataBundleDir = File(workDir, "data_bundle")
                        dataBundleDir.mkdirs()

                        val metadata = com.clonemaster.cloning.models.DataBundleMetadata(
                            sourcePackage = config.originalPackage,
                            clonePackage = config.clonePackage,
                            sourceVersionName = config.versionName,
                            sourceVersionCode = config.versionCode,
                            cloneVersionName = config.versionName,
                            cloneVersionCode = config.versionCode,
                            androidVersion = android.os.Build.VERSION.SDK_INT,
                            androidRelease = android.os.Build.VERSION.RELEASE ?: "",
                            dataFormatVersion = 2,
                            includedCategories = config.dataBundle.selectedCategories,
                            includedDirs = filteredFiles.map { it.absolutePath },
                            excludedDirs = config.dataBundle.excludeDirs,
                            compression = config.dataBundle.compression,
                            encryption = config.dataBundle.encryption
                        )

                        val archiveManager = com.clonemaster.databundle.DataArchiveManager(context)
                        val (archive, manifest) = archiveManager.createArchive(
                            sourcePackage = config.originalPackage,
                            clonePackage = config.clonePackage,
                            selectedFiles = filteredFiles,
                            metadata = metadata,
                            config = config.dataBundle,
                            outputDir = dataBundleDir,
                            onProgress = { msg -> onProgress(msg); diagnostics.log(msg) }
                        )

                        dataArchiveFile = archive
                        dataManifest = manifest

                        // Embed or package as associated payload
                        if (config.dataBundle.embedInApk) {
                            onProgress("Embedding data archive into APK assets...")
                            // Copy archive into assets/data/
                            val assetsDataDir = File(assetsDir, "data")
                            assetsDataDir.mkdirs()
                            archive.copyTo(File(assetsDataDir, "archive.zip"), overwrite = true)
                            // Also copy manifest
                            File(assetsDir, "data_manifest.json").writeText(
                                com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(manifest)
                            )
                            File(assetsDir, "data_bundle_metadata.json").writeText(
                                com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(metadata)
                            )
                            diagnostics.log("Embedded data archive ${archive.length() / 1024 / 1024} MB into APK")
                        } else {
                            // Create separate .data file – will be output alongside APK later
                            diagnostics.log("Data archive will be packaged as separate file: ${archive.absolutePath}")
                        }

                        // Inject first-run import activity into manifest if not already present
                        // Ensure clone launches FirstRunImportActivity on first run
                        try {
                            val manifestFile = File(decodedDir, "AndroidManifest.xml")
                            var manifestContent = manifestFile.readText()
                            if (!manifestContent.contains("FirstRunImportActivity")) {
                                val importActivityEntry = """
                                    <activity android:name="com.clonemaster.databundle.FirstRunImportActivity" android:exported="false" android:theme="@style/Theme.CloneMaster" />
                                """.trimIndent()
                                manifestContent = manifestContent.replace("</application>", "    $importActivityEntry\n    </application>")
                                manifestFile.writeText(manifestContent)
                            }
                        } catch (e: Exception) {
                            diagnostics.warn("Failed to inject import activity: ${e.message}")
                        }
                    } else {
                        diagnostics.warn("No exportable data found for bundling")
                    }

                } catch (e: Exception) {
                    diagnostics.warn("Data bundling failed: ${e.message} – clone will be created without data")
                    e.printStackTrace()
                }
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

            // Step 10: Copy to output – handle both single APK and APK+data packaging
            val outputDir = File(context.getExternalFilesDir(null), "clones")
            outputDir.mkdirs()
            val finalApk = File(outputDir, "${config.clonePackage}_${config.versionName}.apk")
            signResult.signedApk.copyTo(finalApk, overwrite = true)

            var finalDataFile: File? = null
            if (config.dataBundle.enabled && !config.dataBundle.embedInApk && dataArchiveFile != null) {
                // Package as separate .data file alongside APK
                finalDataFile = File(outputDir, "${config.clonePackage}_${config.versionName}.data")
                dataArchiveFile.copyTo(finalDataFile, overwrite = true)
                diagnostics.log("Created separate data file: ${finalDataFile.absolutePath}")

                // Also create combined backup package: /clone.apk + /data/archive + /manifest.json + /checksums
                val backupManager = com.clonemaster.databundle.BackupManager(context)
                val combinedPackage = backupManager.exportCloneAndData(
                    cloneConfig = config,
                    apkFile = finalApk,
                    dataArchive = finalDataFile,
                    outputDir = outputDir,
                    encrypt = config.dataBundle.encryption != com.clonemaster.cloning.models.EncryptionType.NONE,
                    password = config.dataBundle.encryptionPassword,
                    onProgress = { msg -> onProgress(msg) }
                )
                diagnostics.log("Created combined backup package: ${combinedPackage.absolutePath}")
            }

            onProgress("Clone complete: ${finalApk.absolutePath}" + (finalDataFile?.let { " + ${it.name}" } ?: ""))

            // Save config for backup/restore
            saveCloneConfig(config)

            // Save data bundle manifest for future restore
            if (dataManifest != null) {
                try {
                    val manifestDir = File(context.filesDir, "data_manifests")
                    manifestDir.mkdirs()
                    File(manifestDir, "${config.clonePackage}.json").writeText(
                        com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(dataManifest)
                    )
                } catch (ignored: Exception) {}
            }

            Result.success(finalApk)
        } catch (e: Exception) {
            diagnostics.error("Clone failed: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Native on-device clone build: AXML manifest transform -> DEX string patch
     * -> aligned repack -> v2 sign -> structural validation. No apktool, no
     * JVM tools, no external binaries. Never presents a build as successful
     * without passing post-build validation.
     */
    private suspend fun cloneNative(config: CloneConfig, onProgress: (String) -> Unit, apkPath: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            onProgress("Native clone build (no apktool on device)...")
            val apkBytes = apkPath.readBytes()
            if (apkBytes.isEmpty()) throw IllegalStateException("Source APK is empty: ${apkPath.absolutePath}")

            if (OptionalFeatures.anyEnabled(config)) {
                diagnostics.warn("Optional features are enabled, but the native pipeline currently applies clean-clone mechanics only (feature injection is a later phase – reported honestly, not hidden)")
            }

            val request = com.clonemaster.core.cloner.CloneRequest(
                originalPackage = config.originalPackage,
                clonePackage = config.clonePackage,
                authorityMap = emptyMap(), // auto-planned deterministically by the builder
                extraAssets = mapOf("clone_config.json" to gsonConfig(config).toByteArray(Charsets.UTF_8))
            )
            onProgress("Transforming manifest & DEX...")
            val product = com.clonemaster.core.cloner.AppCloneBuilder().build(apkBytes, request)
            product.diag.logs.forEach { diagnostics.log("native: $it") }
            product.diag.warnings.forEach { diagnostics.warn("native: $it") }
            product.diag.errors.forEach { diagnostics.error("native: $it") }
            if (product.diag.hasErrors) throw IllegalStateException("Native clone failed validation: ${product.diag.errors.joinToString(" | ")}")

            onProgress("Writing output APK...")
            val outputDir = File(context.getExternalFilesDir(null), "clones")
            outputDir.mkdirs()
            val finalApk = File(outputDir, "${config.clonePackage}_${config.versionName}.apk")
            finalApk.writeBytes(product.apk)
            diagnostics.log("Native clone complete: ${finalApk.absolutePath} (${finalApk.length()} bytes, v2-signed, validated)")
            saveCloneConfig(config)
            onProgress("Clone complete: ${finalApk.absolutePath}")
            Result.success(finalApk)
        } catch (e: Exception) {
            diagnostics.error("Native clone failed: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun getApkPath(packageName: String): File {
        val pm = context.packageManager
        return File(pm.getApplicationInfo(packageName, 0).sourceDir)
    }

    private fun findApktool(): String? {
        // Explicit override first (desktop/dev environments)
        System.getenv("APKTOOL")?.let { if (File(it).exists()) return it }
        // PATH scan – no `which` dependency (Android toybox may not have it)
        val pathEnv = System.getenv("PATH") ?: ""
        pathEnv.split(File.pathSeparator).forEach { dir ->
            val candidate = File(dir, "apktool")
            if (candidate.exists() && candidate.canExecute()) return candidate.absolutePath
        }
        return null
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
        } catch (ignored: Exception) {}
    }

    fun getDiagnostics(): CloningDiagnostics = diagnostics
}
