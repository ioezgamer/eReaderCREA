package org.crea.reader

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeModeTest {
    @Test
    fun `each mode maps to its AppCompat night mode`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.SYSTEM.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.LIGHT.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.DARK.nightMode)
    }

    @Test
    fun `unknown stored names follow the device`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromName("DARK"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("sepia"))
    }
}
