package com.clonemaster.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the P1-4 registry cleanup: exactly one UI row per config field.
 * Duplicates previously made two rows fight over the same value
 * (hideRoot/hideEmulator/hideMockLocation ×2, versionName ×2, logcat ×2) and
 * one row pointed at a nonsense field ("isBatch").
 */
class OptionRegistryIntegrityTest {

    private val options = OptionRegistry.getAllOptions()

    @Test
    fun `no duplicate config field paths`() {
        val counts = options.groupingBy { it.configFieldPath }.eachCount()
        val dups = counts.filterValues { it > 1 }
        assertTrue("duplicate configFieldPath(s): $dups", dups.isEmpty())
    }

    @Test
    fun `no duplicate option ids`() {
        val counts = options.groupingBy { it.id }.eachCount()
        val dups = counts.filterValues { it > 1 }
        assertTrue("duplicate option id(s): $dups", dups.isEmpty())
    }

    @Test
    fun `broken mappings removed`() {
        assertFalse("'isBatch' is not a user-facing option field",
            options.any { it.configFieldPath == "isBatch" })
        // WebView UA was split across identity.webViewUserAgent AND developer.webViewUa
        val uaRows = options.filter {
            it.configFieldPath == "identity.webViewUserAgent" || it.configFieldPath == "developer.webViewUa"
        }
        assertEquals("exactly one WebView UA row", 1, uaRows.size)
        assertEquals("identity.webViewUserAgent", uaRows[0].configFieldPath)
    }

    @Test
    fun `inventory shrunk to 77 after dedupe`() {
        assertEquals(77, options.size)
        // every path still well-formed (root field or dotted path)
        options.forEach { o ->
            assertTrue("blank path on ${o.id}", o.configFieldPath.isNotBlank())
            assertFalse("path must not contain spaces: ${o.id}", o.configFieldPath.contains(" "))
        }
    }
}
