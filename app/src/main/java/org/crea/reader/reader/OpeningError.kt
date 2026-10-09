/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.reader

import org.readium.r2.lcp.LcpError
import org.readium.r2.shared.util.Error
import org.crea.reader.R
import org.crea.reader.domain.toUserError
import org.crea.reader.utils.UserError

sealed class OpeningError(
    override val cause: Error?,
) : Error {

    override val message: String =
        "Could not open publication"

    class PublicationError(
        override val cause: org.crea.reader.domain.PublicationError,
    ) : OpeningError(cause)

    class RestrictedPublication(
        cause: Error,
    ) : OpeningError(cause)

    /** The book disappeared (deleted, or removed from the catalog) before it could be opened. */
    data object BookNotFound : OpeningError(null)

    class CannotRender(cause: Error) :
        OpeningError(cause)

    class AudioEngineInitialization(
        cause: Error,
    ) : OpeningError(cause)

    fun toUserError(): UserError =
        when (this) {
            is AudioEngineInitialization ->
                UserError(R.string.opening_publication_audio_engine_initialization, cause = this)
            is PublicationError ->
                cause.toUserError()
            is RestrictedPublication ->
                (cause as? LcpError)?.toUserError()
                    ?: UserError(R.string.publication_error_restricted, cause = this)
            is BookNotFound ->
                UserError(R.string.opening_publication_not_found, cause = this)
            is CannotRender ->
                UserError(R.string.opening_publication_cannot_render, cause = this)
        }
}

