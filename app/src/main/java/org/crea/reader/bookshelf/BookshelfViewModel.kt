/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.bookshelf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.readium.r2.shared.util.AbsoluteUrl
import org.crea.reader.data.model.Book
import org.crea.reader.reader.OpeningError
import org.crea.reader.reader.ReaderActivityContract
import org.crea.reader.utils.EventChannel

class BookshelfViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() =
        getApplication<org.crea.reader.Application>()

    val channel = EventChannel(Channel<Event>(Channel.BUFFERED), viewModelScope)
    val books = app.booksState

    val bookmarkedIds = app.bookRepository.bookmarkedBookIds()
    val annotatedIds = app.bookRepository.annotatedBookIds()

    fun setFavorite(bookId: Long, favorite: Boolean) {
        viewModelScope.launch { app.bookRepository.setFavorite(bookId, favorite) }
    }

    val collections = app.bookRepository.collections()
    val collectionMembers = app.bookRepository.collectionMembers()

    fun createCollection(name: String, firstBookHash: String? = null) {
        viewModelScope.launch {
            val id = app.bookRepository.createCollection(name)
            if (firstBookHash != null) app.bookRepository.setInCollection(id, firstBookHash, true)
        }
    }

    fun renameCollection(id: Long, name: String) {
        viewModelScope.launch { app.bookRepository.renameCollection(id, name) }
    }

    fun deleteCollection(id: Long) {
        viewModelScope.launch { app.bookRepository.deleteCollection(id) }
    }

    fun setInCollection(collectionId: Long, contentHash: String, member: Boolean) {
        viewModelScope.launch { app.bookRepository.setInCollection(collectionId, contentHash, member) }
    }

    fun deletePublication(book: Book) =
        viewModelScope.launch {
            app.bookshelf.deleteBook(book)
        }

    fun importPublicationFromStorage(uri: Uri) {
        app.bookshelf.importPublicationFromStorage(uri)
    }

    /**
     * "Agregar desde la web": the book is downloaded and imported like any other file. (It used to
     * be added by its web address, which the library cannot keep, and the app closed.)
     */
    fun addPublicationFromWeb(url: AbsoluteUrl) {
        app.bookshelf.importPublicationFromHttp(url)
    }

    fun setCategory(bookId: Long, category: String?) {
        viewModelScope.launch { app.bookRepository.setCategory(bookId, category) }
    }

    /** Asks the next sync to upload this book to the chosen Drive folder (null = the main folder). */
    fun requestUpload(book: Book, category: String?) {
        app.syncStateStore.requestUpload(book.contentHash, category)
        org.crea.reader.sync.SyncScheduler.runNow(app)
    }

    fun openPublication(
        bookId: Long,
    ) {
        viewModelScope.launch {
            app.readerRepository
                .open(bookId)
                .onFailure {
                    channel.send(Event.OpenPublicationError(it))
                }
                .onSuccess {
                    val arguments = ReaderActivityContract.Arguments(bookId)
                    channel.send(Event.LaunchReader(arguments))
                }
        }
    }

    sealed class Event {

        class OpenPublicationError(
            val error: OpeningError,
        ) : Event()

        class LaunchReader(
            val arguments: ReaderActivityContract.Arguments,
        ) : Event()
    }
}

