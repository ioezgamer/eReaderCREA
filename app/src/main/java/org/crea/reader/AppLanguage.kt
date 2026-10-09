package org.crea.reader

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The app always speaks Spanish unless the user picks another language in Ajustes.
 * The choice is kept here and applied to every AppCompat screen, reader included.
 */
object AppLanguage {
    const val SPANISH = "es"
    const val ENGLISH = "en"

    private const val PREFERENCES = "ui-preferences"
    private const val KEY = "app_language"

    fun saved(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)
            ?.takeIf { it == SPANISH || it == ENGLISH }
            ?: SPANISH

    /** Stores [tag] and switches the whole app to it right away. */
    fun select(context: Context, tag: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Call once at startup. Respects a language chosen in the system per-app settings (Android 13+). */
    fun applyAtStartup(context: Context) {
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(saved(context)))
        } else {
            val tag = current.get(0)?.language
            if (tag == SPANISH || tag == ENGLISH) {
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            }
        }
    }
}
