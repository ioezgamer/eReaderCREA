/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.reader

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.crea.reader.Application
import org.crea.reader.ai.AiSheets
import org.crea.reader.ai.ChapterText
import org.readium.r2.shared.publication.allAreHtml
import org.readium.r2.lcp.lcpLicense
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.preferences.Configurable
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.crea.reader.R
import org.crea.reader.reader.preferences.MainPreferencesBottomSheetDialogFragment
import org.crea.reader.utils.UserError

/*
 * Base reader fragment class
 *
 * Provides common menu items and saves last location on stop.
 */
@OptIn(ExperimentalReadiumApi::class)
abstract class BaseReaderFragment : Fragment() {

    val model: ReaderViewModel by activityViewModels()
    protected val publication: Publication get() = model.publication

    protected abstract val navigator: Navigator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        model.fragmentChannel.receive(this) { event ->
            fun toast(id: Int) {
                Toast.makeText(requireContext(), getString(id), Toast.LENGTH_SHORT).show()
            }

            when (event) {
                is ReaderViewModel.FragmentFeedback.BookmarkFailed -> toast(
                    R.string.bookmark_exists
                )
                is ReaderViewModel.FragmentFeedback.BookmarkSuccessfullyAdded -> toast(
                    R.string.bookmark_added
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        watchAiAvailability()

        val menuHost: MenuHost = requireActivity()

        menuHost.addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_reader, menu)

                    menu.findItem(R.id.settings).isVisible =
                        navigator is Configurable<*, *>

                    menu.findItem(R.id.ai).isVisible = aiAvailableForThisBook()

                    menu.findItem(R.id.drm).isVisible =
                        model.publication.lcpLicense != null
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    when (menuItem.itemId) {
                        R.id.toc -> {
                            model.activityChannel.send(
                                ReaderViewModel.ActivityCommand.OpenOutlineRequested
                            )
                        }
                        R.id.info -> {
                            PublicationMetadataDialogFragment()
                                .show(childFragmentManager, "Info")
                        }
                        R.id.bookmark -> {
                            model.insertBookmark(navigator.currentLocator.value)
                        }
                        R.id.ai -> showAiMenu()
                        R.id.settings -> {
                            MainPreferencesBottomSheetDialogFragment()
                                .show(childFragmentManager, "Settings")
                        }
                        R.id.drm -> {
                            model.activityChannel.send(
                                ReaderViewModel.ActivityCommand.OpenDrmManagementRequested
                            )
                        }
                        else -> return false
                    }
                    return true
                }
            },
            viewLifecycleOwner
        )
    }

    // region AI assistant

    private val app get() = requireContext().applicationContext as Application

    /** The assistant works on text: books made of HTML (EPUB), not scanned or fixed-layout PDFs. */
    private fun aiAvailableForThisBook() =
        app.aiService.availability.available.value && publication.readingOrder.allAreHtml

    private fun watchAiAvailability() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.aiService.availability.refresh()
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.aiService.availability.available.collect { requireActivity().invalidateOptionsMenu() }
            }
        }
    }

    private fun showAiMenu() {
        val options = arrayOf(getString(R.string.ai_menu_chapter_summary), getString(R.string.ai_menu_teacher))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_menu_title)
            .setItems(options) { _, which -> openChapterTool(teacher = which == 1) }
            .show()
    }

    private fun openChapterTool(teacher: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val chapter = ChapterText.read(publication, navigator.currentLocator.value)
            if (chapter == null) {
                Toast.makeText(requireContext(), R.string.ai_no_text, Toast.LENGTH_LONG).show()
                return@launch
            }
            val title = publication.metadata.title
            if (teacher) {
                AiSheets.showTeacherGuide(requireContext(), layoutInflater, viewLifecycleOwner.lifecycleScope, app.aiService, chapter.text, title, chapter.title)
            } else {
                AiSheets.showChapterSummary(requireContext(), layoutInflater, viewLifecycleOwner.lifecycleScope, app.aiService, chapter.text, title, chapter.title)
            }
        }
    }

    // endregion

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        setMenuVisibility(!hidden)
        requireActivity().invalidateOptionsMenu()
    }

    open fun go(locator: Locator, animated: Boolean) {
        navigator.go(locator, animated)
    }

    protected fun showError(error: UserError) {
        val activity = activity ?: return
        error.show(activity)
    }
}

