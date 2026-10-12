/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.data

import androidx.annotation.ColorInt
import java.io.File
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType
import org.crea.reader.data.db.BooksDao
import org.crea.reader.data.db.CollectionsDao
import org.crea.reader.data.db.DriveCatalogDao
import org.crea.reader.online.OnlineBookIds
import org.crea.reader.data.model.Book
import org.crea.reader.data.model.BookCollection
import org.crea.reader.data.model.Bookmark
import org.crea.reader.data.model.CollectionBook
import org.crea.reader.data.model.Highlight
import org.crea.reader.utils.extensions.readium.authorName

class BookRepository(
    private val booksDao: BooksDao,
    private val driveCatalogDao: DriveCatalogDao,
    private val collectionsDao: CollectionsDao,
) {
    fun books(): Flow<List<Book>> = booksDao.getAllBooks()

    suspend fun get(id: Long) = booksDao.get(id)

    /**
     * Saves the reading position. A book opened online has no row in [Book]: its position is
     * kept in the catalog entry, and moves to the book if the user downloads it later.
     */
    suspend fun saveProgression(locator: Locator, bookId: Long) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (OnlineBookIds.isOnline(bookId)) {
            driveCatalogDao.saveProgressionByReaderId(bookId, locator.toJSON().toString(), now)
        } else {
            booksDao.saveProgression(locator.toJSON().toString(), bookId, now)
        }
    }

    suspend fun insertBookmark(bookId: Long, publication: Publication, locator: Locator): Long {
        val resource = publication.readingOrder.indexOfFirstWithHref(locator.href)!!
        val bookmark = Bookmark(
            creation = Clock.System.now().toEpochMilliseconds(),
            bookId = bookId,
            resourceIndex = resource.toLong(),
            resourceHref = locator.href.toString(),
            resourceType = locator.mediaType.toString(),
            resourceTitle = locator.title.orEmpty(),
            location = locator.locations.toJSON().toString(),
            locatorText = Locator.Text().toJSON().toString()
        )

        return booksDao.insertBookmark(bookmark)
    }

    fun bookmarksForBook(bookId: Long): Flow<List<Bookmark>> =
        booksDao.getBookmarksForBook(bookId)

    suspend fun deleteBookmark(bookmarkId: Long) = booksDao.deleteBookmark(bookmarkId)

    suspend fun highlightById(id: Long): Highlight? =
        booksDao.getHighlightById(id)

    fun highlightsForBook(bookId: Long): Flow<List<Highlight>> =
        booksDao.getHighlightsForBook(bookId)

    suspend fun addHighlight(
        bookId: Long,
        style: Highlight.Style,
        @ColorInt tint: Int,
        locator: Locator,
        annotation: String,
    ): Long =
        booksDao.insertHighlight(Highlight(bookId, style, tint, locator, annotation))

    suspend fun deleteHighlight(id: Long) = booksDao.deleteHighlight(id)

    suspend fun updateHighlightAnnotation(id: Long, annotation: String) {
        booksDao.updateHighlightAnnotation(id, annotation)
    }

    suspend fun updateHighlightStyle(id: Long, style: Highlight.Style, @ColorInt tint: Int) {
        booksDao.updateHighlightStyle(id, style, tint)
    }

    suspend fun insertBook(
        url: Url,
        mediaType: MediaType,
        publication: Publication,
        cover: File,
        contentHash: String,
    ): Long {
        val now = Clock.System.now().toEpochMilliseconds()
        val publicationFile = requireNotNull((url as? AbsoluteUrl)?.toFile()) {
            "Imported publications must resolve to a local file"
        }
        val book = Book(
            creation = now,
            title = publication.metadata.title ?: url.filename,
            author = publication.metadata.authorName,
            href = url.toString(),
            identifier = publication.metadata.identifier ?: "",
            mediaType = mediaType,
            progression = "{}",
            cover = cover.path,
            contentHash = contentHash,
            fileSize = publicationFile.length(),
            updatedAt = now,
        )
        val id = booksDao.insertBook(book)
        // Notes made on an earlier copy of this same file come back with it.
        if (id != -1L) booksDao.restoreHighlights(id, book.contentHash)
        return id
    }

    /**
     * Removes the downloaded copy only. The book stays in the online catalog, so the reading
     * position and the bookmarks are handed back to the catalog entry for the next download.
     */
    suspend fun deleteBook(book: Book) {
        val bookId = requireNotNull(book.id)
        val driveFileId = book.remoteFileId
        if (driveFileId != null) {
            if (!book.progression.isNullOrBlank() && book.progression != "{}") {
                driveCatalogDao.saveProgression(driveFileId, book.progression, book.progressUpdatedAt)
            }
            booksDao.moveBookmarks(bookId, OnlineBookIds.forDriveFile(driveFileId))
        } else {
            booksDao.deleteBookmarksOf(bookId)
        }
        booksDao.stashAndDelete(book)
    }

    /** Brings what the user did while reading online (position, bookmarks) into the new local copy. */
    suspend fun adoptOnlineData(book: Book, driveFileId: String) {
        val bookId = requireNotNull(book.id)
        val online = driveCatalogDao.get(driveFileId)
        val progression = online?.progression
        if (progression != null && online.progressionAt > book.progressUpdatedAt) {
            booksDao.saveProgressionByHash(book.contentHash, progression, online.progressionAt)
        }
        booksDao.moveBookmarks(OnlineBookIds.forDriveFile(driveFileId), bookId)
    }

    suspend fun getByRemoteFileId(remoteFileId: String): Book? = booksDao.getByRemoteFileId(remoteFileId)

    suspend fun setFavorite(bookId: Long, favorite: Boolean) = booksDao.setFavorite(bookId, favorite)

    suspend fun setCategory(bookId: Long, category: String?) = booksDao.setCategory(bookId, category?.trim()?.takeIf { it.isNotEmpty() })

    /** After a catalog refresh: downloaded books follow their Drive folder if it was moved or renamed. */
    suspend fun followDriveCategories() = booksDao.followDriveCategories()

    fun bookmarkedBookIds(): Flow<List<Long>> = booksDao.getBookmarkedBookIds()

    fun annotatedBookIds(): Flow<List<Long>> = booksDao.getAnnotatedBookIds()

    suspend fun allBooksSnapshot(): List<Book> = booksDao.getAllBooksSnapshot()

    suspend fun getByContentHash(contentHash: String) = booksDao.getByContentHash(contentHash)

    suspend fun saveProgressionFromSync(contentHash: String, locator: String, updatedAt: Long) =
        booksDao.saveProgressionByHash(contentHash, locator, updatedAt)

    suspend fun setRemoteFileId(contentHash: String, remoteFileId: String, updatedAt: Long) =
        booksDao.setRemoteFileId(contentHash, remoteFileId, updatedAt)

    suspend fun clearRemoteFileIds() = booksDao.clearRemoteFileIds()

    /** Links a local book to its Drive file, remembering which version of the file it matches. */
    suspend fun linkToDrive(contentHash: String, remoteFileId: String, md5: String?, updatedAt: Long) =
        booksDao.linkToDrive(contentHash, remoteFileId, md5, updatedAt)

    suspend fun setRemoteMd5(bookId: Long, md5: String?) = booksDao.setRemoteMd5(bookId, md5)

    /**
     * Swaps a downloaded copy for a newer version of the same Drive file ([fresh] was just imported):
     * favourite, reading position (when it is the same publication) and bookmarks move to the new
     * copy and the old one is removed. Highlights of the old copy are stashed by its hash.
     */
    suspend fun replaceWithUpdate(old: Book, fresh: Book, driveFileId: String, md5: String?) {
        val oldId = requireNotNull(old.id)
        val freshId = requireNotNull(fresh.id)
        booksDao.linkToDrive(fresh.contentHash, driveFileId, md5, Clock.System.now().toEpochMilliseconds())
        if (old.favorite) booksDao.setFavorite(freshId, true)
        if (old.identifier.isNotEmpty() && old.identifier == fresh.identifier &&
            !old.progression.isNullOrBlank() && old.progression != "{}"
        ) {
            booksDao.saveProgressionByHash(fresh.contentHash, old.progression, old.progressUpdatedAt)
        }
        booksDao.moveBookmarks(oldId, freshId)
        booksDao.stashAndDelete(old)
    }

    // Collections (membership is by content hash)

    fun collections(): Flow<List<BookCollection>> = collectionsDao.observeCollections()

    fun collectionMembers(): Flow<List<CollectionBook>> = collectionsDao.observeMembers()

    suspend fun createCollection(name: String): Long =
        collectionsDao.insert(BookCollection(name = name.trim(), createdAt = Clock.System.now().toEpochMilliseconds()))

    suspend fun renameCollection(id: Long, name: String) = collectionsDao.rename(id, name.trim())

    suspend fun deleteCollection(id: Long) = collectionsDao.delete(id)

    suspend fun setInCollection(collectionId: Long, contentHash: String, member: Boolean) {
        if (member) collectionsDao.addBook(CollectionBook(collectionId, contentHash))
        else collectionsDao.removeBook(collectionId, contentHash)
    }
}

