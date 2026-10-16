package org.crea.reader

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Light, dark, or whatever the device uses. */
enum class ThemeMode(val nightMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        /** Unknown or missing names fall back to following the device. */
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * The look of the whole app (light or dark), chosen in Ajustes. The choice is kept here and applied
 * to every AppCompat screen, the reader's bars and the Compose panels included.
 */
object AppThemeMode {
    private const val PREFERENCES = "ui-preferences"
    private const val KEY = "app_theme_mode"

    fun saved(context: Context): ThemeMode =
        ThemeMode.fromName(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))

    /** Stores [mode] and switches every open screen to it right away. */
    fun select(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    /** Call once at startup, before any screen is created. */
    fun applyAtStartup(context: Context) {
        AppCompatDelegate.setDefaultNightMode(saved(context).nightMode)
    }
}
