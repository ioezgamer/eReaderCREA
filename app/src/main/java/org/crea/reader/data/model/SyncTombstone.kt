package org.crea.reader.data.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Legacy: earlier versions recorded deleted books here to delete them in Drive too. Deleting a
 * book from "Mi biblioteca" now only removes the local copy, so nothing writes or reads this
 * table any more. It stays so existing databases keep matching the schema.
 */
@Entity(tableName = SyncTombstone.TABLE_NAME)
data class SyncTombstone(
    @PrimaryKey
    @ColumnInfo(name = CONTENT_HASH)
    val contentHash: String,
    @ColumnInfo(name = DELETED_AT)
    val deletedAt: Long,
) {
    companion object {
        const val TABLE_NAME = "sync_tombstones"
        const val CONTENT_HASH = "content_hash"
        const val DELETED_AT = "deleted_at"
    }
}
