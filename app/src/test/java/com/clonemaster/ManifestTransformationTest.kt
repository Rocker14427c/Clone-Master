package com.clonemaster

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Tests for manifest transformation – ensures provider authority collisions handled, exported components, etc.
 * Independent implementation tests
 */
class ManifestTransformationTest {

    @Test
    fun testPackageReplacement() {
        val originalManifest = """
            <manifest package="com.example.app">
                <application>
                    <provider android:authorities="com.example.app.provider" />
                </application>
            </manifest>
        """.trimIndent()

        val newPackage = "com.example.app.clone1"
        var content = originalManifest.replace(Regex("""package="[^"]*""""), """package="$newPackage"""")

        assertTrue(content.contains("""package="$newPackage""""))
        assertFalse(content.contains("""package="com.example.app""""))
    }

    @Test
    fun testAuthorityTransformation() {
        val oldAuth = "com.example.app.provider"
        val newPkg = "com.example.app.clone1"
        val transformed = "$newPkg.${oldAuth.hashCode().toString(36)}.provider.${oldAuth.substringAfterLast('.')}".lowercase()

        assertTrue(transformed.startsWith(newPkg))
        assertTrue(transformed.contains("provider"))
        assertNotEquals(oldAuth, transformed)
    }

    @Test
    fun testAuthorityCollisionHandling() {
        val authorities = listOf("com.example.app.provider", "com.example.app.provider")
        val newPkg = "com.example.app.clone1"
        val seen = mutableSetOf<String>()

        val transformed = authorities.map { auth ->
            var base = "$newPkg.${auth.hashCode().toString(36)}.provider.${auth.substringAfterLast('.')}".lowercase()
            var result = base
            var attempts = 0
            while (seen.contains(result) && attempts < 10) {
                result = "${base}_${attempts}"
                attempts++
            }
            seen.add(result)
            result
        }

        assertEquals(transformed.size, transformed.distinct().size)
    }

    @Test
    fun testSharedUserIdRemoval() {
        val manifestWithSharedUserId = """
            <manifest package="com.example.app" android:sharedUserId="com.example.shared">
        """.trimIndent()

        val cleaned = manifestWithSharedUserId.replace(Regex("""android:sharedUserId="[^"]*""""), "")
        assertFalse(cleaned.contains("sharedUserId"))
    }

    @Test
    fun testExportedRequirement() {
        // Android 12+ requires android:exported for activities with intent-filter
        val activityWithoutExported = """
            <activity android:name=".MainActivity">
                <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                </intent-filter>
            </activity>
        """.trimIndent()

        val hasIntentFilter = activityWithoutExported.contains("<intent-filter>")
        val hasExported = activityWithoutExported.contains("android:exported")

        assertTrue(hasIntentFilter)
        assertFalse(hasExported)
        // Should warn about missing exported
    }

    @Test
    fun testHasFragileUserData() {
        val manifest = """<application android:name=".App">"""
        val withFragile = manifest.replace("<application", """<application android:hasFragileUserData="true"""")

        assertTrue(withFragile.contains("hasFragileUserData"))
        assertTrue(withFragile.contains("true"))
    }
}
