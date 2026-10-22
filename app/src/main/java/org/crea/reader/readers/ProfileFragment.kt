package org.crea.reader.readers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.bookshelf.CoverImages
import org.crea.reader.data.model.Book
import org.crea.reader.online.CatalogRow
import org.crea.reader.online.OnlineBookManager
import org.crea.reader.online.OnlineCatalogViewModel
import org.crea.reader.reader.ReaderActivityContract
import org.crea.reader.reader.toReaderRef

/**
 * "Perfil": what this reader has read, independent of the books stored on the tablet. On a group
 * tablet it also offers "who is reading?" and the way to hand the tablet back.
 */
class ProfileFragment : Fragment() {

    private val viewModel: OnlineCatalogViewModel by activityViewModels()

    private val app: Application
        get() = requireContext().applicationContext as Application

    /** Drive catalog rows (cover, downloaded copy) and the books of this tablet, to show and open. */
    private val rows = MutableStateFlow<Map<String, CatalogRow>>(emptyMap())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ProfileScreen() }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.channel.receive(viewLifecycleOwner) { event ->
            when (event) {
                is OnlineCatalogViewModel.Event.OpenPublicationError -> event.error.toUserError().show(requireActivity())
                is OnlineCatalogViewModel.Event.LaunchReader ->
                    startActivity(ReaderActivityContract().createIntent(requireContext(), event.arguments))
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.readers.expireIfIdle()
                launch { viewModel.rowsById.collect { rows.value = it } }
                launch { app.onlineBooks.readyToRead.collect { event -> event?.let(::openWhenReady) } }
            }
        }
    }

    private fun openWhenReady(event: OnlineBookManager.Event.ReadyToRead) {
        app.onlineBooks.consumeReady(event)
        if (System.currentTimeMillis() - event.at <= READY_MAX_AGE_MS) viewModel.openPublication(event.readerId)
    }

    /** One tap: the downloaded copy, else read it online from Drive, else explain where it is. */
    private fun open(key: String, driveFileId: String?, books: List<Book>) {
        val row = driveFileId?.let { rows.value[it] }
        when {
            row != null -> viewModel.readOnline(row)
            else -> books.firstOrNull { it.toReaderRef()?.key == key }?.id?.let { viewModel.openPublication(it) }
                ?: Toast.makeText(requireContext(), R.string.profile_not_here, Toast.LENGTH_LONG).show()
        }
    }

    private fun coverFor(key: String, driveFileId: String?, books: List<Book>): String? {
        val row = driveFileId?.let { rows.value[it] }
        return row?.local?.cover ?: row?.book?.coverPath ?: books.firstOrNull { it.toReaderRef()?.key == key }?.cover
    }

    @Composable
    private fun ProfileScreen() {
        val store = app.readers
        val profiles by store.profiles.collectAsState()
        val activeId by store.activeId.collectAsState()
        val guest by store.guest.collectAsState()
        store.modeOverride.collectAsState().value
        val books by app.booksState.collectAsState()
        rows.collectAsState().value
        val allBooks = books.orEmpty()

        val group = store.isGroupTablet
        val profile = if (group) activeId?.let { id -> profiles.firstOrNull { it.id == id } } else store.current()
        val shown = profile ?: guest.takeIf { group }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                if (profile != null) {
                    Header(profile, group, store)
                } else {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            stringResource(R.string.profile_guest_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = colorResource(R.color.crea_on_surface),
                        )
                        Text(
                            stringResource(R.string.profile_guest_detail),
                            fontSize = 14.sp,
                            color = colorResource(R.color.crea_on_surface_variant),
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        )
                        ReaderSessionCard(store, onOpenProfile = {})
                    }
                }
            }
            val continueReading = ReaderRules.continueReading(shown)
            val favorites = ReaderRules.favorites(shown)
            val recent = ReaderRules.recent(shown)
            val finished = ReaderRules.finished(shown)
            if (shown != null && recent.isEmpty() && favorites.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.profile_empty),
                        color = colorResource(R.color.crea_on_surface_variant),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (continueReading.isNotEmpty()) item {
                Shelf(stringResource(R.string.profile_continue), continueReading.map { it.toTile(allBooks) }, allBooks)
            }
            if (favorites.isNotEmpty()) item {
                Shelf(stringResource(R.string.profile_favorites), favorites.map { it.toTile(allBooks, shown) }, allBooks)
            }
            if (recent.isNotEmpty()) item {
                Shelf(stringResource(R.string.profile_recent), recent.take(30).map { it.toTile(allBooks) }, allBooks)
            }
            if (finished.isNotEmpty()) item {
                Shelf(stringResource(R.string.profile_finished), finished.map { it.toTile(allBooks) }, allBooks)
            }
            item { CommunityLink() }
            if (group && profile != null) item {
                Button(
                    onClick = { finishSession(requireContext(), store) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorResource(R.color.crea_secondary_container),
                        contentColor = colorResource(R.color.crea_on_secondary_container),
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                ) { Text(stringResource(R.string.profile_finish_button), fontSize = 16.sp) }
            }
        }
    }

    private data class Tile(val key: String, val title: String, val driveFileId: String?, val percent: Int?, val finished: Boolean, val cover: String?)

    private fun ReadEntry.toTile(books: List<Book>) =
        Tile(key, title, driveFileId, percent, finishedAt != null, coverFor(key, driveFileId, books))

    private fun FavoriteMark.toTile(books: List<Book>, profile: ReaderProfile?): Tile {
        val entry = profile?.history?.firstOrNull { it.key == key }
        return Tile(key, title.ifBlank { entry?.title.orEmpty() }, driveFileId, entry?.percent, entry?.finishedAt != null, coverFor(key, driveFileId, books))
    }

    @Composable
    private fun Header(profile: ReaderProfile, group: Boolean, store: ReaderStore) {
        var editing by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var confirm by remember { mutableStateOf<Int?>(null) }
        val finished = ReaderRules.finished(profile).size
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReaderAvatar(profile, 64.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(
                            profile.nickname,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = colorResource(R.color.crea_on_surface),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!group) {
                            Text(stringResource(R.string.profile_personal_detail), fontSize = 13.sp, color = colorResource(R.color.crea_on_surface_variant))
                        }
                    }
                    Box {
                        TextButton(onClick = { menu = true }) {
                            Text("⋯", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = colorResource(R.color.crea_on_surface))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.profile_edit)) }, onClick = { menu = false; editing = true })
                            if (group) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (profile.hidden) R.string.profile_show else R.string.profile_hide)) },
                                    onClick = { menu = false; store.update(profile.id, hidden = !profile.hidden) },
                                )
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.profile_clear)) }, onClick = { menu = false; confirm = R.string.profile_clear_confirm })
                            if (group) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.profile_delete)) }, onClick = { menu = false; confirm = R.string.profile_delete_confirm })
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    Stat(finished, stringResource(R.string.profile_stat_finished))
                    Stat(profile.history.size, stringResource(R.string.profile_stat_books))
                    Stat(ReaderRules.favorites(profile).size, stringResource(R.string.profile_stat_favorites))
                }
            }
        }
        if (editing) {
            EditReaderDialog(profile, onSave = { nickname, avatar ->
                store.update(profile.id, nickname = nickname, avatar = avatar)
                editing = false
            }, onDismiss = { editing = false })
        }
        confirm?.let { message ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                text = { Text(stringResource(message)) },
                confirmButton = {
                    TextButton(onClick = {
                        if (message == R.string.profile_delete_confirm) store.delete(profile.id) else store.clearHistory(profile.id)
                        confirm = null
                    }) { Text(stringResource(R.string.profile_confirm)) }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    }

    @Composable
    private fun Stat(value: Int, label: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = colorResource(R.color.crea_primary))
            Text(label, fontSize = 12.sp, color = colorResource(R.color.crea_on_surface_variant))
        }
    }

    @Composable
    private fun Shelf(title: String, tiles: List<Tile>, books: List<Book>) {
        Column {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = colorResource(R.color.crea_on_surface),
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(tiles, key = { it.key }) { tile -> BookTile(tile) { open(tile.key, tile.driveFileId, books) } }
            }
        }
    }

    @Composable
    private fun BookTile(tile: Tile, onClick: () -> Unit) {
        Column(Modifier.width(104.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
            AndroidView(
                factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                update = { view ->
                    val cover = tile.cover
                    if (cover != null) CoverImages.request(cover).placeholder(R.drawable.cover).into(view) else view.setImageResource(R.drawable.cover)
                },
                modifier = Modifier.width(104.dp).height(150.dp).clip(RoundedCornerShape(12.dp)),
            )
            val percent = tile.percent
            if (tile.finished) {
                Text(stringResource(R.string.profile_finished_label), fontSize = 11.sp, color = colorResource(R.color.crea_green), modifier = Modifier.padding(top = 4.dp))
            } else if (percent != null && percent > 0) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = colorResource(R.color.crea_primary),
                    trackColor = colorResource(R.color.crea_surface_variant),
                )
            }
            Text(
                tile.title,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = colorResource(R.color.crea_on_surface),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    @Composable
    private fun CommunityLink() {
        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { findNavController().navigate(R.id.action_profile_to_community) }
                    .padding(18.dp),
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(colorResource(R.color.crea_primary_container)), contentAlignment = Alignment.Center) {
                    Text("👥", fontSize = 20.sp)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(stringResource(R.string.profile_community), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colorResource(R.color.crea_on_surface))
                    Text(stringResource(R.string.profile_community_detail), fontSize = 13.sp, color = colorResource(R.color.crea_on_surface_variant))
                }
                Text("›", fontSize = 26.sp, color = colorResource(R.color.crea_on_surface_variant))
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    private companion object {
        const val READY_MAX_AGE_MS = 2 * 60 * 1000L
    }
}

/** Change nickname or drawing. */
@Composable
fun EditReaderDialog(profile: ReaderProfile, onSave: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var nickname by remember { mutableStateOf(profile.nickname) }
    var avatar by remember { mutableStateOf(profile.avatar) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_edit)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(ReaderStore.MAX_NICKNAME) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.readers_nickname)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ReaderAvatars.EMOJIS.indices.toList()) { index ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    if (index == avatar) androidx.compose.ui.graphics.Color(ReaderAvatars.color(profile.color))
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable { avatar = index },
                        ) { Text(ReaderAvatars.emoji(index), fontSize = 24.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = nickname.isNotBlank(), onClick = { onSave(nickname, avatar) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
