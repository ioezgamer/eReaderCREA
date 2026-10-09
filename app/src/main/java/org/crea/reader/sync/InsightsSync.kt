package org.crea.reader.sync

import android.content.Context
import kotlinx.serialization.Serializable

/** One book's AI-generated information as stored in the shared file of the Drive folder. */
@Serializable
data class SharedInsight(
    val driveFileId: String,
    val version: String,
    val title: String,
    val author: String? = null,
    val summary: String,
    val categories: List<String> = emptyList(),
    val level: String = "general",
    val confidence: String = "medium",
)

@Serializable
data class InsightIndex(val schema: Int = 1, val books: List<SharedInsight> = emptyList())

/** Remembers which version of the shared index was already read, so it is downloaded only when it changes. */
class InsightsSyncState(context: Context) {
    private val preferences = context.getSharedPreferences("insights-sync", Context.MODE_PRIVATE)

    var lastIndexModified: String?
        get() = preferences.getString("modified", null)
        set(value) = preferences.edit().putString("modified", value).apply()

    companion object {
        const val FILE_NAME = "crea-ai-catalog.json"
        const val MAX_PER_SYNC = 6
    }
}
