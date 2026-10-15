/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.reader.preferences

import android.app.Dialog
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.reader.ReaderViewModel
import org.crea.reader.utils.compose.ComposeBottomSheetDialogFragment

abstract class UserPreferencesBottomSheetDialogFragment(
    @StringRes private val titleRes: Int,
) : ComposeBottomSheetDialogFragment(
    isScrollable = true
) {
    abstract val preferencesModel: UserPreferencesViewModel<*, *>

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            // Reduce the dim to see the impact of the settings on the page.
            window?.setDimAmount(0.1f)

            behavior.apply {
                peekHeight = 1000
                maxHeight = 1000
            }
        }

    @Composable
    override fun Content() {
        UserPreferences(preferencesModel, stringResource(titleRes))
    }
}

class MainPreferencesBottomSheetDialogFragment : UserPreferencesBottomSheetDialogFragment(
    R.string.epubactivity_settings
) {

    private val viewModel: ReaderViewModel by activityViewModels()

    override val preferencesModel: UserPreferencesViewModel<*, *> by lazy {
        checkNotNull(viewModel.settings)
    }

    @Composable
    override fun Content() {
        val look = (LocalContext.current.applicationContext as Application).readerLook
        UserPreferences(
            model = preferencesModel,
            title = stringResource(R.string.epubactivity_settings),
            header = { ReaderLookSection(look) },
        )
    }
}

