package org.crea.reader.online

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.crea.reader.data.BookRepository
import org.crea.reader.data.model.Book
import org.crea.reader.data.model.DriveBook
import org.crea.reader.domain.Bookshelf
import org.crea.reader.sync.DriveAuthorizationProvider
import org.crea.reader.sync.GoogleDriveRestClient
import org.crea.reader.utils.sha256
import org.readium.r2.shared.util.getOrElse
import timber.log.Timber

/**
 * The only place that fetches a book's content from Drive, and only when the user asks:
 *
 * - [download] saves the book into "Mi biblioteca" (app-private storage, readable offline).
 * - [read] fetches a temporary copy into the private cache so the reader can open it; the book
 *   is not added to the library.
 *
 * Transfers run in the app scope, so they continue when the screen is closed (as long as the
 * process lives), are never started twice for the same book, and can be cancelled.
 */
class OnlineBookManager(
    private val context: Context,
    private val bookRepository: BookRepository,
    private val bookshelf: Bookshelf,
    private val cache: OnlineReadingCache,
    private val libraryDir: File,
    private val tempDir: File,
    private val isDriveConnected: () -> Boolean,
    private val openOnlineFiles: suspend () -> Set<File>,
    private val onLibraryChanged: () -> Unit,
    private val onTransferStarted: () -> Unit = {},
    private val scope: CoroutineScope,
) {
    enum class Purpose { DOWNLOAD, READ, UPDATE }

    enum class Failure { NO_CONNECTION, NO_SPACE, SIGN_IN_NEEDED, UNAVAILABLE, CORRUPT, UNKNOWN }

    /** A transfer in flight ([failure] == null) or one that just failed and awaits a retry. */
    data class Transfer(
        val purpose: Purpose,
        val bytes: Long = 0,
        val total: Long = 0,
        val failure: Failure? = null,
    ) {
        val active: Boolean get() = failure == null
        val percent: Int get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    sealed class Event {
        /** The temporary copy is ready: open the reader with [readerId]. */
        class ReadyToRead(
            val driveFileId: String,
            val readerId: Long,
            val at: Long = System.currentTimeMillis(),
        ) : Event()
        class Downloaded(val title: String) : Event()
        class Updated(val title: String) : Event()
    }

    private class TransferFailed(val reason: Failure) : Exception(reason.name)

    private val jobs = ConcurrentHashMap<String, Job>()
    private val gate = Semaphore(MAX_PARALLEL)
    private val importLock = Mutex()

    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val transfers: StateFlow<Map<String, Transfer>> = _transfers.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    private val _ready = MutableStateFlow<Event.ReadyToRead?>(null)

    /**
     * A book that finished preparing and waits to be opened. It stays until a visible screen takes it
     * (so rotating or switching tabs mid-transfer does not lose it); the screen ignores stale ones.
     */
    val readyToRead: StateFlow<Event.ReadyToRead?> = _ready.asStateFlow()

    fun consumeReady(event: Event.ReadyToRead) {
        _ready.compareAndSet(event, null)
    }

    /** One-off notices such as "downloaded"; delivered only to a visible screen. */
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun download(book: DriveBook) = start(book, Purpose.DOWNLOAD)

    fun read(book: DriveBook) = start(book, Purpose.READ)

    fun update(book: DriveBook, local: Book) = start(book, Purpose.UPDATE, local)

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
    }

    fun cancel(driveFileId: String) {
        jobs[driveFileId]?.cancel()
    }

    fun dismissFailure(driveFileId: String) {
        _transfers.update { current ->
            if (current[driveFileId]?.failure != null) current - driveFileId else current
        }
    }

    private fun start(book: DriveBook, purpose: Purpose, local: Book? = null) {
        val id = book.driveFileId
        // Lazy start: the job is registered before it can run, so a second tap never races it.
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { run(book, purpose, local) }
        if (jobs.putIfAbsent(id, job) != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { jobs.remove(id, job) }
        publish(id, Transfer(purpose, total = book.size))
        job.start()
        onTransferStarted()
    }

    private suspend fun run(book: DriveBook, purpose: Purpose, local: Book?) {
        val id = book.driveFileId
        try {
            gate.withPermit {
                when (purpose) {
                    Purpose.READ -> prepareForReading(book)
                    Purpose.DOWNLOAD -> saveToLibrary(book)
                    Purpose.UPDATE -> updateInLibrary(book, requireNotNull(local))
                }
            }
            _transfers.update { it - id }
        } catch (cancelled: CancellationException) {
            _transfers.update { it - id }
            throw cancelled
        } catch (error: Throwable) {
            Timber.w(error, "Transfer of ${book.title} failed")
            publish(id, Transfer(purpose, total = book.size, failure = failureOf(error)))
        }
    }

    private suspend fun prepareForReading(book: DriveBook) {
        val ready = cache.cached(book)
        if (ready != null) {
            cache.touch(ready)
            _ready.value = Event.ReadyToRead(book.driveFileId, book.readerId)
            return
        }
        requireNetwork()
        ensureSpace(cache.ensureDir(), book.size)
        val partial = cache.partialFor(book)
        val target = cache.fileFor(book)
        try {
            fetch(book, partial, Purpose.READ)
            if (!partial.renameTo(target)) throw IOException("Could not store the temporary copy")
        } finally {
            partial.delete()
        }
        cache.cleanUp(protectedFiles = openOnlineFiles() + target)
        _ready.value = Event.ReadyToRead(book.driveFileId, book.readerId)
    }

    private suspend fun saveToLibrary(book: DriveBook) {
        if (bookRepository.getByRemoteFileId(book.driveFileId) != null) return
        tempDir.mkdirs()
        val temp = File(tempDir, "drive-${book.driveFileId}.${book.format.lowercase()}")
        try {
            val cachedCopy = cache.cached(book)
            if (cachedCopy != null) {
                // Already fetched for reading online: reuse it instead of downloading again.
                ensureSpace(tempDir, cachedCopy.length())
                cachedCopy.copyTo(temp, overwrite = true)
            } else {
                requireNetwork()
                ensureSpace(tempDir, book.size)
                ensureSpace(libraryDir, book.size)
                fetch(book, temp, Purpose.DOWNLOAD)
            }
            val hash = temp.sha256()
            // One import at a time: two downloads of identical content must not both pass the duplicate check.
            importLock.withLock {
                val local = bookRepository.getByContentHash(hash) ?: run {
                    bookshelf.importPublicationFile(temp).getOrElse { throw TransferFailed(Failure.CORRUPT) }
                    bookRepository.getByContentHash(hash) ?: throw TransferFailed(Failure.UNKNOWN)
                }
                bookRepository.linkToDrive(hash, book.driveFileId, book.md5, Clock.System.now().toEpochMilliseconds())
                bookRepository.adoptOnlineData(local, book.driveFileId)
            }
        } finally {
            temp.delete()
        }
        _events.emit(Event.Downloaded(book.title))
        onLibraryChanged()
    }

    /** Replaces the downloaded copy with the current version of the Drive file. */
    private suspend fun updateInLibrary(book: DriveBook, old: Book) {
        tempDir.mkdirs()
        val temp = File(tempDir, "drive-${book.driveFileId}-update.${book.format.lowercase()}")
        try {
            requireNetwork()
            ensureSpace(tempDir, book.size)
            ensureSpace(libraryDir, book.size)
            fetch(book, temp, Purpose.UPDATE)
            val hash = temp.sha256()
            importLock.withLock {
                if (hash == old.contentHash) {
                    // Only Drive's bookkeeping changed (same bytes): just remember the new version.
                    bookRepository.setRemoteMd5(requireNotNull(old.id), book.md5)
                } else {
                    val fresh = bookRepository.getByContentHash(hash) ?: run {
                        bookshelf.importPublicationFile(temp).getOrElse { throw TransferFailed(Failure.CORRUPT) }
                        bookRepository.getByContentHash(hash) ?: throw TransferFailed(Failure.UNKNOWN)
                    }
                    bookRepository.replaceWithUpdate(old, fresh, book.driveFileId, book.md5)
                }
            }
        } finally {
            temp.delete()
        }
        _events.emit(Event.Updated(book.title))
        onLibraryChanged()
    }

    /** Streams the Drive file into [destination] with progress, then checks it against Drive's checksum. */
    private suspend fun fetch(book: DriveBook, destination: File, purpose: Purpose) {
        val token = when (val authorization = DriveAuthorizationProvider(context).requestToken()) {
            is DriveAuthorizationProvider.TokenResult.Authorized -> authorization.accessToken
            is DriveAuthorizationProvider.TokenResult.ConsentRequired -> throw TransferFailed(Failure.SIGN_IN_NEEDED)
        }
        var lastPublished = 0L
        try {
            val md5 = GoogleDriveRestClient(token).download(book.driveFileId, destination) { copied, total ->
                val now = System.currentTimeMillis()
                if (now - lastPublished >= PROGRESS_INTERVAL_MS) {
                    lastPublished = now
                    publish(book.driveFileId, Transfer(purpose, copied, if (total > 0) total else book.size))
                }
            }
            if (book.md5 != null && !book.md5.equals(md5, ignoreCase = true)) {
                throw TransferFailed(Failure.CORRUPT)
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun publish(driveFileId: String, transfer: Transfer) {
        _transfers.update { it + (driveFileId to transfer) }
    }

    private fun requireNetwork() {
        if (!isDriveConnected()) throw TransferFailed(Failure.SIGN_IN_NEEDED)
        if (!Connectivity.isOnline(context)) throw TransferFailed(Failure.NO_CONNECTION)
    }

    private suspend fun ensureSpace(dir: File, bytes: Long) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val needed = bytes + maxOf(MIN_FREE_MARGIN, bytes / 10)
        if (StatFs(dir.path).availableBytes < needed) throw TransferFailed(Failure.NO_SPACE)
    }

    private fun failureOf(error: Throwable): Failure = when {
        error is TransferFailed -> error.reason
        error is GoogleDriveRestClient.DriveApiException -> when (error.statusCode) {
            401 -> Failure.SIGN_IN_NEEDED
            403, 404 -> Failure.UNAVAILABLE
            else -> Failure.UNKNOWN
        }
        error is IOException && error.message?.contains("ENOSPC") == true -> Failure.NO_SPACE
        error is IOException -> if (Connectivity.isOnline(context)) Failure.UNKNOWN else Failure.NO_CONNECTION
        else -> Failure.UNKNOWN
    }


    private companion object {
        const val MAX_PARALLEL = 2
        const val PROGRESS_INTERVAL_MS = 150L
        const val MIN_FREE_MARGIN = 20L * 1024 * 1024
    }
}
