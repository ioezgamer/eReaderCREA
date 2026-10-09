package org.crea.reader.data.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Notes and highlights of a book that was removed from "Mi biblioteca", kept by the content hash of
 * the file. If the same file is added again (for example downloaded again from the catalog) they
 * are restored, which a plain delete would not allow because highlights are tied to the book row.
 */
@Entity(
    tableName = HighlightStash.TABLE_NAME,
    indices = [Index(value = [HighlightStash.CONTENT_HASH])]
)
data class HighlightStash(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = CONTENT_HASH)
    val contentHash: String,
    @ColumnInfo(name = "creation_date")
    val creation: Long?,
    @ColumnInfo(name = "style")
    val style: String,
    @ColumnInfo(name = "tint")
    val tint: Int,
    @ColumnInfo(name = "href")
    val href: String,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "total_progression")
    val totalProgression: Double,
    @ColumnInfo(name = "locations")
    val locations: String,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "annotation")
    val annotation: String,
) {
    companion object {
        const val TABLE_NAME = "highlight_stash"
        const val CONTENT_HASH = "content_hash"
    }
}
