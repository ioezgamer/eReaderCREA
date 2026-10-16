package org.crea.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLookTest {
    @Test
    fun `bar sizes use the scales of the reference app`() {
        assertEquals(0.72f, BarSize.SMALL.scale, 0f)
        assertEquals(0.9f, BarSize.MEDIUM.scale, 0f)
        assertEquals(1.15f, BarSize.LARGE.scale, 0f)
    }

    @Test
    fun `medium is the base size`() {
        assertEquals(1f, BarSize.MEDIUM.relative, 0f)
        assertEquals(true, BarSize.SMALL.relative < 1f)
        assertEquals(true, BarSize.LARGE.relative > 1f)
    }

    @Test
    fun `unknown stored index falls back to medium`() {
        assertEquals(BarSize.SMALL, BarSize.fromIndex(0))
        assertEquals(BarSize.LARGE, BarSize.fromIndex(2))
        assertEquals(BarSize.MEDIUM, BarSize.fromIndex(7))
        assertEquals(BarSize.MEDIUM, BarSize.fromIndex(-1))
    }

    @Test
    fun `brightness never goes below the minimum`() {
        assertEquals(BrightnessRules.MIN, BrightnessRules.fromPercent(0), 0f)
        assertEquals(0.5f, BrightnessRules.fromPercent(50), 0f)
        assertEquals(1f, BrightnessRules.fromPercent(300), 0f)
    }

    @Test
    fun `percent round trips`() {
        assertEquals(40, BrightnessRules.toPercent(BrightnessRules.fromPercent(40)))
        assertEquals(3, BrightnessRules.toPercent(0f))
    }
}
