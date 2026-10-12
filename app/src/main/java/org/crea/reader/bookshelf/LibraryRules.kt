package org.crea.reader.bookshelf

import java.text.Collator
import org.crea.reader.data.model.Book
import org.crea.reader.online.CatalogRows
import org.crea.reader.online.CategoryPaths
import org.crea.reader.utils.ReadingProgress

enum class LibraryFilter {
    ALL, FAVORITES, READING, UNREAD, FINISHED, RECENT, EPUB, PDF, BOOKMARKS, NOTES, IN_DRIVE, ONLY_HERE
}

enum class LibrarySort { LAST_READ, DOWNLOADED, TITLE, AUTHOR, PROGRESS, SIZE }

data class LibraryQuery(
    val text: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.LAST_READ,
)

/** Which books have bookmarks or notes, for the filters that need them. */
data class BookMarks(val bookmarked: Set<Long> = emptySet(), val annotated: Set<Long> = emptySet())

/** Search, filter and sort for "Mi biblioteca", kept free of Android so it can be tested. */
object LibraryRules {
    const val FINISHED_AT = 98
    const val RECENT_DAYS = 14

    fun apply(
        books: List<Book>,
        query: LibraryQuery,
        marks: BookMarks = BookMarks(),
        now: Long = System.currentTimeMillis(),
        /** Content hashes of the selected collection, or null when no collection is selected. */
        collection: Set<String>? = null,
        /** Selected category path; its sub-categories are included. Null = every book. */
        category: String? = null,
    ): List<Book> {
        val words = CatalogRows.fold(query.text).split(' ').filter { it.isNotEmpty() }
        val recentSince = now - RECENT_DAYS * DAY_MS
        return books
            .filter { book ->
                val key = CatalogRows.fold("${book.title.orEmpty()} ${book.author.orEmpty()}")
                words.all { key.contains(it) } && matches(book, query.filter, marks, recentSince) &&
                    (collection == null || book.contentHash in collection) &&
                    CategoryPaths.matches(book.category, category)
            }
            .sortedWith(comparator(query.sort))
    }

    fun matches(book: Book, filter: LibraryFilter, marks: BookMarks, recentSince: Long): Boolean {
        val progress = ReadingProgress.percent(book.progression)
        return when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.FAVORITES -> book.favorite
            LibraryFilter.READING -> progress in 1 until FINISHED_AT
            LibraryFilter.UNREAD -> progress == 0
            LibraryFilter.FINISHED -> progress >= FINISHED_AT
            LibraryFilter.RECENT -> (book.creation ?: book.updatedAt) >= recentSince
            LibraryFilter.EPUB -> book.rawMediaType.contains("epub", ignoreCase = true)
            LibraryFilter.PDF -> book.rawMediaType.contains("pdf", ignoreCase = true)
            LibraryFilter.BOOKMARKS -> book.id in marks.bookmarked
            LibraryFilter.NOTES -> book.id in marks.annotated
            LibraryFilter.IN_DRIVE -> book.remoteFileId != null
            LibraryFilter.ONLY_HERE -> book.remoteFileId == null
        }
    }

    fun comparator(sort: LibrarySort): Comparator<Book> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return when (sort) {
            LibrarySort.LAST_READ -> compareByDescending<Book> { maxOf(it.progressUpdatedAt, it.creation ?: it.updatedAt) }
            LibrarySort.DOWNLOADED -> compareByDescending<Book> { it.creation ?: it.updatedAt }
            LibrarySort.TITLE -> compareBy<Book, String>(collator) { it.title.orEmpty() }
            LibrarySort.AUTHOR -> compareBy<Book, String>(collator) { it.author.orEmpty() }
                .thenBy(collator) { it.title.orEmpty() }
            LibrarySort.PROGRESS -> compareByDescending<Book> { ReadingProgress.percent(it.progression) }
                .thenBy(collator) { it.title.orEmpty() }
            LibrarySort.SIZE -> compareByDescending<Book> { it.fileSize }
        }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
