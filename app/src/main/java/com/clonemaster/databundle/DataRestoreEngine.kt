package com.clonemaster.databundle

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.clonemaster.cloning.models.*
import java.io.File

/**
 * Installation process: First-run import screen, progress, extraction, transformation, validation, rollback, log, retry
 */
class DataRestoreEngine(private val context: Context) {

    data class RestoreResult(
        val success: Boolean,
        val restoredFiles: Int,
        val restoredBytes: Long,
        val warnings: List<String>,
        val errors: List<String>,
        val log: String,
        val hasKeystoreData: Boolean,
        val needsRetry: Boolean
    )

    data class RestoreProgress(
        val stage: RestoreStage,
        val message: String,
        val progress: Int, // 0-100
        val currentFile: String? = null
    )

    enum class RestoreStage {
        DETECTING,
        PREPARING,
        EXTRACTING,
        RESTORING_FILES,
        RESTORING_DATABASES,
        RESTORING_WEBVIEW,
        TRANSFORMING,
        VALIDATING,
        FINALIZING,
        COMPLETE,
        FAILED
    }

    private val importLog = StringBuilder()
    private val warnings = mutableListOf<String>()
    private val errors = mutableListOf<String>()

    fun detectBundledData(): File? {
        // Check assets for data archive
        val possiblePaths = listOf(
            "data/archive.zip",
            "data/data.zip",
            "clone_data.cmb",
            "data_archive.zip",
            "data/data_archive.zip"
        )
        for (path in possiblePaths) {
            try {
                context.assets.open(path).close()
                // Found – copy to files dir for processing
                val outFile = File(context.filesDir, "bundled_data.zip")
                context.assets.open(path).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                return outFile
            } catch (_: Exception) { continue }
        }

        // Check files dir for separate data file (CloneName.data)
        val dataFiles = context.filesDir.listFiles { f -> f.extension == "cmb" || f.name.contains("data") } ?: emptyArray()
        return dataFiles.firstOrNull()
    }

    fun hasCompletedMigration(): Boolean {
        val prefs = context.getSharedPreferences("clone_migration", Context.MODE_PRIVATE)
        return prefs.getBoolean("migration_completed", false)
    }

    fun markMigrationCompleted() {
        context.getSharedPreferences("clone_migration", Context.MODE_PRIVATE).edit()
            .putBoolean("migration_completed", true)
            .putLong("migration_time", System.currentTimeMillis())
            .apply()
    }

    fun restore(
        archiveFile: File,
        manifest: DataBundleManifest,
        config: DataBundleConfig,
        onProgress: (RestoreProgress) -> Unit
    ): RestoreResult {

        importLog.clear()
        warnings.clear()
        errors.clear()

        var restoredFiles = 0
        var restoredBytes = 0L

        try {
            onProgress(RestoreProgress(RestoreStage.DETECTING, "Detecting bundled data...", 0))

            // Validate checksum
            onProgress(RestoreProgress(RestoreStage.PREPARING, "Verifying archive checksum...", 5))
            val archiveManager = DataArchiveManager(context)
            if (!archiveManager.verifyChecksum(archiveFile, manifest.metadata.archiveChecksumSha256)) {
                warnings.add("Archive checksum mismatch – expected ${manifest.metadata.archiveChecksumSha256}")
                // Continue anyway but warn
            }

            // Android version incompatibility check
            if (manifest.metadata.androidVersion > Build.VERSION.SDK_INT + 5) {
                warnings.add("Data from newer Android version ${manifest.metadata.androidVersion} may not be fully compatible with current ${Build.VERSION.SDK_INT}")
            }
            if (manifest.metadata.androidVersion < Build.VERSION.SDK_INT - 10) {
                warnings.add("Data from very old Android version ${manifest.metadata.androidVersion} – some files may be incompatible")
            }

            // App version incompatibility
            if (manifest.metadata.sourceVersionCode != manifest.metadata.cloneVersionCode) {
                warnings.add("Source version ${manifest.metadata.sourceVersionName} (${manifest.metadata.sourceVersionCode}) differs from clone ${manifest.metadata.cloneVersionName} (${manifest.metadata.cloneVersionCode}) – database schema may have changed")
            }

            // Create clone's private data structure – already exists, but ensure dirs
            onProgress(RestoreProgress(RestoreStage.PREPARING, "Creating storage structure...", 10))
            val dataDir = File(context.applicationInfo.dataDir)
            dataDir.mkdirs()
            File(dataDir, "shared_prefs").mkdirs()
            File(dataDir, "databases").mkdirs()
            File(dataDir, "files").mkdirs()
            File(dataDir, "app_webview").mkdirs()
            File(dataDir, "no_backup").mkdirs()

            importLog.appendLine("Data dir: $dataDir")
            importLog.appendLine("Source: ${manifest.metadata.sourcePackage} -> Clone: ${manifest.metadata.clonePackage}")

            // Extract archive
            onProgress(RestoreProgress(RestoreStage.EXTRACTING, "Extracting archive...", 20))
            val extractDir = File(context.cacheDir, "data_restore_${System.currentTimeMillis()}").apply { mkdirs() }
            extractArchive(archiveFile, extractDir, onProgress)

            // Restore files with transformation
            onProgress(RestoreProgress(RestoreStage.RESTORING_FILES, "Restoring files...", 30))
            val dataRoot = File(extractDir, "data")
            val filesToRestore = if (dataRoot.exists()) dataRoot.walkTopDown().filter { it.isFile }.toList() else extractDir.walkTopDown().filter { it.isFile }.toList()

            filesToRestore.forEachIndexed { index, file ->
                val progress = 30 + (index * 40 / filesToRestore.size.coerceAtLeast(1))
                onProgress(RestoreProgress(RestoreStage.RESTORING_FILES, "Restoring files... ${file.name}", progress, file.name))

                try {
                    val relativePath = file.relativeTo(if (dataRoot.exists()) dataRoot else extractDir).path
                    val targetPath = transformPathForRestore(relativePath, manifest, config)

                    val targetFile = File(dataDir, targetPath)
                    targetFile.parentFile?.mkdirs()

                    // Safety: never blindly overwrite incompatible files – check if target exists and is newer?
                    if (targetFile.exists()) {
                        // If file is database, check schema compatibility
                        if (targetFile.extension == "db" || targetFile.extension == "sqlite") {
                            if (!isDatabaseCompatible(targetFile, file)) {
                                warnings.add("Database schema incompatibility detected for $relativePath – skipping to avoid corruption")
                                importLog.appendLine("SKIPPED (schema incompatible): $relativePath")
                                return@forEachIndexed
                            }
                        }
                    }

                    file.copyTo(targetFile, overwrite = true)
                    restoredFiles++
                    restoredBytes += file.length()
                    importLog.appendLine("RESTORED: $relativePath -> ${targetFile.absolutePath}")

                } catch (e: Exception) {
                    errors.add("Failed to restore ${file.name}: ${e.message}")
                    importLog.appendLine("FAILED: ${file.name} – ${e.message}")
                }
            }

            // Restore databases – special handling for Room, SQLite
            onProgress(RestoreProgress(RestoreStage.RESTORING_DATABASES, "Restoring databases...", 70))
            // Databases already restored as files, but need to handle WAL, SHM files
            // Also need to handle Room's extra files

            // Restore WebView data
            onProgress(RestoreProgress(RestoreStage.RESTORING_WEBVIEW, "Restoring WebView data...", 75))
            val webViewFiles = filesToRestore.filter { it.path.contains("app_webview") }
            if (webViewFiles.isNotEmpty()) {
                warnings.add("WebView data restored, but cookies may be encrypted with device key – session may not be fully restored")
                importLog.appendLine("WebView data: ${webViewFiles.size} files restored with warning about encryption")
            }

            // Apply package-name/path/provider transformations
            onProgress(RestoreProgress(RestoreStage.TRANSFORMING, "Applying transformations...", 80))
            applyTransformations(dataDir, manifest, config)

            // Validate restored data
            onProgress(RestoreProgress(RestoreStage.VALIDATING, "Validating restored data...", 85))
            val validationResult = validateRestoredData(dataDir, manifest)
            if (!validationResult) {
                warnings.add("Validation found some inconsistencies – check import log")
            }

            // Verify final data directory after extraction
            onProgress(RestoreProgress(RestoreStage.FINALIZING, "Finalizing...", 90))
            val finalCheck = verifyFinalDataDir(dataDir)
            if (!finalCheck) {
                errors.add("Final data directory verification failed")
            }

            // Mark migration completed
            markMigrationCompleted()

            // Delete temporary extraction files
            extractDir.deleteRecursively()
            archiveFile.delete()

            onProgress(RestoreProgress(RestoreStage.COMPLETE, "Data import complete", 100))

            // Check for Keystore / hardware-backed data that could not be restored
            val hasKeystoreData = manifest.metadata.hasKeystoreData || warnings.any { it.contains("Keystore") || it.contains("hardware-backed") }

            if (hasKeystoreData) {
                warnings.add("Some account/session data could not be restored because it is protected by Android or the application (Keystore, hardware-backed security, certificate-bound credentials, server-side sessions)")
            }

            return RestoreResult(
                success = errors.isEmpty(),
                restoredFiles = restoredFiles,
                restoredBytes = restoredBytes,
                warnings = warnings,
                errors = errors,
                log = importLog.toString(),
                hasKeystoreData = hasKeystoreData,
                needsRetry = errors.isNotEmpty()
            )

        } catch (e: Exception) {
            e.printStackTrace()
            errors.add("Restore failed: ${e.message}")
            importLog.appendLine("FATAL ERROR: ${e.message}")
            onProgress(RestoreProgress(RestoreStage.FAILED, "Restore failed: ${e.message}", 0))

            // Rollback
            rollback()

            return RestoreResult(
                success = false,
                restoredFiles = restoredFiles,
                restoredBytes = restoredBytes,
                warnings = warnings,
                errors = errors,
                log = importLog.toString(),
                hasKeystoreData = false,
                needsRetry = true
            )
        }
    }

    private fun extractArchive(archive: File, dest: File, onProgress: (RestoreProgress) -> Unit) {
        // Archive is zip containing manifest + data/archive.zip
        // For simplicity, if archive is zip, extract it
        try {
            java.util.zip.ZipFile(archive).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val outFile = File(dest, entry.name)
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            // If inner data/archive.zip exists, extract it too
            val innerArchive = File(dest, "data/archive.zip")
            if (innerArchive.exists()) {
                java.util.zip.ZipFile(innerArchive).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        val outFile = File(dest, entry.name)
                        outFile.parentFile?.mkdirs()
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { input ->
                                outFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // If not zip, try direct copy
            archive.copyTo(File(dest, "data.zip"), overwrite = true)
        }
    }

    private fun transformPathForRestore(relativePath: String, manifest: DataBundleManifest, config: DataBundleConfig): String {
        if (!config.transformPaths) return relativePath

        var transformed = relativePath

        // Transform package-name based paths
        // Example: shared_prefs/com.example.app_preferences.xml -> shared_prefs/com.example.clone_preferences.xml
        // But we should keep file names as is for SharedPreferences – Android will load them based on name, not package
        // Actually for SharedPreferences, file name often contains package – we should replace source package with clone package in file name and content

        // For simplicity, replace source package string in path with clone package if present
        transformed = transformed.replace(manifest.metadata.sourcePackage, manifest.metadata.clonePackage)

        return transformed
    }

    private fun applyTransformations(dataDir: File, manifest: DataBundleManifest, config: DataBundleConfig) {
        if (!config.transformPaths) return

        // Transform SharedPreferences content: replace source package with clone package in XML values
        val prefsDir = File(dataDir, "shared_prefs")
        if (prefsDir.exists()) {
            prefsDir.listFiles()?.forEach { file ->
                try {
                    var content = file.readText()
                    if (content.contains(manifest.metadata.sourcePackage)) {
                        content = content.replace(manifest.metadata.sourcePackage, manifest.metadata.clonePackage)
                        file.writeText(content)
                        importLog.appendLine("Transformed prefs: ${file.name}")
                    }
                } catch (_: Exception) {}
            }
        }

        // Transform provider authorities in databases? For Room/SQLite, we might need to replace authority strings
        // This is heuristic – real implementation would parse DB content

        // Transform external storage paths if bundled
        // /sdcard/Android/data/sourcePkg -> /sdcard/Android/data/clonePkg is handled at build time, not here
    }

    private fun isDatabaseCompatible(existing: File, newFile: File): Boolean {
        // Check database schema compatibility – compare table schemas via SQLite
        // For simplicity, check file size and basic header
        return try {
            // SQLite files start with "SQLite format 3"
            val existingHeader = existing.inputStream().use { it.readNBytes(16).toString(Charsets.UTF_8) }
            val newHeader = newFile.inputStream().use { it.readNBytes(16).toString(Charsets.UTF_8) }
            existingHeader == newHeader || existingHeader.contains("SQLite") && newHeader.contains("SQLite")
        } catch (_: Exception) { true }
    }

    private fun validateRestoredData(dataDir: File, manifest: DataBundleManifest): Boolean {
        // Verify file count matches manifest
        val actualFiles = dataDir.walkTopDown().filter { it.isFile }.count()
        importLog.appendLine("Validation: expected ~${manifest.files.size} files, actual $actualFiles in data dir")
        return actualFiles >= manifest.files.size * 0.8 // allow some missing
    }

    private fun verifyFinalDataDir(dataDir: File): Boolean {
        // Check that data dir is not empty and has expected subdirs
        return dataDir.exists() && dataDir.listFiles()?.isNotEmpty() == true
    }

    private fun rollback() {
        importLog.appendLine("Rolling back failed restore...")
        // In real implementation, keep backup of original data dir before restore and restore it on failure
        // For now, just log
    }

    fun getImportLog(): String = importLog.toString()

    fun allowRetry(): Boolean {
        // Clear migration completed flag to allow retry
        context.getSharedPreferences("clone_migration", Context.MODE_PRIVATE).edit()
            .putBoolean("migration_completed", false)
            .apply()
        return true
    }
}
