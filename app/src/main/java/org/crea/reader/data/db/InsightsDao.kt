package org.crea.reader.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow
import org.crea.reader.data.model.BookInsight

@Dao
interface InsightsDao {
    @Query("SELECT * FROM ${BookInsight.TABLE_NAME}")
    fun observeAll(): Flow<List<BookInsight>>

    @Query("SELECT * FROM ${BookInsight.TABLE_NAME}")
    suspend fun getAll(): List<BookInsight>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(insights: List<BookInsight>)

    @Query("DELETE FROM ${BookInsight.TABLE_NAME} WHERE drive_file_id IN (:ids)")
    suspend fun delete(ids: List<String>)
}
