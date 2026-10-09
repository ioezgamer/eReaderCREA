package org.crea.reader.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow
import org.crea.reader.data.model.BookCollection
import org.crea.reader.data.model.CollectionBook

@Dao
interface CollectionsDao {
    @Query("SELECT * FROM ${BookCollection.TABLE_NAME} ORDER BY name COLLATE NOCASE")
    fun observeCollections(): Flow<List<BookCollection>>

    @Query("SELECT * FROM ${CollectionBook.TABLE_NAME}")
    fun observeMembers(): Flow<List<CollectionBook>>

    @Insert
    suspend fun insert(collection: BookCollection): Long

    @Query("UPDATE ${BookCollection.TABLE_NAME} SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM ${BookCollection.TABLE_NAME} WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBook(member: CollectionBook)

    @Query("DELETE FROM ${CollectionBook.TABLE_NAME} WHERE collection_id = :collectionId AND content_hash = :contentHash")
    suspend fun removeBook(collectionId: Long, contentHash: String)
}
