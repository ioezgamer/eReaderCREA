/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.data.db

import androidx.annotation.ColorInt
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow
import org.crea.reader.data.model.Book
import org.crea.reader.data.model.Bookmark
import org.crea.reader.data.model.Highlight

@Dao
interface BooksDao {

    /**
     * Inserts a book
     * @param book The book to insert
     * @return ID of the book that was added (primary key)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    /**
     * Deletes a book
     * @param bookId The ID of the book
     */
    @Query("DELETE FROM " + Book.TABLE_NAME + " WHERE " + Book.ID + " = :bookId")
    suspend fun deleteBook(bookId: Long)

    /**
     * Retrieve a book from its ID.
     */
    @Query("SELECT * FROM " + Book.TABLE_NAME + " WHERE " + Book.ID + " = :id")
    suspend fun get(id: Long): Book?

    @Query("SELECT * FROM ${Book.TABLE_NAME} WHERE ${Book.CONTENT_HASH} = :contentHash LIMIT 1")
    suspend fun getByContentHash(contentHash: String): Book?

    @Query("SELECT * FROM ${Book.TABLE_NAME} ORDER BY ${Book.CREATION_DATE} DESC")
    suspend fun getAllBooksSnapshot(): List<Book>

    /**
     * Retrieve all books
     * @return List of books as Flow
     */
    @Query("SELECT * FROM " + Book.TABLE_NAME + " ORDER BY " + Book.CREATION_DATE + " desc")
    fun getAllBooks(): Flow<List<Book>>

    /**
     * Retrieve all bookmarks for a specific book
     * @param bookId The ID of the book
     * @return List of bookmarks for the book as Flow
     */
    @Query("SELECT * FROM " + Bookmark.TABLE_NAME + " WHERE " + Bookmark.BOOK_ID + " = :bookId")
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>

    /**
     * Retrieve all highlights for a specific book
     */
    @Query(
        "SELECT * FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.BOOK_ID} = :bookId ORDER BY ${Highlight.TOTAL_PROGRESSION} ASC"
    )
    fun getHighlightsForBook(bookId: Long): Flow<List<Highlight>>

    /**
     * Retrieves the highlight with the given ID.
     */
    @Query("SELECT * FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.ID} = :highlightId")
    suspend fun getHighlightById(highlightId: Long): Highlight?

    /**
     * Inserts a bookmark
     * @param bookmark The bookmark to insert
     * @return The ID of the bookmark that was added (primary key)
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmark(bookmark: Bookmark): Long

    /**
     * Inserts a highlight
     * @param highlight The highlight to insert
     * @return The ID of the highlight that was added (primary key)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: Highlight): Long

    /**
     * Updates a highlight's annotation.
     */
    @Query(
        "UPDATE ${Highlight.TABLE_NAME} SET ${Highlight.ANNOTATION} = :annotation WHERE ${Highlight.ID} = :id"
    )
    suspend fun updateHighlightAnnotation(id: Long, annotation: String)

    /**
     * Updates a highlight's tint and style.
     */
    @Query(
        "UPDATE ${Highlight.TABLE_NAME} SET ${Highlight.TINT} = :tint, ${Highlight.STYLE} = :style WHERE ${Highlight.ID} = :id"
    )
    suspend fun updateHighlightStyle(id: Long, style: Highlight.Style, @ColorInt tint: Int)

    /**
     * Deletes a bookmark
     */
    @Query("DELETE FROM " + Bookmark.TABLE_NAME + " WHERE " + Bookmark.ID + " = :id")
    suspend fun deleteBookmark(id: Long)

    /**
     * Deletes the highlight with given id.
     */
    @Query("DELETE FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.ID} = :id")
    suspend fun deleteHighlight(id: Long)

    /**
     * Saves book progression
     * @param locator Location of the book
     * @param id The book to update
     */
    @Query(
        "UPDATE ${Book.TABLE_NAME} SET ${Book.PROGRESSION} = :locator, " +
            "${Book.PROGRESS_UPDATED_AT} = :updatedAt, ${Book.UPDATED_AT} = :updatedAt WHERE ${Book.ID} = :id"
    )
    suspend fun saveProgression(locator: String, id: Long, updatedAt: Long)

    @Query(
        "UPDATE ${Book.TABLE_NAME} SET ${Book.PROGRESSION} = :locator, " +
            "${Book.PROGRESS_UPDATED_AT} = :updatedAt, ${Book.UPDATED_AT} = :updatedAt " +
            "WHERE ${Book.CONTENT_HASH} = :contentHash"
    )
    suspend fun saveProgressionByHash(contentHash: String, locator: String, updatedAt: Long)

    @Query(
        "UPDATE ${Book.TABLE_NAME} SET ${Book.REMOTE_FILE_ID} = :remoteFileId, " +
            "${Book.UPDATED_AT} = :updatedAt WHERE ${Book.CONTENT_HASH} = :contentHash"
    )
    suspend fun setRemoteFileId(contentHash: String, remoteFileId: String, updatedAt: Long)

    @Query("UPDATE ${Book.TABLE_NAME} SET ${Book.REMOTE_FILE_ID} = NULL")
    suspend fun clearRemoteFileIds()

    @Query("SELECT * FROM ${Book.TABLE_NAME} WHERE ${Book.REMOTE_FILE_ID} = :remoteFileId LIMIT 1")
    suspend fun getByRemoteFileId(remoteFileId: String): Book?

    /** Moves bookmarks between two book ids; a bookmark already present at the target wins. */
    @Transaction
    suspend fun moveBookmarks(fromBookId: Long, toBookId: Long) {
        reassignBookmarks(fromBookId, toBookId)
        deleteBookmarksOf(fromBookId)
    }

    @Query(
        "UPDATE OR IGNORE ${Bookmark.TABLE_NAME} SET ${Bookmark.BOOK_ID} = :toBookId " +
            "WHERE ${Bookmark.BOOK_ID} = :fromBookId"
    )
    suspend fun reassignBookmarks(fromBookId: Long, toBookId: Long)

    @Query("DELETE FROM ${Bookmark.TABLE_NAME} WHERE ${Bookmark.BOOK_ID} = :bookId")
    suspend fun deleteBookmarksOf(bookId: Long)

    @Query("UPDATE ${Book.TABLE_NAME} SET ${Book.REMOTE_MD5} = :md5 WHERE ${Book.ID} = :id")
    suspend fun setRemoteMd5(id: Long, md5: String?)

    @Query(
        "UPDATE ${Book.TABLE_NAME} SET ${Book.REMOTE_FILE_ID} = :remoteFileId, ${Book.REMOTE_MD5} = :md5, " +
            "${Book.UPDATED_AT} = :updatedAt WHERE ${Book.CONTENT_HASH} = :contentHash"
    )
    suspend fun linkToDrive(contentHash: String, remoteFileId: String, md5: String?, updatedAt: Long)

    /** Copies a book's notes and highlights aside before the book row (and, by cascade, them) goes away. */
    @Query(
        "INSERT INTO highlight_stash (content_hash, creation_date, style, tint, href, type, title, " +
            "total_progression, locations, text, annotation) " +
            "SELECT :contentHash, CREATION_DATE, STYLE, TINT, HREF, TYPE, TITLE, TOTAL_PROGRESSION, " +
            "LOCATIONS, TEXT, ANNOTATION FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.BOOK_ID} = :bookId"
    )
    suspend fun stashHighlights(bookId: Long, contentHash: String)

    @Query(
        "INSERT INTO ${Highlight.TABLE_NAME} (CREATION_DATE, BOOK_ID, STYLE, TINT, HREF, TYPE, TITLE, " +
            "TOTAL_PROGRESSION, LOCATIONS, TEXT, ANNOTATION) " +
            "SELECT creation_date, :bookId, style, tint, href, type, title, total_progression, " +
            "locations, text, annotation FROM highlight_stash WHERE content_hash = :contentHash"
    )
    suspend fun copyStashedHighlights(bookId: Long, contentHash: String)

    @Query("DELETE FROM highlight_stash WHERE content_hash = :contentHash")
    suspend fun clearStash(contentHash: String)

    @Transaction
    suspend fun restoreHighlights(bookId: Long, contentHash: String) {
        copyStashedHighlights(bookId, contentHash)
        clearStash(contentHash)
    }

    @Transaction
    suspend fun stashAndDelete(book: Book) {
        val id = requireNotNull(book.id)
        stashHighlights(id, book.contentHash)
        deleteBook(id)
    }

    @Query("UPDATE ${Book.TABLE_NAME} SET ${Book.FAVORITE} = :favorite WHERE ${Book.ID} = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    /** Ids of the books that have at least one bookmark (online-reading ids are negative and excluded). */
    @Query("SELECT DISTINCT ${Bookmark.BOOK_ID} FROM ${Bookmark.TABLE_NAME} WHERE ${Bookmark.BOOK_ID} > 0")
    fun getBookmarkedBookIds(): Flow<List<Long>>

    @Query("SELECT DISTINCT ${Highlight.BOOK_ID} FROM ${Highlight.TABLE_NAME}")
    fun getAnnotatedBookIds(): Flow<List<Long>>
}

