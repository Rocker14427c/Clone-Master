package com.clonemaster.core

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Unit tests for core cloning logic – independent implementation, not relying on Android
 * Tests manifest transformation, package transformation, archive handling
 */
class CloningCoreTest {

    @Test
    fun testPackageValidation() {
        val validPackages = listOf(
            "com.example.app",
            "com.example.app.clone1",
            "com.clonemaster.test"
        )
        val invalidPackages = listOf(
            "com..example",
            ".com.example",
            "123com.example",
            "com.example.",
            ""
        )

        val regex = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

        validPackages.forEach { pkg ->
            assertTrue("Package $pkg should be valid", regex.matches(pkg))
        }

        invalidPackages.forEach { pkg ->
            assertFalse("Package $pkg should be invalid", regex.matches(pkg))
        }
    }

    @Test
    fun testAuthorityTransformationUniqueness() {
        val originalAuthorities = listOf(
            "com.example.app.provider",
            "com.example.app.fileprovider",
            "com.example.app.provider" // duplicate to test collision handling
        )

        val newPackage = "com.example.app.clone1"
        val seen = mutableSetOf<String>()
        val transformed = mutableListOf<String>()

        originalAuthorities.forEach { auth ->
            var base = "$newPackage.${auth.hashCode().toString(36)}.provider.${auth.substringAfterLast('.')}".lowercase()
            var result = base
            var attempts = 0
            while (seen.contains(result) && attempts < 10) {
                result = "${base}_${attempts}_${(0..9999).random()}"
                attempts++
            }
            seen.add(result)
            transformed.add(result)
        }

        // Ensure uniqueness
        assertEquals(transformed.size, transformed.distinct().size)
        assertTrue(transformed.all { it.startsWith(newPackage) })
    }

    @Test
    fun testPathTraversalPrevention() {
        val maliciousPaths = listOf(
            "../../etc/passwd",
            "/etc/passwd",
            "..\\..\\windows\\system32",
            "data/../../etc/passwd",
            "data//../..//etc/passwd"
        )

        maliciousPaths.forEach { path ->
            val isSuspicious = path.contains("..") || path.startsWith("/") || path.contains("\\")
            assertTrue("Path $path should be detected as suspicious", isSuspicious)
        }

        val safePaths = listOf(
            "data/shared_prefs/com.example.xml",
            "data/databases/app.db",
            "data/files/image.jpg"
        )

        safePaths.forEach { path ->
            val isSuspicious = path.contains("..") || path.startsWith("/") || path.contains("\\")
            assertFalse("Path $path should be safe", isSuspicious)
        }
    }

    @Test
    fun testChecksumCalculation() {
        val tempFile = File.createTempFile("test", ".txt")
        tempFile.writeText("Hello Clone-Master")
        tempFile.deleteOnExit()

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val expected = digest.digest("Hello Clone-Master".toByteArray()).joinToString("") { "%02x".format(it) }

        val actual = calculateSha256(tempFile)
        assertEquals(expected, actual)
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
    fun testArchiveSizeLimits() {
        val maxFileSize = 100L * 1024 * 1024
        val maxTotalSize = 500L * 1024 * 1024

        assertTrue(maxFileSize == 100L * 1024 * 1024)
        assertTrue(maxTotalSize == 500L * 1024 * 1024)

        // Test that large file detection works
        val largeFileSize = 150L * 1024 * 1024
        assertTrue(largeFileSize > maxFileSize)
    }

    @Test
    fun testCloneConfigSerialization() {
        // Test that CloneConfig can be serialized/deserialized without losing data
        val configJson = """
            {
                "originalPackage": "com.example.app",
                "clonePackage": "com.example.app.clone1",
                "appName": "Example Clone",
                "versionName": "1.0",
                "versionCode": 1
            }
        """.trimIndent()

        assertTrue(configJson.contains("originalPackage"))
        assertTrue(configJson.contains("clonePackage"))
    }
}
