package org.crea.reader.sync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.core.net.toUri
import org.crea.reader.R

/** Text and links shared by every screen that shows the Drive sync state. */
object SyncUi {
    fun stageText(context: Context, progress: LibrarySyncEngine.Progress): String = when (progress.stage) {
        LibrarySyncEngine.Stage.CONNECTING -> context.getString(R.string.sync_stage_connecting)
        LibrarySyncEngine.Stage.CHECKING -> context.getString(R.string.sync_stage_checking)
        LibrarySyncEngine.Stage.COVERS ->
            context.getString(R.string.sync_stage_covers, progress.current, progress.total)
        LibrarySyncEngine.Stage.UPLOADING ->
            context.getString(R.string.sync_stage_uploading, progress.current, progress.total)
        LibrarySyncEngine.Stage.FINISHING -> context.getString(R.string.sync_stage_finishing)
    }

    fun relativeTime(timestamp: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )

    /** Opens the "CREA Reader" folder in the Drive app or browser. */
    fun openDriveFolder(context: Context, folderId: String?): Boolean {
        val url = if (folderId != null) {
            "https://drive.google.com/drive/folders/$folderId"
        } else {
            "https://drive.google.com/drive/my-drive"
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
