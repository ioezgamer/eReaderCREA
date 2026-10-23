/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.bookshelf

import android.widget.Toast
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.text.format.Formatter
import android.view.ViewGroup
import android.webkit.URLUtil
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.bookdetail.BookDetailDialog
import org.crea.reader.data.model.Book
import org.crea.reader.data.model.DriveBook
import org.crea.reader.online.OnlineBookManager
import org.crea.reader.data.model.BookCollection
import org.crea.reader.data.model.CollectionBook
import org.crea.reader.databinding.FragmentBookshelfBinding
import org.crea.reader.databinding.ItemSheetActionBinding
import org.crea.reader.databinding.SheetAddBookBinding
import org.crea.reader.databinding.SheetBookActionsBinding
import org.crea.reader.online.CategoryChips
import org.crea.reader.online.CategoryPaths
import org.crea.reader.opds.GridAutoFitLayoutManager
import org.crea.reader.reader.ReaderActivityContract
import org.crea.reader.reader.toReaderRef
import org.crea.reader.sync.LibrarySyncEngine
import org.crea.reader.sync.SyncScheduler
import org.crea.reader.sync.SyncStateStore
import org.crea.reader.sync.SyncUi
import org.crea.reader.utils.viewLifecycle
import org.crea.reader.views.CoverSize
import org.crea.reader.views.ViewMode
import org.crea.reader.views.ViewOptions
import org.crea.reader.views.ViewOptionsSheet
import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl

class BookshelfFragment : Fragment() {

    private inner class OnViewAttachedListener : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            app.readium.onLcpDialogAuthenticationParentAttached(view)
        }

        override fun onViewDetachedFromWindow(view: View) {
            app.readium.onLcpDialogAuthenticationParentDetached()
        }
    }

    private val bookshelfViewModel: BookshelfViewModel by activityViewModels()
    private lateinit var bookshelfAdapter: BookshelfAdapter
    private lateinit var bookPickerLauncher: ActivityResultLauncher<Array<String>>
    private var binding: FragmentBookshelfBinding by viewLifecycle()
    private var onViewAttachedListener: OnViewAttachedListener = OnViewAttachedListener()
    private var allBooks: List<Book> = emptyList()
    private var booksLoaded = false
    private var query = LibraryQuery()
    private var marks = BookMarks()
    private var collections = emptyList<BookCollection>()
    private var members = emptyList<CollectionBook>()
    private var selectedCollection: Long? = null
    private var selectedCategory: String? = null
    private var collectionChips = mapOf<Int, Long>()
    private var viewOptions = ViewOptions(ViewMode.GRID, CoverSize.MEDIUM)
    private var syncRunning = false
    private var driveCatalog: List<DriveBook> = emptyList()
    private var highlights by mutableStateOf<Pair<List<ContinueItem>, List<Book>>>(emptyList<ContinueItem>() to emptyList())

    private val app: Application
        get() = requireContext().applicationContext as Application

    private val uiPreferences
        get() = requireContext().getSharedPreferences(UI_PREFERENCES, Context.MODE_PRIVATE)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentBookshelfBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.addOnAttachStateChangeListener(onViewAttachedListener)
        // Older versions saved RECENT / ADDED; they are now LAST_READ / DOWNLOADED.
        val savedSort = when (val name = uiPreferences.getString(KEY_SORT, null)) {
            "RECENT" -> LibrarySort.LAST_READ
            "ADDED" -> LibrarySort.DOWNLOADED
            else -> LibrarySort.entries.firstOrNull { it.name == name } ?: LibrarySort.LAST_READ
        }
        query = query.copy(sort = savedSort)

        bookshelfViewModel.channel.receive(viewLifecycleOwner) { handleEvent(it) }

        bookshelfAdapter = BookshelfAdapter(
            onBookClick = { book -> book.id?.let { bookshelfViewModel.openPublication(it) } },
            onBookLongClick = { book -> showBookActions(book) }
        )

        bookPickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                uris.forEach { bookshelfViewModel.importPublicationFromStorage(it) }
            }

        bookshelfAdapter.onFavoriteToggle = { book -> book.id?.let { bookshelfViewModel.setFavorite(it, !book.favorite) } }
        viewOptions = ViewOptions.load(requireContext(), KEY_VIEW, ViewMode.GRID)
        binding.bookshelfBookList.apply {
            bookshelfAdapter.mode = viewOptions.mode
            layoutManager = GridAutoFitLayoutManager(requireContext(), viewOptions.columnWidthDp)
            adapter = bookshelfAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 4) binding.bookshelfAddBookFab.shrink()
                    else if (dy < -4) binding.bookshelfAddBookFab.extend()
                }
            })
        }

        // Paint the last known state right away; the collectors below only refine it.
        bookshelfViewModel.books.value?.let {
            booksLoaded = true
            allBooks = it
            applyFilters()
        }
        renderSyncState(app.syncStateStore.snapshot(), app.syncEngine.progress.value)

        // Group tablets: "who is reading today?" (reading never waits for it).
        binding.bookshelfHighlights.setContent {
            val (reading, recent) = highlights
            LibraryHighlights(
                reading = reading,
                recent = recent,
                onOpen = ::openContinue,
                onDetails = { item ->
                    BookDetailDialog.show(childFragmentManager, driveFileId = item.drive?.driveFileId ?: item.book?.remoteFileId, bookId = item.book?.id)
                },
                onBookDetails = { book -> BookDetailDialog.show(childFragmentManager, driveFileId = book.remoteFileId, bookId = book.id) },
            )
        }
        binding.bookshelfReaderCard.setContent {
            org.crea.reader.readers.ReaderSessionCard(app.readers, onOpenProfile = { findNavController().navigate(R.id.community) })
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.readers.expireIfIdle()
                    combine(app.readers.activeId, app.readers.profiles, app.readers.modeOverride) { _, _, _ -> }
                        .collect {
                            binding.bookshelfReaderCard.isVisible = app.readers.isGroupTablet
                            applyFilters()
                        }
                }
                launch {
                    bookshelfViewModel.books.filterNotNull().collectLatest {
                        booksLoaded = true
                        allBooks = it
                        applyFilters()
                    }
                }
                launch {
                    combine(bookshelfViewModel.collections, bookshelfViewModel.collectionMembers) { list, links -> list to links }
                        .collect { (list, links) ->
                            collections = list
                            members = links
                            if (selectedCollection != null && list.none { it.id == selectedCollection }) selectedCollection = null
                            renderCollections()
                            applyFilters()
                        }
                }
                launch {
                    combine(bookshelfViewModel.bookmarkedIds, bookshelfViewModel.annotatedIds) { bookmarked, annotated ->
                        BookMarks(bookmarked.toSet(), annotated.toSet())
                    }.collect {
                        marks = it
                        if (query.filter == LibraryFilter.BOOKMARKS || query.filter == LibraryFilter.NOTES) applyFilters()
                    }
                }
                launch {
                    app.driveCatalogState.filterNotNull().collect {
                        driveCatalog = it
                        renderContinueReading()
                    }
                }
                launch { app.onlineBooks.readyToRead.collect { event -> event?.let(::openWhenReady) } }
                launch {
                    app.insightsState.collect { insights ->
                        bookshelfAdapter.summaries = insights.values
                            .filter { !it.isPlaceholder && it.summary.isNotBlank() }
                            .associate { it.driveFileId to it.summary }
                        renderContinueReading()
                    }
                }
                launch {
                    combine(app.syncStateStore.state, app.syncEngine.progress) { state, progress ->
                        state to progress
                    }.collect { (state, progress) ->
                        renderSyncState(state, progress)
                    }
                }
            }
        }

        binding.bookshelfSearch.doAfterTextChanged {
            query = query.copy(text = it?.toString().orEmpty().trim())
            applyFilters()
        }
        binding.bookshelfFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            query = query.copy(filter = FILTER_CHIPS[checkedIds.firstOrNull()] ?: LibraryFilter.ALL)
            applyFilters()
        }
        binding.bookshelfSortButton.setOnClickListener { showSortMenu(it) }
        binding.bookshelfViewButton.setOnClickListener {
            ViewOptionsSheet.show(requireContext(), layoutInflater, viewOptions) { applyViewOptions(it) }
        }
        binding.bookshelfSyncCard.setOnClickListener {
            findNavController().navigate(R.id.sync)
        }
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitEmptyState() }
        binding.bookshelfRefresh.setOnRefreshListener { refreshFromDrive() }
        binding.bookshelfAddBookFab.setOnClickListener { showAddBookSheet() }
        binding.bookshelfEmptyPrimary.setOnClickListener {
            if (allBooks.isEmpty()) findNavController().navigate(R.id.online_catalog) else clearFilters()
        }
        binding.bookshelfEmptySecondary.setOnClickListener { showAddBookSheet() }
    }

    private fun applyFilters() {
        val inCollection = selectedCollection?.let { id ->
            members.filter { it.collectionId == id }.mapTo(hashSetOf()) { it.contentHash }
        }
        renderCategories()
        val visibleBooks = LibraryRules.apply(allBooks, query, marks, collection = inCollection, category = selectedCategory)
        bookshelfAdapter.submitList(visibleBooks)
        renderStorage()
        renderContinueReading()
        renderEmptyState(visibleBooks.isEmpty())
    }

    /** Space taken by the downloaded books, so the user knows what deleting a few would free. */
    private fun renderStorage() {
        binding.bookshelfStorage.isVisible = allBooks.isNotEmpty()
        if (allBooks.isEmpty()) return
        binding.bookshelfStorage.text = getString(
            R.string.library_storage_used,
            allBooks.size,
            Formatter.formatShortFileSize(requireContext(), allBooks.sumOf { it.fileSize })
        )
    }

    /**
     * "Seguir leyendo": the last books in progress (on a shared device, the current reader's own,
     * and nothing for a guest), plus the books added most recently. Only on the unfiltered library.
     */
    private fun renderContinueReading() {
        val showing = query.text.isBlank() && query.filter == LibraryFilter.ALL && selectedCollection == null && selectedCategory == null
        val summaryOf = { driveFileId: String? -> driveFileId?.let { bookshelfAdapter.summaries[it] } }
        val local = { book: Book, percent: Int ->
            ContinueItem(book.title.orEmpty(), book.author, book.cover, percent, summaryOf(book.remoteFileId), book = book)
        }
        val online = { drive: DriveBook, percent: Int ->
            ContinueItem(drive.title, drive.author, drive.coverPath, percent, summaryOf(drive.driveFileId), drive = drive)
        }
        val downloadedIds = allBooks.mapNotNullTo(hashSetOf()) { it.remoteFileId }
        val catalog = driveCatalog.associateBy { it.driveFileId }
        val reading = when {
            !showing -> emptyList()
            app.readers.isGroupTablet -> {
                val byKey = allBooks.mapNotNull { book -> book.toReaderRef()?.let { it.key to book } }.toMap()
                org.crea.reader.readers.ReaderRules.continueReading(app.readers.current()).mapNotNull { entry ->
                    byKey[entry.key]?.let { local(it, entry.percent) }
                        ?: entry.driveFileId?.let(catalog::get)?.let { online(it, entry.percent) }
                }
            }
            else -> {
                // Downloaded books and books read online here (not downloaded), most recent first.
                val inProgress = { percent: Int -> percent in 1 until LibraryRules.FINISHED_AT }
                val downloaded = allBooks.mapNotNull { book ->
                    val percent = BookshelfAdapter.progressPercent(book.progression)
                    if (inProgress(percent)) book.progressUpdatedAt to local(book, percent) else null
                }
                val readOnline = driveCatalog.filter { it.driveFileId !in downloadedIds }.mapNotNull { drive ->
                    val percent = BookshelfAdapter.progressPercent(drive.progression)
                    if (inProgress(percent)) drive.progressionAt to online(drive, percent) else null
                }
                (downloaded + readOnline).sortedByDescending { it.first }.map { it.second }
            }
        }.take(MAX_CONTINUE)
        val recent = if (showing && allBooks.size >= MIN_FOR_RECENT) {
            allBooks.sortedByDescending { it.creation ?: 0L }.take(MAX_RECENT)
        } else {
            emptyList()
        }
        highlights = reading to recent
        binding.bookshelfHighlights.isVisible = reading.isNotEmpty() || recent.isNotEmpty()
    }

    /** A downloaded book opens right away; one read online is fetched again and opens when ready. */
    private fun openContinue(item: ContinueItem) {
        item.book?.id?.let { return bookshelfViewModel.openPublication(it) }
        val drive = item.drive ?: return
        app.onlineBooks.read(drive)
        Toast.makeText(requireContext(), R.string.book_detail_preparing, Toast.LENGTH_SHORT).show()
    }

    private fun openWhenReady(event: OnlineBookManager.Event.ReadyToRead) {
        app.onlineBooks.consumeReady(event)
        if (System.currentTimeMillis() - event.at <= READY_MAX_AGE_MS) bookshelfViewModel.openPublication(event.readerId)
    }

    /** Centres the empty message between the header (whatever cards it shows) and the bottom bar. */
    private fun fitEmptyState() {
        val empty = binding.bookshelfEmptyState
        val free = (binding.root.height - binding.bookshelfAppbar.bottom - empty.paddingBottom).coerceAtLeast(0)
        if (binding.bookshelfEmptyContent.minimumHeight != free) {
            binding.bookshelfEmptyContent.minimumHeight = free
            binding.bookshelfEmptyContent.requestLayout()
        }
    }

    private fun renderEmptyState(listEmpty: Boolean) {
        // Until the library has been read once, show neither the list nor the empty message.
        binding.bookshelfBookList.isVisible = booksLoaded && !listEmpty
        binding.bookshelfEmptyState.isVisible = booksLoaded && listEmpty
        if (!booksLoaded || !listEmpty) return
        if (allBooks.isEmpty()) {
            binding.bookshelfEmptyTitle.setText(R.string.library_empty_title)
            binding.bookshelfEmptyDetail.setText(R.string.library_empty_detail)
            // Downloading from the catalog is the main way to fill the library.
            binding.bookshelfEmptyPrimary.setText(R.string.library_open_catalog)
            binding.bookshelfEmptyPrimary.setIconResource(R.drawable.ic_nav_online_outline)
            binding.bookshelfEmptySecondary.isVisible = true
            binding.bookshelfEmptySecondary.setText(R.string.add_book)
        } else {
            binding.bookshelfEmptyTitle.setText(R.string.library_no_results_title)
            binding.bookshelfEmptyDetail.setText(R.string.library_no_results_detail)
            binding.bookshelfEmptyPrimary.setText(R.string.library_clear_filters)
            binding.bookshelfEmptyPrimary.icon = null
            binding.bookshelfEmptySecondary.isVisible = false
        }
    }

    private fun renderSyncState(state: SyncStateStore.Snapshot, progress: LibrarySyncEngine.Progress?) {
        syncRunning = progress != null
        binding.bookshelfSyncSpinner.isVisible = syncRunning
        binding.bookshelfSyncIcon.isVisible = !syncRunning
        if (!syncRunning) binding.bookshelfRefresh.isRefreshing = false

        when {
            progress != null -> {
                binding.bookshelfSyncTitle.setText(R.string.library_sync_running)
                binding.bookshelfSyncDetail.text = SyncUi.stageText(requireContext(), progress)
            }
            !state.enabled -> {
                binding.bookshelfSyncIcon.setImageResource(R.drawable.ic_cloud_off)
                binding.bookshelfSyncTitle.setText(R.string.library_sync_off_title)
                binding.bookshelfSyncDetail.setText(R.string.library_sync_off_detail)
            }
            state.status == SyncStateStore.STATUS_ERROR -> {
                binding.bookshelfSyncIcon.setImageResource(R.drawable.ic_error)
                binding.bookshelfSyncTitle.setText(R.string.library_sync_error_title)
                binding.bookshelfSyncDetail.text = state.error.orEmpty()
            }
            else -> {
                binding.bookshelfSyncIcon.setImageResource(R.drawable.ic_cloud_done)
                binding.bookshelfSyncTitle.setText(R.string.library_sync_on_title)
                binding.bookshelfSyncDetail.text = if (state.lastSync > 0) {
                    getString(
                        R.string.library_sync_ok_detail,
                        SyncUi.relativeTime(state.lastSync),
                        state.bookCount
                    )
                } else {
                    getString(R.string.sync_waiting_first_run)
                }
            }
        }
        if (booksLoaded && allBooks.isEmpty()) renderEmptyState(true)
    }

    private fun refreshFromDrive() {
        if (!app.syncStateStore.isEnabled) {
            binding.bookshelfRefresh.isRefreshing = false
            Snackbar.make(binding.root, R.string.library_connect_to_refresh, Snackbar.LENGTH_LONG)
                .setAction(R.string.library_connect_action) {
                    findNavController().navigate(R.id.sync)
                }
                .show()
            return
        }
        SyncScheduler.runNow(requireContext())
        // Hide the indicator if the worker never starts (for example, no network).
        binding.bookshelfRefresh.postDelayed({
            if (view != null && !syncRunning) binding.bookshelfRefresh.isRefreshing = false
        }, REFRESH_GRACE_MS)
    }

    // region Categories

    private fun renderCategories() {
        val paths = allBooks.mapNotNull { it.category }.toSet()
        if (selectedCategory != null && paths.none { CategoryPaths.matches(it, selectedCategory) }) selectedCategory = null
        binding.bookshelfCategoriesScroll.isVisible = paths.isNotEmpty()
        CategoryChips.render(binding.bookshelfCategories, layoutInflater, paths, selectedCategory) { category ->
            selectedCategory = category
            binding.bookshelfCategories.post { if (view != null) applyFilters() }
        }
    }

    /** Every category known on this device: the books' own and the Drive folders. */
    private fun knownCategories(): List<String> {
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        return (allBooks.mapNotNull { it.category } + app.syncStateStore.categoryFolders.keys)
            .distinct()
            .sortedWith(collator)
    }

    private fun showCategoryDialog(book: Book) {
        val id = book.id ?: return
        if (book.remoteFileId != null) {
            // Drive is the source of truth for downloaded books: the folder decides.
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.category_title)
                .setMessage(getString(R.string.category_from_drive, book.category ?: getString(R.string.category_none)))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val options = knownCategories()
        val labels = (listOf(getString(R.string.category_none)) + options).toTypedArray()
        val current = options.indexOf(book.category).let { if (it < 0) 0 else it + 1 }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.category_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                bookshelfViewModel.setCategory(id, if (which == 0) null else options[which - 1])
                dialog.dismiss()
            }
            .setNeutralButton(R.string.category_new) { _, _ ->
                askCollectionName(R.string.category_new, "") { bookshelfViewModel.setCategory(id, it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Uploads only when asked, to the Drive folder chosen here; Drive is checked for copies first. */
    private fun showUploadDialog(book: Book) {
        val folders = app.syncStateStore.categoryFolders.keys.sortedWith(
            java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        )
        val labels = (listOf(getString(R.string.upload_main_folder)) + folders).toTypedArray()
        var choice = folders.indexOf(book.category).let { if (it < 0) 0 else it + 1 }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.upload_title)
            .setSingleChoiceItems(labels, choice) { _, which -> choice = which }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.upload_action) { _, _ ->
                bookshelfViewModel.requestUpload(book, if (choice == 0) null else folders[choice - 1])
                Snackbar.make(binding.root, R.string.upload_queued, Snackbar.LENGTH_LONG).show()
            }
            .show()
    }

    // endregion

    // region Collections

    private fun renderCollections() {
        val group = binding.bookshelfCollections
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        val chips = mutableMapOf<Int, Long>()
        for (collection in collections) {
            val chip = layoutInflater.inflate(R.layout.item_filter_chip, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = collection.name
            chip.isChecked = collection.id == selectedCollection
            chip.setOnLongClickListener {
                showCollectionMenu(chip, collection)
                true
            }
            chips[chip.id] = collection.id
            group.addView(chip)
        }
        collectionChips = chips
        val add = layoutInflater.inflate(R.layout.item_filter_chip, group, false) as Chip
        add.text = getString(R.string.collection_new_chip)
        add.isCheckable = false
        add.setOnClickListener { askCollectionName(R.string.collection_new_title, "") { bookshelfViewModel.createCollection(it) } }
        group.addView(add)
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedCollection = checkedIds.firstOrNull()?.let { collectionChips[it] }
            applyFilters()
        }
    }

    private fun showCollectionMenu(anchor: View, collection: BookCollection) {
        androidx.appcompat.widget.PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.collection_rename)
            menu.add(0, 2, 1, R.string.collection_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> askCollectionName(R.string.collection_rename, collection.name) {
                        bookshelfViewModel.renameCollection(collection.id, it)
                    }
                    else -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.collection_delete_title, collection.name))
                        .setMessage(R.string.collection_delete_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ -> bookshelfViewModel.deleteCollection(collection.id) }
                        .show()
                }
                true
            }
        }.show()
    }

    private fun askCollectionName(title: Int, initial: String, onName: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(initial)
            setSelection(text.length)
            hint = getString(R.string.collection_name_hint)
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_COLLECTION_NAME))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onName)
            }
            .show()
    }

    /** Check the shelves this book belongs to; a new shelf can be created on the way. */
    private fun showCollectionsDialog(book: Book) {
        if (collections.isEmpty()) {
            askCollectionName(R.string.collection_new_title, "") { bookshelfViewModel.createCollection(it, book.contentHash) }
            return
        }
        val member = collections.map { c -> members.any { it.collectionId == c.id && it.contentHash == book.contentHash } }
        val checked = member.toBooleanArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.collection_add_to)
            .setMultiChoiceItems(collections.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNeutralButton(R.string.collection_new_chip) { _, _ ->
                askCollectionName(R.string.collection_new_title, "") { bookshelfViewModel.createCollection(it, book.contentHash) }
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                collections.forEachIndexed { index, collection ->
                    if (checked[index] != member[index]) {
                        bookshelfViewModel.setInCollection(collection.id, book.contentHash, checked[index])
                    }
                }
            }
            .show()
    }

    // endregion

    private fun applyViewOptions(options: ViewOptions) {
        viewOptions = options
        ViewOptions.save(requireContext(), KEY_VIEW, options)
        bookshelfAdapter.mode = options.mode
        binding.bookshelfBookList.layoutManager = GridAutoFitLayoutManager(requireContext(), options.columnWidthDp)
    }

    private fun clearFilters() {
        binding.bookshelfSearch.text?.clear()
        binding.bookshelfFilters.check(R.id.filter_all)
        selectedCategory = null
        applyFilters()
    }

    private fun showSortMenu(anchor: View) {
        androidx.appcompat.widget.PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_sort, menu)
            SORT_MENU.entries.firstOrNull { it.value == query.sort }?.let { menu.findItem(it.key)?.isChecked = true }
            setOnMenuItemClickListener { item ->
                val sort = SORT_MENU[item.itemId] ?: LibrarySort.LAST_READ
                query = query.copy(sort = sort)
                uiPreferences.edit().putString(KEY_SORT, sort.name).apply()
                binding.bookshelfBookList.post { binding.bookshelfBookList.scrollToPosition(0) }
                applyFilters()
                true
            }
        }.show()
    }

    private fun showAddBookSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = SheetAddBookBinding.inflate(layoutInflater)
        bindAction(sheet.addFromDevice, R.drawable.ic_note_add, R.string.add_from_device, R.string.add_from_device_detail) {
            dialog.dismiss()
            bookPickerLauncher.launch(arrayOf("application/epub+zip", "application/pdf"))
        }
        bindAction(sheet.addFromWeb, R.drawable.ic_link, R.string.add_from_web, R.string.add_from_web_detail) {
            dialog.dismiss()
            askForRemoteUrl()
        }
        bindAction(sheet.addFromDrive, R.drawable.ic_nav_online_outline, R.string.add_from_drive, R.string.add_from_drive_detail) {
            dialog.dismiss()
            findNavController().navigate(R.id.online_catalog)
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun showBookActions(book: Book) {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = SheetBookActionsBinding.inflate(layoutInflater)
        sheet.bookSheetTitle.text = book.title
        sheet.bookSheetDetail.text = getString(
            if (book.remoteFileId != null) R.string.book_action_delete_synced else R.string.book_action_delete_local
        )
        bindAction(sheet.bookSheetDetails, R.drawable.ic_baseline_info_24, R.string.book_detail_open, null) {
            dialog.dismiss()
            BookDetailDialog.show(childFragmentManager, driveFileId = book.remoteFileId, bookId = book.id)
        }
        bindAction(
            sheet.bookSheetFavorite,
            if (book.favorite) R.drawable.ic_favorite_outline else R.drawable.ic_favorite,
            if (book.favorite) R.string.favorite_remove else R.string.favorite_add,
            null
        ) {
            dialog.dismiss()
            book.id?.let { bookshelfViewModel.setFavorite(it, !book.favorite) }
        }
        bindAction(sheet.bookSheetCollections, R.drawable.ic_menu_book, R.string.collection_add_to, null) {
            dialog.dismiss()
            showCollectionsDialog(book)
        }
        bindAction(sheet.bookSheetCategory, R.drawable.ic_folder, R.string.category_title, null) {
            dialog.dismiss()
            showCategoryDialog(book)
        }
        sheet.bookSheetCategory.sheetActionDetail.isVisible = true
        sheet.bookSheetCategory.sheetActionDetail.text = book.category ?: getString(R.string.category_none)
        val pendingUpload = book.contentHash in app.syncStateStore.pendingUploads
        sheet.bookSheetUpload.root.isVisible = book.remoteFileId == null && app.syncStateStore.isEnabled
        bindAction(
            sheet.bookSheetUpload,
            R.drawable.ic_cloud_upload,
            R.string.upload_title,
            if (pendingUpload) R.string.upload_pending else R.string.upload_detail,
        ) {
            dialog.dismiss()
            showUploadDialog(book)
        }
        bindAction(sheet.bookSheetDelete, R.drawable.ic_baseline_delete_24, R.string.book_action_delete, null) {
            dialog.dismiss()
            confirmDeleteBook(book)
        }
        sheet.bookSheetDelete.sheetActionTitle.setTextColor(
            com.google.android.material.color.MaterialColors.getColor(sheet.root, androidx.appcompat.R.attr.colorError)
        )
        sheet.bookSheetDelete.sheetActionDetail.isVisible = false
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun bindAction(
        row: ItemSheetActionBinding,
        icon: Int,
        title: Int,
        detail: Int?,
        onClick: () -> Unit,
    ) {
        row.sheetActionIcon.setImageResource(icon)
        row.sheetActionTitle.setText(title)
        row.sheetActionDetail.isVisible = detail != null
        detail?.let { row.sheetActionDetail.setText(it) }
        row.root.setOnClickListener { onClick() }
    }

    @OptIn(DelicateReadiumApi::class)
    private fun askForRemoteUrl() {
        val urlEditText = EditText(requireContext())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.add_from_web))
            .setMessage(R.string.enter_url)
            .setView(urlEditText)
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val url = AbsoluteUrl(urlEditText.text.toString())
                if (url == null || !URLUtil.isValidUrl(urlEditText.text.toString())) {
                    urlEditText.error = getString(R.string.invalid_url)
                    return@setPositiveButton
                }

                bookshelfViewModel.addPublicationFromWeb(url)
            }
            .show()
    }

    private fun handleEvent(event: BookshelfViewModel.Event) {
        when (event) {
            is BookshelfViewModel.Event.OpenPublicationError -> {
                event.error.toUserError().show(requireActivity())
            }

            is BookshelfViewModel.Event.LaunchReader -> {
                val intent = ReaderActivityContract().createIntent(
                    requireContext(),
                    event.arguments
                )
                startActivity(intent)
            }
        }
    }

    private fun confirmDeleteBook(book: Book) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_delete_book_title))
            .setMessage(
                getString(
                    if (book.remoteFileId != null) R.string.book_action_delete_synced
                    else R.string.book_action_delete_local
                )
            )
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            .setPositiveButton(getString(R.string.delete)) { dialog, _ ->
                bookshelfViewModel.deletePublication(book)
                Snackbar.make(binding.root, R.string.book_deleted, Snackbar.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }

    class VerticalSpaceItemDecoration(private val verticalSpaceHeight: Int) :
        RecyclerView.ItemDecoration() {

        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            outRect.bottom = verticalSpaceHeight
        }
    }

    private companion object {
        const val UI_PREFERENCES = "ui-preferences"
        const val KEY_SORT = "bookshelf_sort"
        const val KEY_VIEW = "bookshelf_view"
        const val REFRESH_GRACE_MS = 2500L
        const val MAX_COLLECTION_NAME = 40
        const val MAX_CONTINUE = 5
        const val MAX_RECENT = 10
        const val MIN_FOR_RECENT = 4
        const val READY_MAX_AGE_MS = 2 * 60 * 1000L

        val SORT_MENU = mapOf(
            R.id.sort_recent to LibrarySort.LAST_READ,
            R.id.sort_added to LibrarySort.DOWNLOADED,
            R.id.sort_title to LibrarySort.TITLE,
            R.id.sort_author to LibrarySort.AUTHOR,
            R.id.sort_progress to LibrarySort.PROGRESS,
            R.id.sort_size to LibrarySort.SIZE,
        )

        val FILTER_CHIPS = mapOf(
            R.id.filter_all to LibraryFilter.ALL,
            R.id.filter_favorites to LibraryFilter.FAVORITES,
            R.id.filter_reading to LibraryFilter.READING,
            R.id.filter_unread to LibraryFilter.UNREAD,
            R.id.filter_finished to LibraryFilter.FINISHED,
            R.id.filter_recent to LibraryFilter.RECENT,
            R.id.filter_epub to LibraryFilter.EPUB,
            R.id.filter_pdf to LibraryFilter.PDF,
            R.id.filter_bookmarks to LibraryFilter.BOOKMARKS,
            R.id.filter_notes to LibraryFilter.NOTES,
            R.id.filter_drive to LibraryFilter.IN_DRIVE,
            R.id.filter_here to LibraryFilter.ONLY_HERE,
        )
    }
}
