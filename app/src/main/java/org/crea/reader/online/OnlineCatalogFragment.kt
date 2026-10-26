package org.crea.reader.online

import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.databinding.FragmentOnlineCatalogBinding
import org.crea.reader.databinding.ItemSheetActionBinding
import org.crea.reader.opds.GridAutoFitLayoutManager
import org.crea.reader.reader.ReaderActivityContract
import org.crea.reader.sync.LibrarySyncEngine
import org.crea.reader.sync.SyncScheduler
import org.crea.reader.sync.SyncStateStore
import org.crea.reader.sync.SyncUi
import org.crea.reader.utils.viewLifecycle
import org.crea.reader.views.CoverSize
import org.crea.reader.bookdetail.BookDetailDialog
import org.crea.reader.views.ViewMode
import org.crea.reader.views.ViewOptions
import org.crea.reader.views.ViewOptionsSheet

/**
 * "Catálogo en línea": every book in the Drive folder, browsable without downloading anything.
 * Each book can be read online (temporary copy) or downloaded to "Mi biblioteca".
 */
class OnlineCatalogFragment : Fragment() {

    private val viewModel: OnlineCatalogViewModel by activityViewModels()
    private var binding: FragmentOnlineCatalogBinding by viewLifecycle()
    private lateinit var adapter: OnlineBookAdapter

    private var catalogView: CatalogView = CatalogView(loaded = false, total = 0, rows = emptyList())
    private var syncState: SyncStateStore.Snapshot? = null
    private var syncProgress: LibrarySyncEngine.Progress? = null
    private var online = true
    private var viewOptions = ViewOptions(ViewMode.LIST, CoverSize.MEDIUM)
    private var knownCategories = emptyList<String>()

    private val app: Application
        get() = requireContext().applicationContext as Application

    private val uiPreferences
        get() = requireContext().getSharedPreferences(UI_PREFERENCES, Context.MODE_PRIVATE)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentOnlineCatalogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val savedSort = runCatching { CatalogSort.valueOf(uiPreferences.getString(KEY_SORT, null).orEmpty()) }
            .getOrDefault(CatalogSort.TITLE)
        if (viewModel.query.value.sort != savedSort && savedInstanceState == null) {
            viewModel.updateQuery { it.copy(sort = savedSort) }
        }

        viewModel.channel.receive(viewLifecycleOwner) { handleEvent(it) }

        viewOptions = ViewOptions.load(requireContext(), KEY_VIEW, ViewMode.LIST)
        adapter = OnlineBookAdapter(
            onOpenDetails = ::showBookSheet,
            onRead = viewModel::readOnline,
            onDownload = ::startDownload,
            onUpdate = ::startUpdate,
            onToggleSelect = viewModel::toggleSelection,
            onCancel = viewModel::cancel,
        )
        binding.catalogList.apply {
            this@OnlineCatalogFragment.adapter.mode = viewOptions.mode
            layoutManager = GridAutoFitLayoutManager(requireContext(), viewOptions.columnWidthDp)
            adapter = this@OnlineCatalogFragment.adapter
            itemAnimator = null
        }
        restoreQueryUi()

        binding.catalogSearch.doAfterTextChanged { text ->
            val value = text?.toString().orEmpty()
            viewModel.updateQuery { it.copy(text = value) }
        }
        binding.catalogFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.catalog_filter_remote -> CatalogFilter.NOT_DOWNLOADED
                R.id.catalog_filter_downloaded -> CatalogFilter.DOWNLOADED
                R.id.catalog_filter_reading -> CatalogFilter.READING
                R.id.catalog_filter_finished -> CatalogFilter.FINISHED
                R.id.catalog_filter_new -> CatalogFilter.NEW
                R.id.catalog_filter_epub -> CatalogFilter.EPUB
                R.id.catalog_filter_pdf -> CatalogFilter.PDF
                else -> CatalogFilter.ALL
            }
            viewModel.updateQuery { it.copy(filter = filter) }
        }
        binding.catalogSortButton.setOnClickListener { showSortMenu(it) }
        binding.catalogRightsNotice.setOnClickListener { CatalogRights.show(requireContext()) }
        binding.catalogViewButton.setOnClickListener {
            ViewOptionsSheet.show(requireContext(), layoutInflater, viewOptions) { applyViewOptions(it) }
        }
        binding.catalogStatusCard.setOnClickListener { findNavController().navigate(R.id.sync) }
        binding.catalogRefresh.setOnRefreshListener { refresh() }
        binding.catalogEmptyAction.setOnClickListener { onEmptyAction() }
        binding.catalogSelectionClose.setOnClickListener { viewModel.clearSelection() }
        binding.catalogSelectionAll.setOnClickListener {
            viewModel.selectAll(catalogView.rows)
        }
        binding.catalogSelectionDownload.setOnClickListener { downloadSelection() }

        syncState = app.syncStateStore.snapshot()
        syncProgress = app.syncEngine.progress.value
        render()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.view.collect { catalogView = it; render() } }
                launch { viewModel.categories.collect { renderCategories(it) } }
                launch {
                    combine(app.syncStateStore.state, app.syncEngine.progress) { state, progress -> state to progress }
                        .collect { (state, progress) ->
                            syncState = state
                            syncProgress = progress
                            if (progress == null) binding.catalogRefresh.isRefreshing = false
                            render()
                        }
                }
                launch { Connectivity.observe(requireContext()).collect { online = it; render() } }
                launch { app.onlineBooks.events.collect(::handleTransferEvent) }
                launch { app.onlineBooks.readyToRead.collect { it?.let(::openWhenReady) } }
                launch { viewModel.selection.collect { renderSelection(it) } }
            }
        }
    }

    // region Rendering

    private fun render() {
        renderStatus()
        val list = catalogView
        val state = syncState
        // Waiting for the very first sync; a failed one or no network falls through to the empty state.
        val firstSyncPending = state != null && state.catalogAvailable && state.lastSync == 0L &&
            state.status != SyncStateStore.STATUS_ERROR && online
        val noBooks = list.loaded && list.total == 0
        val noMatches = list.loaded && list.total > 0 && list.rows.isEmpty()

        adapter.submitList(list.rows)
        binding.catalogLoading.isVisible = !list.loaded || (noBooks && (syncProgress != null || firstSyncPending))
        val showEmpty = (noBooks && !binding.catalogLoading.isVisible) || noMatches
        binding.catalogEmptyState.isVisible = showEmpty
        binding.catalogRefresh.isVisible = !showEmpty && list.loaded && !noBooks

        if (!showEmpty) return
        val action = binding.catalogEmptyAction
        action.icon = null
        action.isVisible = true
        when {
            noMatches -> {
                binding.catalogEmptyTitle.setText(R.string.library_no_results_title)
                binding.catalogEmptyDetail.setText(R.string.library_no_results_detail)
                action.setText(R.string.library_clear_filters)
            }
            state?.catalogAvailable != true -> {
                binding.catalogEmptyTitle.setText(R.string.catalog_off_title)
                binding.catalogEmptyDetail.setText(R.string.catalog_off_detail)
                action.setText(R.string.sync_connect_drive)
            }
            !state.enabled -> {
                binding.catalogEmptyTitle.setText(R.string.catalog_public_empty_title)
                binding.catalogEmptyDetail.setText(R.string.catalog_public_empty_detail)
                action.isVisible = false
            }
            else -> {
                binding.catalogEmptyTitle.setText(R.string.catalog_empty_title)
                binding.catalogEmptyDetail.setText(R.string.catalog_empty_detail)
                action.setText(R.string.sync_open_folder)
            }
        }
    }

    private fun renderStatus() {
        val state = syncState ?: return
        val progress = syncProgress
        val running = progress != null
        binding.catalogStatusSpinner.isVisible = running
        binding.catalogStatusIcon.isVisible = !running
        when {
            progress != null -> {
                binding.catalogStatusTitle.setText(R.string.catalog_status_syncing)
                binding.catalogStatusDetail.text = SyncUi.stageText(requireContext(), progress)
            }
            !state.catalogAvailable -> {
                binding.catalogStatusIcon.setImageResource(R.drawable.ic_cloud_off)
                binding.catalogStatusTitle.setText(R.string.catalog_status_off)
                binding.catalogStatusDetail.setText(R.string.catalog_off_detail)
            }
            !online -> {
                // The saved catalog is still shown, but it may not match Drive any more.
                binding.catalogStatusIcon.setImageResource(R.drawable.ic_cloud_off)
                binding.catalogStatusTitle.setText(R.string.catalog_status_title_offline)
                binding.catalogStatusDetail.text = if (state.lastSync > 0) {
                    getString(R.string.catalog_status_offline, SyncUi.relativeTime(state.lastSync))
                } else {
                    getString(R.string.catalog_status_offline_never)
                }
            }
            state.status == SyncStateStore.STATUS_ERROR -> {
                binding.catalogStatusIcon.setImageResource(R.drawable.ic_error)
                binding.catalogStatusTitle.setText(R.string.catalog_status_error)
                binding.catalogStatusDetail.text = state.error.orEmpty()
            }
            else -> {
                binding.catalogStatusIcon.setImageResource(R.drawable.ic_cloud_done)
                binding.catalogStatusTitle.setText(if (state.enabled) R.string.catalog_status_title_ok else R.string.catalog_status_public_title)
                binding.catalogStatusDetail.text = if (state.lastSync > 0) {
                    getString(R.string.catalog_status_updated, SyncUi.relativeTime(state.lastSync), catalogView.total)
                } else {
                    getString(R.string.sync_waiting_first_run)
                }
            }
        }
    }

    private fun renderCategories(categories: List<String>) {
        knownCategories = categories
        binding.catalogCategoriesScroll.isVisible = categories.isNotEmpty()
        var selected = viewModel.query.value.category
        if (selected != null && categories.none { CategoryPaths.matches(it, selected) }) {
            selected = null
            viewModel.updateQuery { it.copy(category = null) }
        }
        CategoryChips.render(binding.catalogCategories, layoutInflater, categories, selected) { category ->
            viewModel.updateQuery { it.copy(category = category) }
            // Opening a category shows its sub-categories.
            binding.catalogCategories.post { if (view != null) renderCategories(knownCategories) }
        }
    }

    /** The view model outlives this view, so the search box and chips are rebuilt from its query. */
    private fun restoreQueryUi() {
        val query = viewModel.query.value
        binding.catalogSearch.setText(query.text)
        binding.catalogFilters.check(
            when (query.filter) {
                CatalogFilter.ALL -> R.id.catalog_filter_all
                CatalogFilter.NOT_DOWNLOADED -> R.id.catalog_filter_remote
                CatalogFilter.DOWNLOADED -> R.id.catalog_filter_downloaded
                CatalogFilter.READING -> R.id.catalog_filter_reading
                CatalogFilter.FINISHED -> R.id.catalog_filter_finished
                CatalogFilter.NEW -> R.id.catalog_filter_new
                CatalogFilter.EPUB -> R.id.catalog_filter_epub
                CatalogFilter.PDF -> R.id.catalog_filter_pdf
            }
        )
    }

    // endregion

    // region Actions

    private fun refresh() {
        if (syncState?.catalogAvailable != true) {
            binding.catalogRefresh.isRefreshing = false
            Snackbar.make(binding.root, R.string.library_connect_to_refresh, Snackbar.LENGTH_LONG)
                .setAction(R.string.library_connect_action) { findNavController().navigate(R.id.sync) }
                .show()
            return
        }
        SyncScheduler.runNow(requireContext())
        // Hide the indicator if the worker never starts (for example, no network).
        binding.catalogRefresh.postDelayed({
            if (view != null && syncProgress == null) binding.catalogRefresh.isRefreshing = false
        }, REFRESH_GRACE_MS)
    }

    private fun onEmptyAction() {
        val list = catalogView
        when {
            list.total > 0 -> clearFilters()
            syncState?.catalogAvailable != true -> findNavController().navigate(R.id.sync)
            else -> {
                val opened = SyncUi.openDriveFolder(requireContext(), syncState?.folderId)
                if (!opened) Snackbar.make(binding.root, R.string.sync_open_folder_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun applyViewOptions(options: ViewOptions) {
        viewOptions = options
        ViewOptions.save(requireContext(), KEY_VIEW, options)
        adapter.mode = options.mode
        binding.catalogList.layoutManager = GridAutoFitLayoutManager(requireContext(), options.columnWidthDp)
    }

    private fun clearFilters() {
        binding.catalogSearch.text?.clear()
        binding.catalogFilters.check(R.id.catalog_filter_all)
        viewModel.updateQuery { it.copy(text = "", filter = CatalogFilter.ALL, category = null) }
        renderCategories(knownCategories)
    }

    private fun showSortMenu(anchor: View) {
        val current = viewModel.query.value.sort
        androidx.appcompat.widget.PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_sort_catalog, menu)
            menu.findItem(
                when (current) {
                    CatalogSort.TITLE -> R.id.catalog_sort_title
                    CatalogSort.AUTHOR -> R.id.catalog_sort_author
                    CatalogSort.RECENT -> R.id.catalog_sort_recent
                }
            )?.isChecked = true
            setOnMenuItemClickListener { item ->
                val sort = when (item.itemId) {
                    R.id.catalog_sort_author -> CatalogSort.AUTHOR
                    R.id.catalog_sort_recent -> CatalogSort.RECENT
                    else -> CatalogSort.TITLE
                }
                uiPreferences.edit().putString(KEY_SORT, sort.name).apply()
                viewModel.updateQuery { it.copy(sort = sort) }
                binding.catalogList.post { binding.catalogList.scrollToPosition(0) }
                true
            }
        }.show()
    }

private fun showBookSheet(row: CatalogRow) {
        BookDetailDialog.show(childFragmentManager, driveFileId = row.book.driveFileId)
    }

    // region Downloads, updates and multiple selection

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    /** Asks once, the first time a download starts, so its progress can be shown in the notification bar. */
    private fun askForNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val asked = uiPreferences.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (asked || granted) return
        uiPreferences.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startDownload(row: CatalogRow) {
        askForNotificationsOnce()
        viewModel.download(row)
    }

    private fun startUpdate(row: CatalogRow) {
        askForNotificationsOnce()
        viewModel.update(row)
    }

    private fun renderSelection(selection: Set<String>) {
        adapter.selection = selection
        val bar = binding.catalogSelectionBar
        bar.isVisible = selection.isNotEmpty()
        if (selection.isEmpty()) return
        val bytes = viewModel.selectedRows().sumOf { it.book.size }
        binding.catalogSelectionCount.text = getString(
            R.string.selection_count, selection.size, Formatter.formatShortFileSize(requireContext(), bytes)
        )
    }

    private fun downloadSelection() {
        val needed = viewModel.selectedRows().sumOf { it.book.size }
        val free = android.os.StatFs(app.storageDir.path.ifBlank { requireContext().filesDir.path }).availableBytes
        // Each book needs its size plus a margin, and a downloaded book is stored once.
        if (needed + needed / 10 + MIN_FREE_BYTES > free) {
            Snackbar.make(
                binding.root,
                getString(
                    R.string.selection_no_space,
                    Formatter.formatShortFileSize(requireContext(), needed),
                    Formatter.formatShortFileSize(requireContext(), free)
                ),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        askForNotificationsOnce()
        viewModel.downloadSelected()
    }

    // endregion

    // region Events

    /** Opens a book that finished preparing, unless it has been waiting so long the user has moved on. */
    private fun openWhenReady(event: OnlineBookManager.Event.ReadyToRead) {
        app.onlineBooks.consumeReady(event)
        if (System.currentTimeMillis() - event.at <= READY_MAX_AGE_MS) viewModel.openPublication(event.readerId)
    }

    private fun handleTransferEvent(event: OnlineBookManager.Event) {
        when (event) {
            is OnlineBookManager.Event.ReadyToRead -> Unit // delivered through readyToRead
            is OnlineBookManager.Event.Updated ->
                Snackbar.make(binding.root, getString(R.string.catalog_updated_message, event.title), Snackbar.LENGTH_LONG).show()
            is OnlineBookManager.Event.Downloaded ->
                Snackbar.make(binding.root, getString(R.string.catalog_downloaded_message, event.title), Snackbar.LENGTH_LONG)
                    .setAction(R.string.catalog_downloaded_action) { findNavController().navigate(R.id.bookshelf) }
                    .show()
        }
    }

    private fun handleEvent(event: OnlineCatalogViewModel.Event) {
        when (event) {
            is OnlineCatalogViewModel.Event.OpenPublicationError -> event.error.toUserError().show(requireActivity())
            is OnlineCatalogViewModel.Event.LaunchReader ->
                startActivity(ReaderActivityContract().createIntent(requireContext(), event.arguments))
        }
    }

    // endregion

    private companion object {
        const val UI_PREFERENCES = "ui-preferences"
        const val KEY_SORT = "catalog_sort"
        const val KEY_VIEW = "catalog_view"
        const val REFRESH_GRACE_MS = 2500L
        const val READY_MAX_AGE_MS = 2 * 60 * 1000L
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        const val MIN_FREE_BYTES = 20L * 1024 * 1024
    }
}
