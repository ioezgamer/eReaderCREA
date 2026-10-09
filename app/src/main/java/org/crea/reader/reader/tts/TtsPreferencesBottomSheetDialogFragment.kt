/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.reader.tts

import androidx.fragment.app.activityViewModels
import org.readium.r2.shared.ExperimentalReadiumApi
import org.crea.reader.R
import org.crea.reader.reader.ReaderViewModel
import org.crea.reader.reader.preferences.UserPreferencesBottomSheetDialogFragment
import org.crea.reader.reader.preferences.UserPreferencesViewModel

@OptIn(ExperimentalReadiumApi::class)
class TtsPreferencesBottomSheetDialogFragment : UserPreferencesBottomSheetDialogFragment(
    R.string.tts_settings
) {

    private val viewModel: ReaderViewModel by activityViewModels()

    override val preferencesModel: UserPreferencesViewModel<*, *> by lazy {
        checkNotNull(viewModel.tts!!.preferencesModel)
    }
}

