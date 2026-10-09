package org.crea.reader.views

import android.content.Context

/** How a list of books is drawn. */
enum class ViewMode { LIST, GRID, COVERS }

enum class CoverSize { SMALL, MEDIUM, LARGE }

/** The user's choice for one screen; remembered between sessions. */
data class ViewOptions(val mode: ViewMode, val size: CoverSize) {

    /** Minimum column width in dp: the screen fits as many columns as that allows. */
    val columnWidthDp: Int
        get() = when (mode) {
            ViewMode.LIST -> LIST_COLUMN_DP
            ViewMode.GRID -> when (size) {
                CoverSize.SMALL -> 112
                CoverSize.MEDIUM -> 150
                CoverSize.LARGE -> 210
            }
            ViewMode.COVERS -> when (size) {
                CoverSize.SMALL -> 88
                CoverSize.MEDIUM -> 120
                CoverSize.LARGE -> 176
            }
        }

    companion object {
        private const val LIST_COLUMN_DP = 360
        private const val PREFERENCES = "ui-preferences"

        fun load(context: Context, key: String, default: ViewMode): ViewOptions {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            return ViewOptions(
                mode = ViewMode.entries.firstOrNull { it.name == preferences.getString("${key}_mode", null) } ?: default,
                size = CoverSize.entries.firstOrNull { it.name == preferences.getString("${key}_size", null) }
                    ?: CoverSize.MEDIUM,
            )
        }

        fun save(context: Context, key: String, options: ViewOptions) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("${key}_mode", options.mode.name)
                .putString("${key}_size", options.size.name)
                .apply()
        }
    }
}
