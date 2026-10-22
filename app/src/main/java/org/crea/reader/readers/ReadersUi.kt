package org.crea.reader.readers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.crea.reader.R

/** Round avatar: a drawing on a soft colour (never a photo). */
@Composable
fun ReaderAvatar(profile: ReaderProfile?, size: Dp, modifier: Modifier = Modifier) {
    val background = profile?.let { Color(ReaderAvatars.color(it.color)) } ?: colorResource(R.color.crea_surface_variant)
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = profile?.let { ReaderAvatars.emoji(it.avatar) } ?: "👤",
            fontSize = (size.value * 0.5f).sp,
        )
    }
}

@Composable
internal fun glassBrush(): Brush = Brush.verticalGradient(
    listOf(colorResource(R.color.glass_fill_top), colorResource(R.color.glass_fill))
)

/** A card with the app's glass look. */
@Composable
internal fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(glassBrush())
            .border(BorderStroke(1.dp, colorResource(R.color.glass_stroke)), RoundedCornerShape(24.dp))
    ) { content() }
}

/**
 * "¿Quién lee hoy?" on a group tablet: one tap on your name and your reading waits for you on any
 * tablet. Reading never waits for this; a person can ignore it and read as a guest.
 */
@Composable
fun ReaderPickerCard(
    choices: List<ReaderProfile>,
    hasGuestReading: Boolean,
    onSelect: (ReaderProfile) -> Unit,
    onNew: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSurface = colorResource(R.color.crea_on_surface)
    val muted = colorResource(R.color.crea_on_surface_variant)
    GlassCard(modifier) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                stringResource(R.string.readers_who_reads),
                color = onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Text(
                stringResource(if (hasGuestReading) R.string.readers_who_reads_keep else R.string.readers_who_reads_detail),
                color = muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 12.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { NewReaderChip(onNew) }
                items(choices.take(VISIBLE_CHOICES), key = { it.id }) { profile ->
                    ReaderChip(profile) { onSelect(profile) }
                }
                if (choices.size > VISIBLE_CHOICES) item { SearchChip(onSearch) }
            }
        }
    }
}

@Composable
private fun ReaderChip(profile: ReaderProfile, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        ReaderAvatar(profile, 52.dp)
        Text(
            profile.nickname,
            color = colorResource(R.color.crea_on_surface),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun NewReaderChip(onClick: () -> Unit) {
    ActionChip("＋", stringResource(R.string.readers_new), colorResource(R.color.crea_primary_container), onClick)
}

@Composable
private fun SearchChip(onClick: () -> Unit) {
    ActionChip("🔎", stringResource(R.string.readers_search), colorResource(R.color.crea_surface_variant), onClick)
}

@Composable
private fun ActionChip(symbol: String, label: String, background: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
            Text(symbol, fontSize = 22.sp, color = colorResource(R.color.crea_on_primary_container))
        }
        Text(
            label,
            color = colorResource(R.color.crea_on_surface),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Compact line while someone is reading: who, and the way to hand the tablet back. */
@Composable
fun ActiveReaderBar(profile: ReaderProfile, onOpenProfile: () -> Unit, onFinish: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenProfile)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            ReaderAvatar(profile, 40.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.readers_hello, profile.nickname),
                    color = colorResource(R.color.crea_on_surface),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.readers_hello_detail),
                    color = colorResource(R.color.crea_on_surface_variant),
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onFinish) {
                Text(stringResource(R.string.readers_finish_short), color = colorResource(R.color.crea_primary), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** "Soy nuevo": a nickname and a drawing, nothing else. */
@Composable
fun NewReaderDialog(
    sameNickname: (String) -> List<ReaderProfile>,
    onCreate: (nickname: String, avatar: Int, color: Int) -> Unit,
    onItsMe: (ReaderProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var nickname by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf((ReaderAvatars.EMOJIS.indices).random()) }
    val color = remember { (ReaderAvatars.COLORS.indices).random() }
    var duplicates by remember { mutableStateOf<List<ReaderProfile>>(emptyList()) }

    if (duplicates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { duplicates = emptyList() },
            title = { Text(stringResource(R.string.readers_same_title, nickname.trim())) },
            text = {
                Column {
                    Text(stringResource(R.string.readers_same_detail))
                    Spacer(Modifier.size(12.dp))
                    duplicates.take(4).forEach { profile ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onItsMe(profile) }
                                .padding(8.dp),
                        ) {
                            ReaderAvatar(profile, 40.dp)
                            Text(
                                stringResource(R.string.readers_its_me, profile.nickname),
                                modifier = Modifier.padding(start = 12.dp),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onCreate(nickname.trim(), avatar, color) }) {
                    Text(stringResource(R.string.readers_create_anyway))
                }
            },
            dismissButton = { TextButton(onClick = { duplicates = emptyList() }) { Text(stringResource(R.string.readers_change_name)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.readers_new_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(ReaderStore.MAX_NICKNAME) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.readers_nickname)) },
                    supportingText = { Text(stringResource(R.string.readers_nickname_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.readers_pick_avatar),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(ReaderAvatars.EMOJIS) { index, emoji ->
                        val selected = index == avatar
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (selected) Color(ReaderAvatars.color(color)) else Color.Transparent)
                                .border(
                                    BorderStroke(if (selected) 2.dp else 0.dp, colorResource(R.color.crea_primary)),
                                    CircleShape,
                                )
                                .clickable { avatar = index },
                        ) { Text(emoji, fontSize = 22.sp) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = nickname.isNotBlank(),
                onClick = {
                    val same = sameNickname(nickname)
                    if (same.isEmpty()) onCreate(nickname.trim(), avatar, color) else duplicates = same
                },
            ) { Text(stringResource(R.string.readers_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Every reader of the tablets, with a search box (for when there are many). */
@Composable
fun AllReadersDialog(profiles: List<ReaderProfile>, onSelect: (ReaderProfile) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val wanted = ReaderRules.normalize(query)
    val shown = if (wanted.isEmpty()) profiles else profiles.filter { ReaderRules.normalize(it.nickname).contains(wanted) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.readers_search_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.readers_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { profile ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(profile) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            ReaderAvatar(profile, 40.dp)
                            Text(profile.nickname, modifier = Modifier.padding(start = 12.dp), fontSize = 16.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private const val VISIBLE_CHOICES = 10
