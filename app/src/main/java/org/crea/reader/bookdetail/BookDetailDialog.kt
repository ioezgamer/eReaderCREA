package org.crea.reader.bookdetail

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.bookshelf.CoverImages
import org.crea.reader.data.model.Book
import org.crea.reader.online.CatalogRow
import org.crea.reader.online.OnlineCatalogViewModel
import org.crea.reader.online.levelLabel
import org.crea.reader.online.levelNote
import org.crea.reader.reader.ReaderActivityContract
import org.crea.reader.reader.toReaderRef
import org.crea.reader.readers.BookRef
import org.crea.reader.readers.GlassCard
import org.crea.reader.readers.ReaderAvatar
import org.crea.reader.readers.ReaderRules
import org.crea.reader.utils.ReadingProgress
import org.readium.r2.shared.publication.Link

/**
 * "Ficha del libro": cover, details, summary and the actions (read, listen, download, favourite) of
 * one book, whether it is only in the Drive catalog or already in "Mi biblioteca". For a downloaded
 * book it also shows its table of contents, bookmarks and notes, each opening the book at that place.
 */
class BookDetailDialog : DialogFragment() {

    private val catalog: OnlineCatalogViewModel by activityViewModels()

    private val app: Application
        get() = requireContext().applicationContext as Application

    private var driveFileId by mutableStateOf<String?>(null)
    private var bookId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.AppTheme_Glass_FullScreen)
        driveFileId = arguments?.getString(ARG_DRIVE)
        bookId = arguments?.getLong(ARG_BOOK)?.takeIf { it != 0L }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Screen() }
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    // region Actions

    /** Opens [id] (a book of the library or an online copy) at [startAt], or where it was left. */
    private fun openReader(id: Long, listen: Boolean = false, startAt: String? = null) {
        lifecycleScope.launch {
            app.readerRepository.open(id, startAt)
                .onSuccess {
                    startActivity(ReaderActivityContract().createIntent(requireContext(), ReaderActivityContract.Arguments(id, listen)))
                    dismissAllowingStateLoss()
                }
                .onFailure { Toast.makeText(requireContext(), R.string.book_detail_open_failed, Toast.LENGTH_LONG).show() }
        }
    }

    /** Read (or listen to) the book: the downloaded copy, or a temporary copy from Drive. */
    private fun read(local: Book?, row: CatalogRow?, listen: Boolean) {
        local?.id?.let { return openReader(it, listen) }
        row ?: return
        // The screen underneath opens the reader once the copy is ready, as for any online book.
        catalog.listenOnOpen = row.book.readerId.takeIf { listen }
        app.onlineBooks.read(row.book)
        Toast.makeText(requireContext(), R.string.book_detail_preparing, Toast.LENGTH_SHORT).show()
        dismiss()
    }

    private fun toggleFavorite(local: Book?, ref: BookRef?, favorite: Boolean) {
        lifecycleScope.launch {
            local?.id?.let { app.bookRepository.setFavorite(it, favorite) }
            ref?.let { app.readers.setFavorite(it, favorite) }
        }
    }

    // endregion

    @Composable
    private fun Screen() {
        val rows by catalog.rowsById.collectAsState(initial = emptyMap())
        val books by app.booksState.collectAsState()
        val profiles by app.readers.profiles.collectAsState()
        app.readers.activeId.collectAsState().value

        val local = bookId?.let { id -> books.orEmpty().firstOrNull { it.id == id } }
            ?: driveFileId?.let { id -> rows[id]?.local }
        val row = driveFileId?.let { rows[it] } ?: local?.remoteFileId?.let { rows[it] }
        val ref = local?.toReaderRef()
            ?: row?.let { BookRef("drive:${it.book.driveFileId}", it.book.title, it.displayAuthor, it.book.driveFileId) }
        if (local == null && row == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.book_detail_loading), color = colorResource(R.color.crea_on_surface_variant))
            }
            return
        }
        val reader = app.readers.current()
        val entry = ref?.let { r -> reader?.history?.firstOrNull { it.key == r.key } }
        val percent = entry?.percent ?: ReadingProgress.percent(local?.progression ?: row?.book?.progression)
        val favorite = if (ref != null && reader != null) ReaderRules.isFavorite(reader, ref.key) else local?.favorite == true
        val title = row?.insight?.title?.takeIf { it.isNotBlank() && !row.insight.isPlaceholder } ?: local?.title ?: row?.book?.title.orEmpty()
        val author = row?.displayAuthor ?: local?.author
        val cover = local?.cover ?: row?.book?.coverPath

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    TextButton(onClick = { dismiss() }) {
                        Text("‹  " + stringResource(R.string.book_detail_back), fontSize = 17.sp, color = colorResource(R.color.crea_on_surface))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { toggleFavorite(local, ref, !favorite) }) {
                        Text(if (favorite) "♥" else "♡", fontSize = 26.sp, color = colorResource(if (favorite) R.color.crea_secondary else R.color.crea_on_surface))
                    }
                }
            }
            item { Header(title, author, cover) }
            item { Actions(local, row, percent) }
            item { InfoCard(local, row) }
            row?.summary?.let { summary ->
                item {
                    Section(stringResource(R.string.book_detail_summary)) {
                        Text(summary, fontSize = 15.sp, lineHeight = 22.sp, color = colorResource(R.color.crea_on_surface))
                        row.insight?.let {
                            Text(
                                levelNote(requireContext(), it.level), fontSize = 12.sp,
                                color = colorResource(R.color.crea_on_surface_variant), modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            val group = ref?.let { r ->
                val mine = reader?.group
                profiles.filter { p ->
                    p.id != reader?.id && !p.hidden && ReaderRules.sameGroup(p.group, mine) && p.history.any { it.key == r.key }
                }.map { p -> p to p.history.first { it.key == r.key } }
            }.orEmpty()
            if (group.isNotEmpty()) item {
                Section(stringResource(R.string.book_detail_group, reader?.group.orEmpty())) {
                    group.forEach { (profile, readEntry) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            ReaderAvatar(profile, 32.dp)
                            Text(
                                profile.nickname, fontWeight = FontWeight.Bold, color = colorResource(R.color.crea_on_surface),
                                modifier = Modifier.padding(start = 10.dp).weight(1f),
                            )
                            Text(
                                if (readEntry.finishedAt != null) stringResource(R.string.profile_finished_label)
                                else stringResource(R.string.profile_percent, readEntry.percent),
                                color = colorResource(R.color.crea_primary),
                            )
                        }
                    }
                }
            }
            if (local?.id != null) item { Contents(local) }
            val similar = similarBooks(row, local, rows.values.toList())
            if (similar.isNotEmpty()) item {
                Column {
                    Text(
                        stringResource(R.string.book_detail_similar), fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = colorResource(R.color.crea_on_surface), modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                    )
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(similar, key = { it.book.driveFileId }) { other ->
                            Column(Modifier.width(96.dp).clickable {
                                driveFileId = other.book.driveFileId
                                bookId = other.local?.id
                            }) {
                                Cover(other.local?.cover ?: other.book.coverPath, Modifier.width(96.dp).height(138.dp))
                                Text(
                                    other.book.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = colorResource(R.color.crea_on_surface), modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(title: String, author: String?, cover: String?) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Cover(cover, Modifier.width(170.dp).height(245.dp))
            Text(
                title, fontWeight = FontWeight.Bold, fontSize = 24.sp, textAlign = TextAlign.Center,
                color = colorResource(R.color.crea_on_surface), modifier = Modifier.padding(top = 18.dp),
            )
            if (!author.isNullOrBlank()) {
                Text(author, fontSize = 16.sp, textAlign = TextAlign.Center, color = colorResource(R.color.crea_on_surface_variant), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    @Composable
    private fun Cover(path: String?, modifier: Modifier) {
        AndroidView(
            factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
            update = { view ->
                if (path != null) CoverImages.request(path).placeholder(R.drawable.cover).into(view) else view.setImageResource(R.drawable.cover)
            },
            modifier = modifier.shadow(10.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)),
        )
    }

    @Composable
    private fun Actions(local: Book?, row: CatalogRow?, percent: Int) {
        val transfer = row?.transfer
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            if (percent in 1..99) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = colorResource(R.color.crea_primary),
                        trackColor = colorResource(R.color.crea_surface_variant),
                    )
                    Text(stringResource(R.string.profile_percent, percent), color = colorResource(R.color.crea_on_surface_variant), modifier = Modifier.padding(start = 10.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { read(local, row, listen = false) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colorResource(R.color.crea_primary), contentColor = colorResource(R.color.crea_on_primary)),
                ) {
                    Text(stringResource(if (percent in 1..99) R.string.book_detail_continue else R.string.book_detail_read), fontSize = 16.sp)
                }
                OutlinedButton(
                    onClick = { read(local, row, listen = true) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    border = BorderStroke(1.5.dp, colorResource(R.color.crea_primary)),
                ) {
                    Text("🎧  " + stringResource(R.string.book_detail_listen), fontSize = 16.sp, color = colorResource(R.color.crea_on_surface))
                }
            }
            if (row != null) {
                val action = when {
                    transfer?.active == true -> stringResource(R.string.catalog_sheet_cancel_title) to { catalog.cancel(row) }
                    row.updateAvailable -> stringResource(R.string.catalog_sheet_update_title) to { catalog.update(row) }
                    !row.downloaded -> stringResource(R.string.book_detail_download, Formatter.formatShortFileSize(requireContext(), row.book.size)) to { catalog.download(row) }
                    else -> null
                }
                action?.let { (label, onClick) ->
                    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(label, color = colorResource(R.color.crea_primary), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    private fun InfoCard(local: Book?, row: CatalogRow?) {
        val context = requireContext()
        val level = row?.insight?.takeIf { !it.isPlaceholder }?.level?.let(::levelLabel)
        val format = row?.book?.format ?: local?.let { if (it.mediaType.matches(org.readium.r2.shared.util.mediatype.MediaType.PDF)) "PDF" else "EPUB" }
        val size = (row?.book?.size ?: local?.fileSize)?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
        val lines = listOfNotNull(
            (row?.displayCategory ?: local?.category)?.let { "📚" to it },
            level?.let { "🎓" to stringResource(R.string.book_detail_level, stringResource(it)) },
            listOfNotNull(format, size).joinToString(" · ").takeIf { it.isNotBlank() }?.let { "📄" to it },
            if (local != null) "📱" to stringResource(R.string.book_detail_here) else "☁️" to stringResource(R.string.book_detail_drive),
            (local?.creation ?: row?.book?.modifiedAt)?.takeIf { it > 0 }?.let {
                "📅" to stringResource(R.string.book_detail_added, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)))
            },
        )
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { (icon, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(icon, fontSize = 16.sp, modifier = Modifier.width(30.dp))
                        Text(text, fontSize = 15.sp, color = colorResource(R.color.crea_on_surface))
                    }
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = colorResource(R.color.crea_on_surface), modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }

    /** Índice / Marcadores / Notas of a downloaded book; each line opens the book there. */
    @Composable
    private fun Contents(book: Book) {
        val id = book.id ?: return
        var tab by remember { mutableStateOf(0) }
        val bookmarks by app.bookRepository.bookmarksForBook(id).collectAsState(initial = emptyList())
        val notes by app.bookRepository.highlightsForBook(id).collectAsState(initial = emptyList())
        var toc by remember(id) { mutableStateOf<List<Pair<String, String>>?>(null) }
        LaunchedEffect(id, tab) { if (tab == 0 && toc == null) toc = loadToc(book) }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colorResource(R.color.crea_surface_variant)).padding(4.dp),
            ) {
                listOf(R.string.book_detail_toc, R.string.book_detail_bookmarks, R.string.book_detail_notes).forEachIndexed { index, label ->
                    val selected = index == tab
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (selected) colorResource(R.color.crea_primary) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { tab = index }.padding(vertical = 9.dp),
                    ) {
                        Text(
                            stringResource(label), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = colorResource(if (selected) R.color.crea_on_primary else R.color.crea_on_surface_variant),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val items: List<Pair<String, String>> = when (tab) {
                0 -> toc.orEmpty()
                1 -> bookmarks.map { (it.resourceTitle.ifBlank { stringResource(R.string.book_detail_bookmark) }) to it.locator.toJSON().toString() }
                else -> notes.map { (it.annotation.ifBlank { it.text.highlight.orEmpty() }.take(140)) to it.locator.toJSON().toString() }
            }
            if (items.isEmpty()) {
                Text(
                    stringResource(
                        when (tab) {
                            0 -> if (toc == null) R.string.book_detail_loading else R.string.book_detail_no_toc
                            1 -> R.string.book_detail_no_bookmarks
                            else -> R.string.book_detail_no_notes
                        }
                    ),
                    color = colorResource(R.color.crea_on_surface_variant), modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            items.take(MAX_LINES).forEach { (label, locator) ->
                Text(
                    label, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colorResource(R.color.crea_on_surface),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { openReader(id, startAt = locator) }.padding(vertical = 10.dp, horizontal = 4.dp),
                )
            }
        }
    }

    /** Chapters of a downloaded book (indented by level), each with the place it starts. */
    private suspend fun loadToc(book: Book): List<Pair<String, String>> = runCatching {
        val asset = app.readium.assetRetriever.retrieve(book.url, book.mediaType).getOrNull() ?: return emptyList()
        val publication = app.readium.publicationOpener.open(asset, allowUserInteraction = false).getOrNull() ?: return emptyList()
        try {
            val out = mutableListOf<Pair<String, String>>()
            fun walk(links: List<Link>, depth: Int) {
                for (link in links) {
                    val locator = publication.locatorFromLink(link)
                    if (locator != null && out.size < MAX_TOC) out += ("    ".repeat(depth) + (link.title ?: "—")) to locator.toJSON().toString()
                    if (depth < 2) walk(link.children, depth + 1)
                }
            }
            walk(publication.tableOfContents, 0)
            out
        } finally {
            publication.close()
        }
    }.getOrDefault(emptyList())

    /** Other Drive books by the same author, then in the same category. */
    private fun similarBooks(row: CatalogRow?, local: Book?, all: List<CatalogRow>): List<CatalogRow> {
        val author = (row?.displayAuthor ?: local?.author)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val category = row?.displayCategory ?: local?.category
        val self = row?.book?.driveFileId ?: local?.remoteFileId
        val others = all.filter { it.book.driveFileId != self }
        val byAuthor = if (author == null) emptyList() else others.filter { it.displayAuthor?.trim()?.lowercase() == author }
        val byCategory = if (category == null) emptyList() else others.filter { it.displayCategory == category && it !in byAuthor }
        return (byAuthor + byCategory).take(MAX_SIMILAR)
    }

    companion object {
        private const val TAG = "book-detail"
        private const val ARG_DRIVE = "drive"
        private const val ARG_BOOK = "book"
        private const val MAX_TOC = 200
        private const val MAX_LINES = 60
        private const val MAX_SIMILAR = 10

        /** Shows the card of a catalog book ([driveFileId]) or of a book of the library ([bookId]). */
        fun show(manager: FragmentManager, driveFileId: String? = null, bookId: Long? = null) {
            if (manager.findFragmentByTag(TAG) != null) return
            BookDetailDialog().apply {
                arguments = bundleOf(ARG_DRIVE to driveFileId, ARG_BOOK to (bookId ?: 0L))
            }.show(manager, TAG)
        }
    }
}
