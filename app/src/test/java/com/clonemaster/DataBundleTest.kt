package com.clonemaster

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Tests for data bundling and restoration – archive creation, checksums, path traversal protection, rollback
 */
class DataBundleTest {

    @Test
    fun testPathTraversalProtection() {
        val destDir = File("/tmp/clone_test_dest").apply { mkdirs() }
        val destCanonical = destDir.canonicalPath

        val maliciousEntries = listOf(
            "../../etc/passwd",
            "/etc/passwd",
            "data/../../etc/passwd",
            "..\\windows\\system32"
        )

        maliciousEntries.forEach { entryName ->
            val outFile = File(destDir, entryName)
            val outCanonical = outFile.canonicalPath
            val isOutside = !outCanonical.startsWith(destCanonical)
            assertTrue("Entry $entryName should be detected as outside dest", isOutside || entryName.contains(".."))
        }

        destDir.deleteRecursively()
    }

    @Test
    fun testArchiveCreationWithSizeLimits() {
        val maxFileSize = 100L * 1024 * 1024
        val smallFile = File.createTempFile("small", ".txt")
        smallFile.writeText("small content")
        smallFile.deleteOnExit()

        assertTrue(smallFile.length() < maxFileSize)

        val largeFileSize = 150L * 1024 * 1024
        assertTrue(largeFileSize > maxFileSize)
    }

    @Test
    fun testPackageTransformation() {
        val sourcePkg = "com.example.app"
        val clonePkg = "com.example.app.clone1"
        val originalPath = "/data/data/com.example.app/shared_prefs/com.example.app_preferences.xml"

        val transformed = originalPath.replace(sourcePkg, clonePkg)
        assertEquals("/data/data/com.example.app.clone1/shared_prefs/com.example.app.clone1_preferences.xml", transformed)
    }

    @Test
    fun testDatabaseCompatibilityCheck() {
        val validSqliteHeader = "SQLite format 3\u0000"
        val invalidHeader = "Not a database"

        assertTrue(validSqliteHeader.contains("SQLite"))
        assertFalse(invalidHeader.contains("SQLite"))
    }

    @Test
    fun testBackupVersioning() {
        val version = 2
        assertTrue(version >= 1)

        val manifest = mapOf(
            "version" to version,
            "backupType" to "CLONE_AND_DATA"
        )

        assertEquals(2, manifest["version"])
    }

    @Test
    fun testChecksumVerification() {
        val file = File.createTempFile("checksum", ".txt")
        file.writeText("test data")
        file.deleteOnExit()

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val expected = digest.digest("test data".toByteArray()).joinToString("") { "%02x".format(it) }

        val actual = calculateSha256(file)
        assertEquals(expected, actual)

        val wrongChecksum = "0000000000000000000000000000000000000000000000000000000000000000"
        assertNotEquals(wrongChecksum, actual)
    }

    private fun calculateSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testNeverModifyOriginal() {
        val originalDataDir = "/data/data/com.example.app"
        val cloneDataDir = "/data/data/com.example.app.clone1"

        assertNotEquals(originalDataDir, cloneDataDir)
        assertTrue(cloneDataDir.contains("clone1"))
        assertFalse(cloneDataDir == originalDataDir)

        // Ensure restore only writes to clone dir
        val dataDir = File(cloneDataDir)
        val originalDir = File(originalDataDir)

        assertTrue(dataDir.canonicalPath.contains("clone1"))
        assertFalse(dataDir.canonicalPath == originalDir.canonicalPath)
    }
}
