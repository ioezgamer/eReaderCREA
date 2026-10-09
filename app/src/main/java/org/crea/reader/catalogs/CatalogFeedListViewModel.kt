/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.catalogs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.opds.OPDS1Parser
import org.readium.r2.opds.OPDS2Parser
import org.readium.r2.shared.opds.ParseData
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.fetchWithDecoder
import org.crea.reader.data.CatalogRepository
import org.crea.reader.data.db.AppDatabase
import org.crea.reader.data.model.Catalog
import org.crea.reader.utils.EventChannel

class CatalogFeedListViewModel(application: Application) : AndroidViewModel(application) {

    private val httpClient = getApplication<org.crea.reader.Application>().readium.httpClient
    private val catalogDao = AppDatabase.getDatabase(application).catalogDao()
    private val repository = CatalogRepository(catalogDao)
    val eventChannel = EventChannel(Channel<Event>(Channel.BUFFERED), viewModelScope)

    val catalogs = getApplication<org.crea.reader.Application>().catalogsState

    fun insertCatalog(catalog: Catalog) = viewModelScope.launch {
        repository.insertCatalog(catalog)
    }

    fun deleteCatalog(id: Long) = viewModelScope.launch {
        repository.deleteCatalog(id)
    }

    /** Replaces the English sample feeds with the Spanish catalogs, keeping the user's own. */
    fun installDefaultCatalogs() = viewModelScope.launch {
        DefaultCatalogs.retiredHrefs.forEach { repository.deleteByHref(it) }
        DefaultCatalogs.all.forEach { entry ->
            repository.deleteByHref(entry.href)
            repository.insertCatalog(Catalog(title = entry.title, href = entry.href, type = entry.type))
        }
    }

    fun parseCatalog(url: String, title: String) = viewModelScope.launch {
        val parseData = parseURL(url)
        parseData.onSuccess { data ->
            val catalog = Catalog(
                title = title,
                href = url,
                type = data.type
            )
            insertCatalog(catalog)
        }
        parseData.onFailure {
            eventChannel.send(Event.FeedListEvent.CatalogParseFailed)
        }
    }

    private suspend fun parseURL(urlString: String): Try<ParseData, Error> {
        val url = AbsoluteUrl(urlString)
            ?: return Try.failure(DebugError("Invalid URL"))

        return httpClient.fetchWithDecoder(HttpRequest(url)) {
            val result = it.body
            if (isJson(result)) {
                OPDS2Parser.parse(result, url)
            } else {
                OPDS1Parser.parse(result, url)
            }
        }
    }

    private fun isJson(byteArray: ByteArray): Boolean {
        return try {
            JSONObject(String(byteArray))
            true
        } catch (e: Exception) {
            false
        }
    }

    sealed class Event {

        sealed class FeedListEvent : Event() {

            object CatalogParseFailed : FeedListEvent()
        }
    }
}

