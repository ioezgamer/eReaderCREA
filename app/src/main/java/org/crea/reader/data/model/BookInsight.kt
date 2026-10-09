package org.crea.reader.data.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * What the AI worked out about a catalog book (clean title, author, summary, categories, reading level).
 * Generated once per book and shared with every device through a file in the Drive folder, so the cost
 * grows with the number of books and not with the number of readers.
 */
@Entity(tableName = BookInsight.TABLE_NAME)
data class BookInsight(
    @PrimaryKey
    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String,
    /** Version of the Drive file the insight was made for (its md5); a changed file is analysed again. */
    @ColumnInfo(name = "version")
    val version: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String?,
    @ColumnInfo(name = "summary")
    val summary: String,
    /** Up to three categories, separated by "|". */
    @ColumnInfo(name = "categories")
    val categories: String,
    @ColumnInfo(name = "level")
    val level: String,
    @ColumnInfo(name = "confidence")
    val confidence: String,
) {
    val categoryList: List<String> get() = categories.split('|').filter { it.isNotBlank() }

    /** Sample text from the AI test server; it must never be shown to readers or shared. */
    val isPlaceholder: Boolean get() = summary.startsWith(PLACEHOLDER)

    companion object {
        const val PLACEHOLDER = "(Ejemplo)"
        const val TABLE_NAME = "book_insights"
    }
}
