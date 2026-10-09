package org.crea.reader.data.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** A shelf the user made ("Para clase", "Pendientes"...). */
@Entity(tableName = BookCollection.TABLE_NAME)
data class BookCollection(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    companion object {
        const val TABLE_NAME = "collections"
    }
}

/** Membership is by content hash, so a book stays in its collections if it is deleted and downloaded again. */
@Entity(
    tableName = CollectionBook.TABLE_NAME,
    primaryKeys = ["collection_id", "content_hash"],
    foreignKeys = [
        ForeignKey(
            entity = BookCollection::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["content_hash"])]
)
data class CollectionBook(
    @ColumnInfo(name = "collection_id")
    val collectionId: Long,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
) {
    companion object {
        const val TABLE_NAME = "collection_books"
    }
}
