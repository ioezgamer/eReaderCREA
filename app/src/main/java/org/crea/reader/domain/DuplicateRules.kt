package org.crea.reader.domain

import org.crea.reader.data.model.Book
import org.crea.reader.online.CatalogRows

/**
 * Decides whether a book being imported is already in "Mi biblioteca". Free of Android so it can
 * be tested.
 *
 * - Same bytes (SHA-256): it is the same file. Nothing is added.
 * - Same publication identifier, or same title and author, in the same format: very likely the
 *   same book from another source (a copy re-sent by WhatsApp, re-downloaded, re-saved). The user
 *   decides.
 */
object DuplicateRules {

    sealed interface Match {
        data object None : Match
        data class SameFile(val existing: Book) : Match
        data class Similar(val existing: Book) : Match
    }

    data class Candidate(
        val contentHash: String,
        val title: String?,
        val author: String?,
        val identifier: String?,
        val mediaType: String,
    )

    fun find(candidate: Candidate, books: List<Book>): Match {
        books.firstOrNull { it.contentHash == candidate.contentHash }?.let { return Match.SameFile(it) }
        val sameFormat = books.filter { formatOf(it.rawMediaType) == formatOf(candidate.mediaType) }
        val identifier = candidate.identifier?.trim().orEmpty()
        if (identifier.length >= MIN_IDENTIFIER) {
            sameFormat.firstOrNull { it.identifier.trim().equals(identifier, ignoreCase = true) }
                ?.let { return Match.Similar(it) }
        }
        val title = CatalogRows.fold(candidate.title.orEmpty())
        if (title.length >= MIN_TITLE) {
            val author = CatalogRows.fold(candidate.author.orEmpty())
            sameFormat.firstOrNull {
                CatalogRows.fold(it.title.orEmpty()) == title && CatalogRows.fold(it.author.orEmpty()) == author
            }?.let { return Match.Similar(it) }
        }
        return Match.None
    }

    /** "Title - Author" names and Drive titles compared the same way as local books. */
    fun sameTitleAndAuthor(titleA: String?, authorA: String?, titleB: String?, authorB: String?): Boolean {
        val a = CatalogRows.fold(titleA.orEmpty())
        return a.length >= MIN_TITLE && a == CatalogRows.fold(titleB.orEmpty()) &&
            CatalogRows.fold(authorA.orEmpty()) == CatalogRows.fold(authorB.orEmpty())
    }

    private fun formatOf(mediaType: String): String = when {
        mediaType.contains("epub", ignoreCase = true) -> "epub"
        mediaType.contains("pdf", ignoreCase = true) -> "pdf"
        else -> mediaType.substringBefore(';').lowercase()
    }

    private const val MIN_IDENTIFIER = 6
    private const val MIN_TITLE = 3
}
