package org.crea.reader.online

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.crea.reader.reader.OpeningError
import org.crea.reader.reader.ReaderActivityContract
import org.crea.reader.utils.EventChannel

/** What the catalog screen renders; [loaded] is false until the local catalog has been read once. */
data class CatalogView(
    val loaded: Boolean,
    /** Entries in the catalog before any search or filter. */
    val total: Int,
    val rows: List<CatalogRow>,
)

class OnlineCatalogViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<org.crea.reader.Application>()

    val channel = EventChannel(Channel<Event>(Channel.BUFFERED), viewModelScope)

    val query = MutableStateFlow(CatalogQuery())

    private val allRows: Flow<List<CatalogRow>?> =
        combine(app.driveCatalogState, app.booksState, app.deviceReadingsStore.readings, app.insightsState) { catalog, books, readings, insights ->
            if (catalog == null || books == null) null else CatalogRows.build(catalog, books, readings, insights)
        }.flowOn(Dispatchers.Default)

    val categories: Flow<List<String>> = allRows
        .map { rows -> rows?.let(CatalogRows::categories).orEmpty() }
        .distinctUntilChanged()

    /** Every catalog row (unfiltered), kept for actions on a selection that a filter may hide. */
    private var latestRows: List<CatalogRow> = emptyList()

    private val filtered: Flow<CatalogView> = combine(allRows, query) { rows, query ->
        latestRows = rows.orEmpty()
        if (rows == null) {
            CatalogView(loaded = false, total = 0, rows = emptyList())
        } else {
            CatalogView(loaded = true, total = rows.size, rows = CatalogRows.filterAndSort(rows, query))
        }
    }.flowOn(Dispatchers.Default)

    /** The filtered list with the transfers in flight laid over it, so progress never re-runs the search. */
    val view: Flow<CatalogView> = combine(filtered, app.onlineBooks.transfers) { view, transfers ->
        if (transfers.isEmpty()) {
            view
        } else {
            view.copy(rows = view.rows.map { row ->
                transfers[row.book.driveFileId]?.let { row.copy(transfer = it) } ?: row
            })
        }
    }

    /** Every catalog row by Drive id with transfers laid over it, for screens that look books up (the community tab). */
    val rowsById: Flow<Map<String, CatalogRow>> = combine(allRows, app.onlineBooks.transfers) { rows, transfers ->
        rows.orEmpty().associateBy({ it.book.driveFileId }) { row ->
            transfers[row.book.driveFileId]?.let { row.copy(transfer = it) } ?: row
        }
    }

    fun updateQuery(change: (CatalogQuery) -> CatalogQuery) = query.update(change)

    fun readOnline(row: CatalogRow) {
        val local = row.local
        if (local != null) openLocal(row) else app.onlineBooks.read(row.book)
    }

    fun download(row: CatalogRow) {
        if (!row.downloaded) app.onlineBooks.download(row.book)
    }

    fun openLocal(row: CatalogRow) {
        row.local?.id?.let { openPublication(it) }
    }

    fun cancel(row: CatalogRow) = app.onlineBooks.cancel(row.book.driveFileId)

    fun update(row: CatalogRow) {
        val local = row.local ?: return
        app.onlineBooks.update(row.book, local)
    }

    // Multi-select download

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: kotlinx.coroutines.flow.StateFlow<Set<String>> = _selection

    /** Only books that are neither on the device nor already transferring can be picked. */
    fun canSelect(row: CatalogRow): Boolean = !row.downloaded && row.transfer?.active != true

    fun toggleSelection(row: CatalogRow) {
        if (!canSelect(row)) return
        _selection.update { if (row.book.driveFileId in it) it - row.book.driveFileId else it + row.book.driveFileId }
    }

    fun selectAll(rows: List<CatalogRow>) {
        _selection.value = rows.filter(::canSelect).mapTo(linkedSetOf()) { it.book.driveFileId }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** Starts the downloads of the selected books; the manager runs two at a time and queues the rest. */
    fun selectedRows(): List<CatalogRow> = latestRows.filter { it.book.driveFileId in _selection.value }

    fun downloadSelected() {
        val chosen = _selection.value
        latestRows.filter { it.book.driveFileId in chosen && canSelect(it) }.forEach { app.onlineBooks.download(it.book) }
        clearSelection()
    }

    /** Reader id of an online book asked to be listened to ("Escuchar"), started when it opens. */
    var listenOnOpen: Long? = null

    fun openPublication(bookId: Long) {
        val listen = listenOnOpen == bookId
        if (listen) listenOnOpen = null
        viewModelScope.launch {
            app.readerRepository
                .open(bookId)
                .onFailure { channel.send(Event.OpenPublicationError(it)) }
                .onSuccess { channel.send(Event.LaunchReader(ReaderActivityContract.Arguments(bookId, listen))) }
        }
    }

    sealed class Event {
        class OpenPublicationError(val error: OpeningError) : Event()
        class LaunchReader(val arguments: ReaderActivityContract.Arguments) : Event()
    }
}
