package org.crea.reader.online

import java.text.Collator
import java.text.Normalizer
import org.crea.reader.BuildConfig
import org.crea.reader.data.model.Book
import org.crea.reader.data.model.BookInsight
import org.crea.reader.data.model.DriveBook
import org.crea.reader.sync.RemoteReading
import org.crea.reader.utils.ReadingProgress

enum class CatalogFilter { ALL, NOT_DOWNLOADED, DOWNLOADED, READING, FINISHED, NEW, EPUB, PDF }

enum class CatalogSort { TITLE, AUTHOR, RECENT }

data class CatalogQuery(
    val text: String = "",
    val filter: CatalogFilter = CatalogFilter.ALL,
    val category: String? = null,
    val sort: CatalogSort = CatalogSort.TITLE,
)

/** One catalog entry as the screen shows it: Drive metadata, its local copy (if any) and any transfer in flight. */
data class CatalogRow(
    val book: DriveBook,
    val local: Book?,
    val transfer: OnlineBookManager.Transfer? = null,
    /** Lower-case, accent-free title, author and category, built once per catalog change. */
    val searchKey: String = "",
    /** What the account's other devices are reading in this book, most recent first. */
    val others: List<RemoteReading> = emptyList(),
    /** What the AI worked out about this book, when it has been analysed. */
    val insight: BookInsight? = null,
) {
    val downloaded: Boolean get() = local != null

    /** The author from the file name or, when there is none, the one the AI found. */
    val displayAuthor: String? get() = book.author?.takeIf { it.isNotBlank() } ?: insight?.author

    /** The Drive sub-folder, or else the first category the AI suggested. */
    val displayCategory: String? get() = book.category ?: insight?.categoryList?.firstOrNull()

    val summary: String? get() = insight?.summary?.takeIf { it.isNotBlank() }

    /** Drive has a different version of the file than the copy on this device. */
    val updateAvailable: Boolean
        get() = local?.remoteMd5 != null && book.md5 != null && !book.md5.equals(local.remoteMd5, ignoreCase = true)

    /** Progress on this device, whether the book is downloaded or was read online. */
    val ownPercent: Int get() = ReadingProgress.percent(local?.progression ?: book.progression)

    private val ownReadingAt: Long get() = local?.progressUpdatedAt ?: book.progressionAt

    /** The overall percentage: the most recent reading on any of the account's devices. */
    val generalPercent: Int
        get() {
            val own = ownPercent.takeIf { it > 0 }?.let { it to ownReadingAt }
            return (listOfNotNull(own) + others.map { it.percent to it.updatedAt }).maxByOrNull { it.second }?.first ?: 0
        }
}

object CatalogRows {
    /**
     * Pairs every catalog entry with the copy in "Mi biblioteca". Books are matched by Drive file
     * id, or by content checksum for copies that were uploaded by this app.
     */
    fun build(
        catalog: List<DriveBook>,
        books: List<Book>,
        readings: List<RemoteReading> = emptyList(),
        insights: Map<String, BookInsight> = emptyMap(),
    ): List<CatalogRow> {
        val readingsByBook = readings.filter { it.percent > 0 }.groupBy { it.driveFileId }
        val byRemoteId = HashMap<String, Book>()
        val byHash = HashMap<String, Book>()
        for (book in books) {
            book.remoteFileId?.let { byRemoteId[it] = book }
            byHash[book.contentHash] = book
        }
        return catalog.map { entry ->
            CatalogRow(
                book = entry,
                local = byRemoteId[entry.driveFileId] ?: entry.contentHash?.let { byHash[it] },
                searchKey = fold(
                    "${entry.title} ${entry.author.orEmpty()} ${entry.category.orEmpty()} " +
                        "${insights[entry.driveFileId]?.let { it.author.orEmpty() + " " + it.categories } ?: ""}"
                ),
                insight = insights[entry.driveFileId]?.takeIf { it.version == entry.version && (BuildConfig.DEBUG || !it.isPlaceholder) },
                others = readingsByBook[entry.driveFileId].orEmpty().sortedByDescending { it.updatedAt },
            )
        }
    }

    fun categories(rows: List<CatalogRow>): List<String> =
        rows.mapNotNull { it.displayCategory }.distinct().sortedWith(collator())

    const val NEW_DAYS = 14

    fun filterAndSort(
        rows: List<CatalogRow>,
        query: CatalogQuery,
        now: Long = System.currentTimeMillis(),
    ): List<CatalogRow> {
        val newSince = now - NEW_DAYS * 24L * 60 * 60 * 1000
        val words = fold(query.text).split(' ').filter { it.isNotEmpty() }
        val collator = collator()
        return rows
            .filter { row ->
                val matchesText = words.all { row.searchKey.contains(it) }
                val matchesFilter = when (query.filter) {
                    CatalogFilter.ALL -> true
                    CatalogFilter.NOT_DOWNLOADED -> !row.downloaded
                    CatalogFilter.DOWNLOADED -> row.downloaded
                    CatalogFilter.READING -> row.generalPercent in 1..97
                    CatalogFilter.FINISHED -> row.generalPercent >= 98
                    CatalogFilter.NEW -> row.book.modifiedAt >= newSince
                    CatalogFilter.EPUB -> row.book.format == "EPUB"
                    CatalogFilter.PDF -> row.book.format == "PDF"
                }
                matchesText && matchesFilter && CategoryPaths.matches(row.displayCategory, query.category)
            }
            .sortedWith(
                when (query.sort) {
                    CatalogSort.TITLE -> compareBy(collator) { it.book.title }
                    CatalogSort.AUTHOR -> compareBy<CatalogRow, String>(collator) { it.book.author.orEmpty() }
                        .thenBy(collator) { it.book.title }
                    CatalogSort.RECENT -> compareByDescending<CatalogRow> { it.book.modifiedAt }
                        .thenBy(collator) { it.book.title }
                }
            )
    }

    /** "García Márquez" and "garcia marquez" are the same search. */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .trim()

    private fun collator(): Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
}
