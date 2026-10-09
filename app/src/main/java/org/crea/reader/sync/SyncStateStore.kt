package org.crea.reader.sync

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SyncStateStore(context: Context) {
    private val preferences = context.getSharedPreferences("sync-state", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readSnapshot())

    /** Emits a fresh [Snapshot] after every change, so screens update without polling. */
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_DEVICE_ID, it).apply()
            }

    val isEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)

    var folderId: String?
        get() = preferences.getString(KEY_FOLDER_ID, null)
        set(value) = update { putString(KEY_FOLDER_ID, value) }

    /** True once the pre-folder (hidden appData) links have been discarded. */
    var folderMigrated: Boolean
        get() = preferences.getBoolean(KEY_FOLDER_MIGRATED, false)
        set(value) = update { putBoolean(KEY_FOLDER_MIGRATED, value) }

    fun setEnabled(enabled: Boolean) = update { putBoolean(KEY_ENABLED, enabled) }

    fun markRunning() = update { putString(KEY_STATUS, STATUS_RUNNING).remove(KEY_ERROR) }

    fun markSuccess(timestamp: Long, bookCount: Int, summary: Summary, account: String?) = update {
        putString(KEY_STATUS, STATUS_SUCCESS)
        putLong(KEY_LAST_SYNC, timestamp)
        putInt(KEY_BOOK_COUNT, bookCount)
        putInt(KEY_ADDED, summary.added)
        putInt(KEY_UPDATED, summary.updated)
        putInt(KEY_UPLOADED, summary.uploaded)
        putInt(KEY_REMOVED, summary.removed)
        if (account != null) putString(KEY_ACCOUNT, account)
        remove(KEY_ERROR)
    }

    fun markError(message: String) = update {
        putString(KEY_STATUS, STATUS_ERROR).putString(KEY_ERROR, message.take(500))
    }

    fun snapshot(): Snapshot = _state.value

    private fun update(block: android.content.SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(block).apply()
        _state.value = readSnapshot()
    }

    private fun readSnapshot() = Snapshot(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        status = preferences.getString(KEY_STATUS, STATUS_IDLE) ?: STATUS_IDLE,
        lastSync = preferences.getLong(KEY_LAST_SYNC, 0),
        bookCount = preferences.getInt(KEY_BOOK_COUNT, 0),
        error = preferences.getString(KEY_ERROR, null),
        account = preferences.getString(KEY_ACCOUNT, null),
        folderId = preferences.getString(KEY_FOLDER_ID, null),
        lastSummary = Summary(
            added = preferences.getInt(KEY_ADDED, 0),
            updated = preferences.getInt(KEY_UPDATED, 0),
            uploaded = preferences.getInt(KEY_UPLOADED, 0),
            removed = preferences.getInt(KEY_REMOVED, 0),
        ),
    )

    data class Summary(
        val added: Int = 0,
        val updated: Int = 0,
        val uploaded: Int = 0,
        val removed: Int = 0,
    ) {
        val hasChanges get() = added + updated + removed + uploaded > 0
    }

    data class Snapshot(
        val enabled: Boolean,
        val status: String,
        val lastSync: Long,
        val bookCount: Int,
        val error: String?,
        val account: String?,
        val folderId: String?,
        val lastSummary: Summary,
    )

    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_ERROR = "error"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STATUS = "status"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_BOOK_COUNT = "book_count"
        private const val KEY_ERROR = "error"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_FOLDER_ID = "folder_id"
        private const val KEY_FOLDER_MIGRATED = "folder_migrated"
        private const val KEY_ADDED = "last_added"
        private const val KEY_UPDATED = "last_updated"
        private const val KEY_UPLOADED = "last_uploaded"
        private const val KEY_REMOVED = "last_removed"
    }
}
