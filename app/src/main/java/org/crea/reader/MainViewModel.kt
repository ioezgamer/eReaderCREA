/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import org.crea.reader.domain.Bookshelf
import org.crea.reader.domain.ImportError
import org.crea.reader.utils.EventChannel

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app =
        getApplication<org.crea.reader.Application>()

    val channel: EventChannel<Event> =
        EventChannel(Channel(Channel.UNLIMITED), viewModelScope)

    init {
        app.bookshelf.channel.receiveAsFlow()
            .onEach { sendImportFeedback(it) }
            .launchIn(viewModelScope)
    }

    private fun sendImportFeedback(event: Bookshelf.Event) {
        when (event) {
            is Bookshelf.Event.ImportPublicationError -> {
                channel.send(Event.ImportPublicationError(event.error))
            }
            is Bookshelf.Event.ImportPublicationSuccess -> {
                channel.send(Event.ImportPublicationSuccess)
            }
            is Bookshelf.Event.AlreadyInLibrary -> {
                channel.send(Event.AlreadyInLibrary(event.title))
            }
            is Bookshelf.Event.PossibleDuplicate -> {
                channel.send(Event.PossibleDuplicate(event.token, event.title, event.existingTitle))
            }
        }
    }

    fun confirmImport(token: Long) = app.bookshelf.confirmImport(token)

    fun discardImport(token: Long) = app.bookshelf.discardImport(token)

    sealed class Event {

        object ImportPublicationSuccess :
            Event()

        class ImportPublicationError(
            val error: ImportError,
        ) : Event()

        class AlreadyInLibrary(val title: String?) : Event()

        class PossibleDuplicate(val token: Long, val title: String?, val existingTitle: String?) : Event()
    }
}

