/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.domain

import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.file.FileSystemError
import org.readium.r2.shared.util.format.Format
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.crea.reader.data.BookRepository
import org.crea.reader.data.model.Book
import org.crea.reader.utils.extensions.readium.authorName
import org.crea.reader.utils.sha256
import org.crea.reader.utils.tryOrLog
import timber.log.Timber

/**
 * Imports publications into "Mi biblioteca": the asset is downloaded or copied to the app storage,
 * checked against the books already there, and only then added to the database.
 *
 * Duplicates (0.9.3): the same file is never added twice, and a book that looks like one already in
 * the library (same identifier, or same title and author) waits for the user to confirm.
 */
class Bookshelf(
    private val bookRepository: BookRepository,
    private val coverStorage: CoverStorage,
    private val publicationOpener: PublicationOpener,
    private val assetRetriever: AssetRetriever,
    private val publicationRetriever: PublicationRetriever,
) {
    sealed class Event {
        data class ImportPublicationSuccess(val title: String?) : Event()

        class ImportPublicationError(
            val error: ImportError,
        ) : Event()

        /** The same file is already in the library: nothing was added. */
        data class AlreadyInLibrary(val title: String?) : Event()

        /** Looks like a book already in the library; [confirmImport] or [discardImport] with [token]. */
        data class PossibleDuplicate(val token: Long, val title: String?, val existingTitle: String?) : Event()
    }

    /** What happened to one import. */
    sealed interface Added {
        data class New(val title: String?) : Added
        data class AlreadyThere(val existing: Book) : Added
        data class Waiting(val token: Long, val title: String?, val existing: Book) : Added
    }

    private class Pending(val url: AbsoluteUrl, val format: Format?, val coverUrl: AbsoluteUrl?)

    val channel: Channel<Event> =
        Channel(Channel.UNLIMITED)

    // An unexpected failure must never take the whole app down (it used to, with "Agregar desde la web").
    private val coroutineScope: CoroutineScope =
        MainScope() + CoroutineExceptionHandler { _, error -> Timber.e(error, "Import failed") }

    /** One import at a time: two copies of the same file picked together cannot both get in. */
    private val importLock = Mutex()
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val nextToken = AtomicLong(1)

    fun importPublicationFromStorage(
        uri: Uri,
    ) {
        coroutineScope.launch {
            addBookFeedback(publicationRetriever.retrieveFromStorage(uri))
        }
    }

    fun importPublicationFromHttp(
        url: AbsoluteUrl,
    ) {
        coroutineScope.launch {
            addBookFeedback(publicationRetriever.retrieveFromHttp(url))
        }
    }

    fun importPublicationFromOpds(
        publication: Publication,
    ) {
        coroutineScope.launch {
            addBookFeedback(publicationRetriever.retrieveFromOpds(publication))
        }
    }

    /** The user confirmed a [Event.PossibleDuplicate]: it is added as a separate book. */
    fun confirmImport(token: Long) {
        val waiting = pending.remove(token) ?: return
        coroutineScope.launch {
            feedback(addBook(waiting.url, waiting.format, waiting.coverUrl, checkSimilar = false))
        }
    }

    /** The user declined a [Event.PossibleDuplicate]: the copied file is removed. */
    fun discardImport(token: Long) {
        val waiting = pending.remove(token) ?: return
        tryOrLog { waiting.url.toFile()?.delete() }
    }

    /**
     * Adds a file downloaded from Drive. Callers have already matched it with the library, so only
     * an identical file is skipped (an update of a book is a different file with the same title).
     */
    suspend fun importPublicationFile(file: File): Try<Unit, ImportError> {
        val result = publicationRetriever.retrieveFromFile(file)
            .getOrElse { return Try.failure(it) }
        return addBook(result.publication.toUrl(isDirectory = false), result.format, result.coverUrl, checkSimilar = false)
            .map { }
    }

    private suspend fun addBookFeedback(
        retrieverResult: Try<PublicationRetriever.Result, ImportError>,
    ) {
        val retrieved = retrieverResult.getOrElse {
            channel.send(Event.ImportPublicationError(it))
            return
        }
        feedback(addBook(retrieved.publication.toUrl(isDirectory = false), retrieved.format, retrieved.coverUrl, checkSimilar = true))
    }

    private suspend fun feedback(result: Try<Added, ImportError>) {
        result
            .onSuccess { added ->
                channel.send(
                    when (added) {
                        is Added.New -> Event.ImportPublicationSuccess(added.title)
                        is Added.AlreadyThere -> Event.AlreadyInLibrary(added.existing.title)
                        is Added.Waiting -> Event.PossibleDuplicate(added.token, added.title, added.existing.title)
                    }
                )
            }
            .onFailure { channel.send(Event.ImportPublicationError(it)) }
    }

    private suspend fun addBook(
        url: AbsoluteUrl,
        format: Format? = null,
        coverUrl: AbsoluteUrl? = null,
        checkSimilar: Boolean,
    ): Try<Added, ImportError> {
        val file = url.toFile()
            ?: return Try.failure(ImportError.InconsistentState(DebugError("Only local files can be added: $url")))
        val contentHash = file.sha256()

        return importLock.withLock {
            bookRepository.getByContentHash(contentHash)?.let { existing ->
                // The same bytes: keep the book (and its notes and progress) that is already there.
                if (existing.url.toFile()?.canonicalPath != file.canonicalPath) tryOrLog { file.delete() }
                return@withLock Try.success(Added.AlreadyThere(existing))
            }

            val asset =
                if (format == null) {
                    assetRetriever.retrieve(url)
                } else {
                    assetRetriever.retrieve(url, format)
                }.getOrElse {
                    return@withLock Try.failure(
                        ImportError.Publication(PublicationError(it))
                    )
                }

            val publication = publicationOpener.open(asset, allowUserInteraction = false)
                .getOrElse {
                    Timber.e("Cannot open publication: $it.")
                    return@withLock Try.failure(ImportError.Publication(PublicationError(it)))
                }

            try {
                if (checkSimilar) {
                    val candidate = DuplicateRules.Candidate(
                        contentHash = contentHash,
                        title = publication.metadata.title,
                        author = publication.metadata.authorName,
                        identifier = publication.metadata.identifier,
                        mediaType = asset.format.mediaType.toString(),
                    )
                    val match = DuplicateRules.find(candidate, bookRepository.allBooksSnapshot())
                    if (match is DuplicateRules.Match.Similar) {
                        val token = nextToken.getAndIncrement()
                        pending[token] = Pending(url, format, coverUrl)
                        return@withLock Try.success(Added.Waiting(token, publication.metadata.title, match.existing))
                    }
                }

                val coverFile =
                    coverStorage.storeCover(publication, coverUrl)
                        .getOrElse {
                            return@withLock Try.failure(
                                ImportError.FileSystem(
                                    FileSystemError.IO(it)
                                )
                            )
                        }

                val id = bookRepository.insertBook(
                    url,
                    asset.format.mediaType,
                    publication,
                    coverFile,
                    contentHash,
                )
                if (id == -1L) {
                    coverFile.delete()
                    return@withLock Try.failure(
                        ImportError.Database(
                            DebugError("Could not insert book into database.")
                        )
                    )
                }
                Try.success(Added.New(publication.metadata.title))
            } finally {
                publication.close()
            }
        }
    }

    suspend fun deleteBook(book: Book) {
        bookRepository.deleteBook(book)
        tryOrLog { book.url.toFile()?.delete() }
        tryOrLog { File(book.cover).delete() }
    }
}
