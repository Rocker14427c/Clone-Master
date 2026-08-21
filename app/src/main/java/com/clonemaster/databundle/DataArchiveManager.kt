package com.clonemaster.databundle

import android.content.Context
import android.os.Build
import com.clonemaster.cloning.models.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

/**
 * Build-time: Package selected data into encrypted/compressed archive
 * Installation: Extract/import with transformations
 */
class DataArchiveManager(private val context: Context) {

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val CHECKSUMS_FILE = "checksums.sha256"
        const val DATA_DIR = "data"
        const val VERSION = 2
    }

    /**
     * Create archive from selected files
     */
    fun createArchive(
        sourcePackage: String,
        clonePackage: String,
        selectedFiles: List<File>,
        metadata: DataBundleMetadata,
        config: DataBundleConfig,
        outputDir: File,
        onProgress: (String) -> Unit = {}
    ): Pair<File, DataBundleManifest> {

        outputDir.mkdirs()
        val archiveName = "${clonePackage}_data_v${VERSION}.cmb" // Clone-Master Bundle
        val archiveFile = File(outputDir, archiveName)

        val manifest = DataBundleManifest()
        manifest.metadata = metadata.copy(
            sourcePackage = sourcePackage,
            clonePackage = clonePackage,
            androidVersion = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "",
            dataFormatVersion = VERSION,
            createdAt = System.currentTimeMillis(),
            archiveName = archiveName,
            compression = config.compression,
            encryption = config.encryption,
            includedCategories = config.selectedCategories,
            includedDirs = selectedFiles.map { it.absolutePath }
        )

        val fileEntries = mutableListOf<DataBundleFileEntry>()
        val checksums = mutableMapOf<String, String>()

        onProgress("Analyzing ${selectedFiles.size} directories...")

        // Collect all files recursively
        val allFiles = mutableListOf<File>()
        selectedFiles.forEach { dir ->
            if (dir.isFile) allFiles.add(dir)
            else dir.walkTopDown().filter { it.isFile }.forEach { allFiles.add(it) }
        }

        manifest.metadata.fileCount = allFiles.size
        manifest.metadata.totalBytes = allFiles.sumOf { it.length() }

        onProgress("Packaging ${allFiles.size} files (${manifest.metadata.totalBytes / 1024 / 1024} MB)...")

        // Create zip / zstd archive
        val tempArchive = File(outputDir, "${archiveName}.tmp")

        when (config.compression) {
            CompressionType.ZIP, CompressionType.NONE -> createZipArchive(tempArchive, allFiles, selectedFiles, fileEntries, checksums, onProgress, config)
            CompressionType.GZIP -> createGzipArchive(tempArchive, allFiles, selectedFiles, fileEntries, checksums, onProgress, config)
            CompressionType.ZSTD -> {
                // For simplicity, use ZIP with best compression as ZSTD placeholder – real would use com.github.luben:zstd-jni
                createZipArchive(tempArchive, allFiles, selectedFiles, fileEntries, checksums, onProgress, config)
            }
        }

        // Calculate checksum
        val sha256 = calculateSha256(tempArchive)
        manifest.metadata.archiveChecksumSha256 = sha256
        manifest.metadata.archiveSize = tempArchive.length()
        manifest.files = fileEntries
        manifest.checksums = checksums
        manifest.version = VERSION

        // Write manifest.json inside archive? Actually we create outer container with manifest + data
        // For simplicity: create final archive as zip containing manifest.json + checksums + data/
        val finalArchive = File(outputDir, archiveName)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(finalArchive))).use { zos ->
            // manifest.json
            zos.putNextEntry(ZipEntry(MANIFEST_FILE))
            zos.write(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(manifest).toByteArray())
            zos.closeEntry()

            // checksums.sha256
            zos.putNextEntry(ZipEntry(CHECKSUMS_FILE))
            val checksumsContent = checksums.entries.joinToString("\n") { "${it.value}  ${it.key}" }
            zos.write(checksumsContent.toByteArray())
            zos.closeEntry()

            // data archive (inner)
            zos.putNextEntry(ZipEntry("data/archive.zip"))
            tempArchive.inputStream().copyTo(zos)
            zos.closeEntry()
        }

        tempArchive.delete()

        // Encrypt if needed
        val encryptedArchive = if (config.encryption != EncryptionType.NONE && config.encryptionPassword.isNotEmpty()) {
            onProgress("Encrypting archive with ${config.encryption}...")
            encryptArchive(finalArchive, config.encryptionPassword, config.encryption, outputDir)
        } else {
            finalArchive
        }

        // Update metadata with final size
        manifest.metadata.archiveSize = encryptedArchive.length()
        manifest.metadata.archiveChecksumSha256 = calculateSha256(encryptedArchive)

        onProgress("Archive created: ${encryptedArchive.name} (${encryptedArchive.length() / 1024 / 1024} MB)")

        return encryptedArchive to manifest
    }

    private fun createZipArchive(
        output: File,
        allFiles: List<File>,
        rootDirs: List<File>,
        entries: MutableList<DataBundleFileEntry>,
        checksums: MutableMap<String, String>,
        onProgress: (String) -> Unit,
        config: DataBundleConfig
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
            allFiles.forEachIndexed { index, file ->
                if (index % 100 == 0) onProgress("Compressing ${index}/${allFiles.size}: ${file.name}")
                // Find relative path from one of root dirs
                val root = rootDirs.find { file.absolutePath.startsWith(it.absolutePath) } ?: rootDirs.firstOrNull()
                val relative = if (root != null) file.relativeTo(root).path else file.name
                val archivePath = "$DATA_DIR/$relative"

                val checksum = calculateSha256(file)
                checksums[archivePath] = checksum

                val entry = DataBundleFileEntry(
                    originalPath = file.absolutePath,
                    relativePath = archivePath,
                    type = detectCategory(file),
                    size = file.length(),
                    checksum = checksum,
                    requiresTransformation = config.transformPaths && needsTransformation(file),
                    transformedPath = if (config.transformPaths) transformPath(file.absolutePath, config) else file.absolutePath
                )
                entries.add(entry)

                zos.putNextEntry(ZipEntry(archivePath))
                file.inputStream().copyTo(zos)
                zos.closeEntry()
            }
        }
    }

    private fun createGzipArchive(
        output: File,
        allFiles: List<File>,
        rootDirs: List<File>,
        entries: MutableList<DataBundleFileEntry>,
        checksums: MutableMap<String, String>,
        onProgress: (String) -> Unit,
        config: DataBundleConfig
    ) {
        // For simplicity, create tar.gz – here we use zip then gzip
        val tempZip = File(output.parentFile, "${output.name}.zip.tmp")
        createZipArchive(tempZip, allFiles, rootDirs, entries, checksums, onProgress, config)
        GZIPOutputStream(FileOutputStream(output)).use { gzos ->
            tempZip.inputStream().copyTo(gzos)
        }
        tempZip.delete()
    }

    private fun detectCategory(file: File): DataCategory {
        return when {
            file.path.contains("shared_prefs") -> DataCategory.SHARED_PREFS
            file.path.contains("databases") -> DataCategory.DATABASES
            file.path.contains("app_webview") -> DataCategory.WEBVIEW_DATA
            file.path.contains("Android/data") -> DataCategory.EXTERNAL_APP_DIRS
            file.path.contains("Android/obb") -> DataCategory.OBB_DIRS
            file.path.contains("no_backup") -> DataCategory.CACHE_INDEPENDENT
            else -> DataCategory.FILES
        }
    }

    private fun needsTransformation(file: File): Boolean {
        // If file contains hard-coded package name or paths, it needs transformation
        // Heuristic: xml, json, db, prefs often contain package
        return file.extension in listOf("xml", "json", "db", "sqlite", "prefs", "txt")
    }

    private fun transformPath(originalPath: String, config: DataBundleConfig): String {
        // Transform /data/data/sourcePkg -> /data/data/clonePkg
        // This will be applied during restore
        return originalPath // placeholder, real transformation done at restore time with source/clone pkg
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

    private fun encryptArchive(input: File, password: String, encryption: EncryptionType, outputDir: File): File {
        return try {
            val encryptedFile = File(outputDir, "${input.name}.enc")
            // Simple AES-GCM encryption – key derived from password via SHA-256 (in production use PBKDF2)
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            FileOutputStream(encryptedFile).use { fos ->
                fos.write(iv) // prepend IV
                val cos = javax.crypto.CipherOutputStream(fos, cipher)
                input.inputStream().copyTo(cos)
                cos.close()
            }
            input.delete()
            encryptedFile
        } catch (e: Exception) {
            e.printStackTrace()
            input
        }
    }

    fun decryptArchive(input: File, password: String, outputDir: File): File {
        return try {
            val decryptedFile = File(outputDir, input.nameWithoutExtension)
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
            val keySpec = SecretKeySpec(keyBytes, "AES")

            FileInputStream(input).use { fis ->
                val iv = ByteArray(12)
                fis.read(iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val gcmSpec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
                val cis = javax.crypto.CipherInputStream(fis, cipher)
                FileOutputStream(decryptedFile).use { fos ->
                    cis.copyTo(fos)
                }
            }
            decryptedFile
        } catch (e: Exception) {
            throw e
        }
    }

    fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        val actual = calculateSha256(file)
        return actual.equals(expectedSha256, ignoreCase = true)
    }
}
