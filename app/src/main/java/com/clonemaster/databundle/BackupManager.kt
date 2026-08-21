package com.clonemaster.databundle

import android.content.Context
import com.clonemaster.cloning.models.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Backup and restoration – export/import clone + data, settings, data-only, encrypted, versioned, integrity verification
 */
class BackupManager(private val context: Context) {

    companion object {
        const val BACKUP_VERSION = 2
        const val BACKUP_DIR = "backups"
    }

    data class BackupInfo(
        val backupId: String,
        val clonePackage: String,
        val backupType: BackupType,
        val createdAt: Long,
        val version: Int,
        val sizeBytes: Long,
        val checksum: String,
        val encrypted: Boolean,
        val includesData: Boolean,
        val includesApk: Boolean,
        val metadata: DataBundleMetadata? = null
    )

    enum class BackupType {
        CLONE_AND_DATA,
        DATA_ONLY,
        SETTINGS_ONLY,
        APK_ONLY
    }

    /**
     * Export clone + data – creates CloneName.apk + CloneName.data or single package
     */
    fun exportCloneAndData(
        cloneConfig: CloneConfig,
        apkFile: File,
        dataArchive: File?,
        outputDir: File,
        encrypt: Boolean = false,
        password: String = "",
        onProgress: (String) -> Unit = {}
    ): File {

        outputDir.mkdirs()
        val backupId = "${cloneConfig.clonePackage}_${System.currentTimeMillis()}"
        val backupFile = File(outputDir, "$backupId.cmb_backup") // Clone-Master Backup

        onProgress("Creating backup $backupId...")

        ZipOutputStream(backupFile.outputStream()).use { zos ->

            // manifest.json
            val metadata = DataBundleMetadata(
                sourcePackage = cloneConfig.originalPackage,
                clonePackage = cloneConfig.clonePackage,
                sourceVersionName = cloneConfig.versionName,
                sourceVersionCode = cloneConfig.versionCode,
                cloneVersionName = cloneConfig.versionName,
                cloneVersionCode = cloneConfig.versionCode,
                dataFormatVersion = BACKUP_VERSION,
                createdAt = System.currentTimeMillis(),
                archiveName = backupId
            )

            val backupManifest = mapOf(
                "backupId" to backupId,
                "clonePackage" to cloneConfig.clonePackage,
                "originalPackage" to cloneConfig.originalPackage,
                "backupType" to BackupType.CLONE_AND_DATA.name,
                "version" to BACKUP_VERSION,
                "createdAt" to System.currentTimeMillis(),
                "includesData" to (dataArchive != null),
                "includesApk" to true,
                "metadata" to metadata,
                "config" to cloneConfig
            )

            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(backupManifest).toByteArray())
            zos.closeEntry()

            // clone.apk
            onProgress("Adding APK...")
            zos.putNextEntry(ZipEntry("clone.apk"))
            apkFile.inputStream().copyTo(zos)
            zos.closeEntry()

            // data/archive
            if (dataArchive != null && dataArchive.exists()) {
                onProgress("Adding data archive...")
                zos.putNextEntry(ZipEntry("data/archive.zip"))
                dataArchive.inputStream().copyTo(zos)
                zos.closeEntry()
            }

            // clone_config.json
            zos.putNextEntry(ZipEntry("clone_config.json"))
            zos.write(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(cloneConfig).toByteArray())
            zos.closeEntry()

            // checksums
            onProgress("Calculating checksums...")
            val checksums = mutableMapOf<String, String>()
            checksums["clone.apk"] = calculateSha256(apkFile)
            if (dataArchive != null) checksums["data/archive.zip"] = calculateSha256(dataArchive)

            zos.putNextEntry(ZipEntry("checksums.sha256"))
            zos.write(checksums.entries.joinToString("\n") { "${it.value}  ${it.key}" }.toByteArray())
            zos.closeEntry()
        }

        // Encrypt if requested
        val finalBackup = if (encrypt && password.isNotEmpty()) {
            onProgress("Encrypting backup...")
            DataArchiveManager(context).let { manager ->
                // Reuse encryption logic – create .enc file
                val encrypted = File(outputDir, "${backupFile.name}.enc")
                // For simplicity, use same encrypt method as data archive
                // Actually backup is already zip, encrypt whole file
                val keyBytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
                val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
                encrypted.outputStream().use { fos ->
                    fos.write(iv)
                    val cos = javax.crypto.CipherOutputStream(fos, cipher)
                    backupFile.inputStream().copyTo(cos)
                    cos.close()
                }
                backupFile.delete()
                encrypted
            }
        } else backupFile

        onProgress("Backup complete: ${finalBackup.absolutePath} (${finalBackup.length() / 1024 / 1024} MB)")

        return finalBackup
    }

    /**
     * Data-only backup
     */
    fun backupDataOnly(
        clonePackage: String,
        outputDir: File,
        encrypt: Boolean = false,
        password: String = "",
        onProgress: (String) -> Unit = {}
    ): File {
        val analyzer = DataBundleAnalyzer(context)
        val analysis = analyzer.analyze(clonePackage)

        val selectedFiles = analysis.categories.values.filter { it.accessible }.map { File(it.path) }

        val config = DataBundleConfig(
            enabled = true,
            selectedCategories = analysis.categories.keys.toMutableList(),
            encryption = if (encrypt) EncryptionType.AES256 else EncryptionType.NONE,
            encryptionPassword = password
        )

        val metadata = DataBundleMetadata(
            sourcePackage = clonePackage,
            clonePackage = clonePackage,
            dataFormatVersion = BACKUP_VERSION,
            createdAt = System.currentTimeMillis()
        )

        val archiveManager = DataArchiveManager(context)
        val (archive, manifest) = archiveManager.createArchive(
            sourcePackage = clonePackage,
            clonePackage = clonePackage,
            selectedFiles = selectedFiles,
            metadata = metadata,
            config = config,
            outputDir = outputDir,
            onProgress = onProgress
        )

        return archive
    }

    /**
     * Backup clone settings/configuration only
     */
    fun backupSettings(cloneConfig: CloneConfig, outputDir: File): File {
        outputDir.mkdirs()
        val backupFile = File(outputDir, "${cloneConfig.clonePackage}_settings_${System.currentTimeMillis()}.json")
        backupFile.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(cloneConfig))
        return backupFile
    }

    fun restoreSettings(backupFile: File): CloneConfig? {
        return try {
            com.google.gson.Gson().fromJson(backupFile.readText(), CloneConfig::class.java)
        } catch (e: Exception) { null }
    }

    /**
     * Import clone + data backup
     */
    fun importBackup(backupFile: File, password: String = "", outputDir: File, onProgress: (String) -> Unit = {}): ImportResult {
        try {
            onProgress("Verifying backup integrity...")
            val isEncrypted = backupFile.extension == "enc"
            val actualBackupFile = if (isEncrypted) {
                if (password.isEmpty()) return ImportResult(false, "Password required for encrypted backup")
                onProgress("Decrypting backup...")
                DataArchiveManager(context).decryptArchive(backupFile, password, outputDir)
            } else backupFile

            // Verify checksum / structure
            val tempDir = File(context.cacheDir, "import_${System.currentTimeMillis()}").apply { mkdirs() }
            java.util.zip.ZipFile(actualBackupFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val outFile = File(tempDir, entry.name)
                    if (!entry.isDirectory) {
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
                    }
                }
            }

            // Check manifest
            val manifestFile = File(tempDir, "manifest.json")
            if (!manifestFile.exists()) return ImportResult(false, "Invalid backup – manifest.json missing")

            val manifestJson = manifestFile.readText()
            val manifestMap = com.google.gson.Gson().fromJson(manifestJson, Map::class.java)

            // Verify checksums
            val checksumsFile = File(tempDir, "checksums.sha256")
            if (checksumsFile.exists()) {
                onProgress("Verifying checksums...")
                val checksums = checksumsFile.readLines().associate { line ->
                    val parts = line.split("  ")
                    parts[1] to parts[0]
                }
                checksums.forEach { (path, expected) ->
                    val file = File(tempDir, path)
                    if (file.exists()) {
                        val actual = calculateSha256(file)
                        if (!actual.equals(expected, true)) {
                            return ImportResult(false, "Checksum mismatch for $path")
                        }
                    }
                }
            }

            // Extract APK and data
            val apkFile = File(tempDir, "clone.apk")
            val dataArchive = File(tempDir, "data/archive.zip")
            val configFile = File(tempDir, "clone_config.json")

            onProgress("Import successful")

            return ImportResult(
                success = true,
                message = "Import successful",
                apkFile = if (apkFile.exists()) apkFile else null,
                dataArchive = if (dataArchive.exists()) dataArchive else null,
                config = if (configFile.exists()) com.google.gson.Gson().fromJson(configFile.readText(), CloneConfig::class.java) else null,
                tempDir = tempDir
            )

        } catch (e: Exception) {
            return ImportResult(false, "Import failed: ${e.message}")
        }
    }

    data class ImportResult(
        val success: Boolean,
        val message: String,
        val apkFile: File? = null,
        val dataArchive: File? = null,
        val config: CloneConfig? = null,
        val tempDir: File? = null
    )

    fun listBackups(backupDir: File): List<BackupInfo> {
        if (!backupDir.exists()) return emptyList()
        return backupDir.listFiles { f -> f.extension == "cmb_backup" || f.name.endsWith(".enc") }?.mapNotNull { file ->
            try {
                // Parse manifest from zip to get info – simplified
                var clonePackage = "unknown"
                var backupType = BackupType.CLONE_AND_DATA
                java.util.zip.ZipFile(file).use { zip ->
                    val manifestEntry = zip.getEntry("manifest.json")
                    if (manifestEntry != null) {
                        val json = zip.getInputStream(manifestEntry).bufferedReader().readText()
                        val map = com.google.gson.Gson().fromJson(json, Map::class.java)
                        clonePackage = map["clonePackage"] as? String ?: "unknown"
                        backupType = try { BackupType.valueOf(map["backupType"] as? String ?: "CLONE_AND_DATA") } catch (ignored: Exception) { BackupType.CLONE_AND_DATA }
                    }
                }
                BackupInfo(
                    backupId = file.nameWithoutExtension,
                    clonePackage = clonePackage,
                    backupType = backupType,
                    createdAt = file.lastModified(),
                    version = BACKUP_VERSION,
                    sizeBytes = file.length(),
                    checksum = calculateSha256(file),
                    encrypted = file.extension == "enc",
                    includesData = true,
                    includesApk = true
                )
            } catch (ignored: Exception) { null }
        } ?: emptyList()
    }

    fun verifyBackupIntegrity(backupFile: File): Boolean {
        return try {
            java.util.zip.ZipFile(backupFile).use { zip ->
                zip.getEntry("manifest.json") != null && zip.getEntry("checksums.sha256") != null
            }
        } catch (ignored: Exception) { false }
    }

    private fun calculateSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { "" }
    }

    /**
     * Migration between compatible clone versions
     */
    fun migrateData(
        oldConfig: CloneConfig,
        newConfig: CloneConfig,
        oldDataDir: File,
        newDataDir: File,
        onProgress: (String) -> Unit = {}
    ): Boolean {
        onProgress("Migrating data from ${oldConfig.clonePackage} v${oldConfig.versionName} to ${newConfig.clonePackage} v${newConfig.versionName}")

        // Check compatibility
        if (oldConfig.originalPackage != newConfig.originalPackage) {
            onProgress("Warning: original packages differ – migration may not be fully compatible")
        }

        // Copy files with transformation
        try {
            oldDataDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(oldDataDir).path
                val target = File(newDataDir, relative.replace(oldConfig.clonePackage, newConfig.clonePackage))
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
            onProgress("Migration complete")
            return true
        } catch (e: Exception) {
            onProgress("Migration failed: ${e.message}")
            return false
        }
    }
}
