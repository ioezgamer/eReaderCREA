package org.crea.reader.readers

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.sync.SyncScheduler

/**
 * Who is reading on a group tablet: the choice of name while nobody is reading, or a short line
 * with the reader and the way to hand the tablet back. Shows nothing on a personal tablet.
 */
@Composable
fun ReaderSessionCard(store: ReaderStore, onOpenProfile: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val profiles by store.profiles.collectAsState()
    val activeId by store.activeId.collectAsState()
    val guest by store.guest.collectAsState()
    store.modeOverride.collectAsState().value
    var creating by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }

    if (!store.isGroupTablet) return
    val active = activeId?.let { id -> profiles.firstOrNull { it.id == id } }

    if (active != null) {
        ActiveReaderBar(
            profile = active,
            onOpenProfile = onOpenProfile,
            onFinish = { finishSession(context, store) },
            modifier = modifier,
        )
    } else {
        ReaderPickerCard(
            choices = store.choices(),
            hasGuestReading = guest?.history?.isNotEmpty() == true,
            onSelect = { store.select(it.id) },
            onNew = { creating = true },
            onSearch = { searching = true },
            modifier = modifier,
        )
    }

    if (creating) {
        NewReaderDialog(
            sameNickname = store::sameNickname,
            groups = store.groups(),
            onCreate = { nickname, avatar, color, group ->
                store.create(nickname, avatar, color, group)
                creating = false
                syncSoon(context)
            },
            onItsMe = {
                store.select(it.id)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
    if (searching) {
        AllReadersDialog(
            profiles = store.choices(),
            onSelect = {
                store.select(it.id)
                searching = false
            },
            onDismiss = { searching = false },
        )
    }
}

/** "Terminé": the reading reaches Drive and the tablet is ready for the next person. */
fun finishSession(context: Context, store: ReaderStore) {
    store.endSession()
    syncSoon(context)
    Toast.makeText(context, R.string.readers_finished_toast, Toast.LENGTH_LONG).show()
}

private fun syncSoon(context: Context) {
    val app = context.applicationContext as Application
    if (app.syncStateStore.isEnabled) SyncScheduler.runSoon(context)
}
