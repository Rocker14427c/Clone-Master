package com.clonemaster.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the dropdown<->config converters that silently broke orientation on-device. */
class OptionValueParsersTest {

    @Test
    fun `dropdown labels parse to the right ActivityInfo codes`() {
        assertEquals(-1, OptionValueParsers.parseOrientation("Default (-1)"))
        assertEquals(1, OptionValueParsers.parseOrientation("Portrait (1)"))
        assertEquals(0, OptionValueParsers.parseOrientation("Landscape (0)"))
        assertEquals(4, OptionValueParsers.parseOrientation("Sensor (4)"))
    }

    @Test
    fun `raw ints and junk still behave`() {
        assertEquals(1, OptionValueParsers.parseOrientation("1"))
        assertEquals(-1, OptionValueParsers.parseOrientation("garbage"))
    }

    @Test
    fun `stored value maps back to the same label - no silent reset to Default`() {
        assertEquals("Portrait (1)", OptionValueParsers.orientationLabel(1))
        assertEquals(1, OptionValueParsers.orientationIndex("1"))
        assertEquals(0, OptionValueParsers.orientationIndex("-1"))
        assertEquals(0, OptionValueParsers.orientationIndex("garbage"))
    }
}
