package org.crea.reader.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow
import org.crea.reader.data.model.DriveBook

@Dao
interface DriveCatalogDao {

    @Query("SELECT * FROM ${DriveBook.TABLE_NAME}")
    fun observeAll(): Flow<List<DriveBook>>

    @Query("SELECT * FROM ${DriveBook.TABLE_NAME}")
    suspend fun getAll(): List<DriveBook>

    @Query("SELECT * FROM ${DriveBook.TABLE_NAME} WHERE ${DriveBook.DRIVE_FILE_ID} = :driveFileId")
    suspend fun get(driveFileId: String): DriveBook?

    @Query("SELECT * FROM ${DriveBook.TABLE_NAME} WHERE ${DriveBook.READER_ID} = :readerId")
    suspend fun getByReaderId(readerId: Long): DriveBook?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(books: List<DriveBook>)

    @Query("DELETE FROM ${DriveBook.TABLE_NAME} WHERE ${DriveBook.DRIVE_FILE_ID} IN (:driveFileIds)")
    suspend fun delete(driveFileIds: List<String>)

    /** Applies one catalog refresh atomically so the screen never sees a half-updated list. */
    @Transaction
    suspend fun applyRefresh(upserts: List<DriveBook>, removedIds: List<String>) {
        if (upserts.isNotEmpty()) {
            // Reading position may have been saved since the caller took its snapshot; keep the newest.
            val current = getAll().associateBy { it.driveFileId }
            upsert(upserts.map { fresh ->
                val live = current[fresh.driveFileId]
                if (live != null && live.progressionAt > fresh.progressionAt) {
                    fresh.copy(progression = live.progression, progressionAt = live.progressionAt)
                } else {
                    fresh
                }
            })
        }
        removedIds.chunked(SQLITE_VARIABLE_CHUNK).forEach { delete(it) }
    }

    @Query(
        "UPDATE ${DriveBook.TABLE_NAME} SET ${DriveBook.COVER_PATH} = :coverPath, " +
            "${DriveBook.COVER_VERSION} = :coverVersion WHERE ${DriveBook.DRIVE_FILE_ID} = :driveFileId"
    )
    suspend fun setCover(driveFileId: String, coverPath: String?, coverVersion: String?)

    @Query(
        "UPDATE ${DriveBook.TABLE_NAME} SET ${DriveBook.PROGRESSION} = :locator, " +
            "${DriveBook.PROGRESSION_AT} = :updatedAt WHERE ${DriveBook.READER_ID} = :readerId"
    )
    suspend fun saveProgressionByReaderId(readerId: Long, locator: String, updatedAt: Long)

    @Query(
        "UPDATE ${DriveBook.TABLE_NAME} SET ${DriveBook.PROGRESSION} = :locator, " +
            "${DriveBook.PROGRESSION_AT} = :updatedAt WHERE ${DriveBook.DRIVE_FILE_ID} = :driveFileId"
    )
    suspend fun saveProgression(driveFileId: String, locator: String, updatedAt: Long)
}

/** Old Android versions allow at most 999 bound variables per statement. */
private const val SQLITE_VARIABLE_CHUNK = 500
