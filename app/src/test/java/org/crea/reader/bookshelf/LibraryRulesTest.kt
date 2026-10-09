package org.crea.reader.bookshelf

import org.crea.reader.data.model.Book
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryRulesTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun locator(progress: Double) = """{"locations":{"totalProgression":$progress}}"""

    private fun book(
        id: Long,
        title: String,
        author: String? = null,
        progress: Double = 0.0,
        mediaType: String = "application/epub+zip",
        favorite: Boolean = false,
        remoteFileId: String? = null,
        createdDaysAgo: Long = 60,
        readDaysAgo: Long = 0,
        size: Long = 100,
    ) = Book(
        id = id,
        creation = now - createdDaysAgo * day,
        href = "file:///b/$id",
        title = title,
        author = author,
        identifier = "i$id",
        progression = if (progress > 0) locator(progress) else "{}",
        rawMediaType = mediaType,
        cover = "/c/$id",
        contentHash = "h$id",
        fileSize = size,
        updatedAt = now - createdDaysAgo * day,
        progressUpdatedAt = if (readDaysAgo > 0) now - readDaysAgo * day else 0,
        remoteFileId = remoteFileId,
        favorite = favorite,
    )

    private val books = listOf(
        book(1, "Cien años de soledad", "Gabriel García Márquez", progress = 0.5, favorite = true, remoteFileId = "a", readDaysAgo = 2),
        book(2, "Álgebra", "Baldor", mediaType = "application/pdf", progress = 1.0, createdDaysAgo = 3, size = 900),
        book(3, "Rayuela", "Cortázar", progress = 0.1, remoteFileId = "c", readDaysAgo = 10, size = 500),
        book(4, "Zoología", createdDaysAgo = 1, mediaType = "application/pdf"),
    )

    private fun ids(query: LibraryQuery, marks: BookMarks = BookMarks()) =
        LibraryRules.apply(books, query, marks, now).map { it.id }

    @Test
    fun filtersByReadingState() {
        assertEquals(setOf<Long?>(1, 3), ids(LibraryQuery(filter = LibraryFilter.READING)).toSet())
        assertEquals(setOf<Long?>(4), ids(LibraryQuery(filter = LibraryFilter.UNREAD)).toSet())
        assertEquals(setOf<Long?>(2), ids(LibraryQuery(filter = LibraryFilter.FINISHED)).toSet())
    }

    @Test
    fun filtersByFormatFavoritesAndRecentDownloads() {
        assertEquals(setOf<Long?>(2, 4), ids(LibraryQuery(filter = LibraryFilter.PDF)).toSet())
        assertEquals(setOf<Long?>(1, 3), ids(LibraryQuery(filter = LibraryFilter.EPUB)).toSet())
        assertEquals(setOf<Long?>(1), ids(LibraryQuery(filter = LibraryFilter.FAVORITES)).toSet())
        assertEquals(setOf<Long?>(2, 4), ids(LibraryQuery(filter = LibraryFilter.RECENT)).toSet())
    }

    @Test
    fun filtersByDriveLinkBookmarksAndNotes() {
        assertEquals(setOf<Long?>(1, 3), ids(LibraryQuery(filter = LibraryFilter.IN_DRIVE)).toSet())
        assertEquals(setOf<Long?>(2, 4), ids(LibraryQuery(filter = LibraryFilter.ONLY_HERE)).toSet())
        val marks = BookMarks(bookmarked = setOf(3L), annotated = setOf(1L, 4L))
        assertEquals(setOf<Long?>(3), ids(LibraryQuery(filter = LibraryFilter.BOOKMARKS), marks).toSet())
        assertEquals(setOf<Long?>(1, 4), ids(LibraryQuery(filter = LibraryFilter.NOTES), marks).toSet())
    }

    @Test
    fun searchIgnoresAccentsAndCombinesWithFilters() {
        assertEquals(listOf<Long?>(1), ids(LibraryQuery(text = "garcia")))
        assertEquals(listOf<Long?>(2), ids(LibraryQuery(text = "algebra")))
        assertEquals(emptyList<Long?>(), ids(LibraryQuery(text = "garcia", filter = LibraryFilter.PDF)))
    }

    @Test
    fun sortsByTitleProgressSizeAndRecency() {
        assertEquals(listOf<Long?>(2, 1, 3, 4), ids(LibraryQuery(sort = LibrarySort.TITLE)))
        assertEquals(listOf<Long?>(2, 1, 3, 4), ids(LibraryQuery(sort = LibrarySort.PROGRESS)))
        assertEquals(listOf<Long?>(2, 3, 1, 4), ids(LibraryQuery(sort = LibrarySort.SIZE)))
        assertEquals(listOf<Long?>(4, 2, 1, 3), ids(LibraryQuery(sort = LibrarySort.DOWNLOADED)))
        // Books never read fall back to their download date.
        assertEquals(listOf<Long?>(4, 1, 2, 3), ids(LibraryQuery(sort = LibrarySort.LAST_READ)))
    }

    @Test
    fun filtersByCollectionMembership() {
        val inShelf = LibraryRules.apply(books, LibraryQuery(), BookMarks(), now, collection = setOf("h1", "h4"))
        assertEquals(setOf<Long?>(1, 4), inShelf.map { it.id }.toSet())
        assertEquals(4, LibraryRules.apply(books, LibraryQuery(), BookMarks(), now, collection = null).size)
        assertEquals(0, LibraryRules.apply(books, LibraryQuery(), BookMarks(), now, collection = emptySet()).size)
    }
}
