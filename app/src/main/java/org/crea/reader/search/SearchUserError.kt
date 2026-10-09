/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.search

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.services.search.SearchError
import org.crea.reader.R
import org.crea.reader.utils.UserError

@OptIn(ExperimentalReadiumApi::class)
fun SearchError.toUserError(): UserError = when (this) {
    is SearchError.Engine -> UserError(R.string.search_error_other, cause = this)
    is SearchError.Reading -> UserError(R.string.search_error_other, cause = this)
}

