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

    /** A personal (non-CREA) Google account is connected: its own folder plus the public catalog. */
    val isPersonal: Boolean
        get() = preferences.getString(KEY_ACCOUNT_KIND, KIND_CREA) == KIND_PERSONAL

    /** A CREA account is connected: the shared library, AI and natural voices. */
    val isCrea: Boolean
        get() = isEnabled && !isPersonal

    var folderId: String?
        get() = preferences.getString(KEY_FOLDER_ID, null)
        set(value) = update { putString(KEY_FOLDER_ID, value) }

    /** True once the pre-folder (hidden appData) links have been discarded. */
    /** How many devices use this Google account (from the last sync): many means group tablets. */
    var accountDevices: Int
        get() = preferences.getInt(KEY_ACCOUNT_DEVICES, 0)
        set(value) = preferences.edit().putInt(KEY_ACCOUNT_DEVICES, value).apply()

    var folderMigrated: Boolean
        get() = preferences.getBoolean(KEY_FOLDER_MIGRATED, false)
        set(value) = update { putBoolean(KEY_FOLDER_MIGRATED, value) }

    fun setEnabled(enabled: Boolean) = update { putBoolean(KEY_ENABLED, enabled) }

    /**
     * Connects [account]. A different account than before starts afresh: its folder is looked up
     * again, and the next sync drops the links of downloaded books to the other account's Drive.
     */
    fun connect(account: String, personal: Boolean) = update {
        val previous = preferences.getString(KEY_ACCOUNT, null)
        if (previous != null && !previous.equals(account, ignoreCase = true)) {
            remove(KEY_FOLDER_ID)
            remove(KEY_CATEGORY_FOLDERS)
            remove(KEY_LAST_SYNC)
            putBoolean(KEY_FOLDER_MIGRATED, false)
        }
        putString(KEY_ACCOUNT, account)
        putString(KEY_ACCOUNT_KIND, if (personal) KIND_PERSONAL else KIND_CREA)
        putBoolean(KEY_ENABLED, true)
    }

    /**
     * Books the user asked to upload, by content hash, with the folder (category path) chosen for
     * each; "" is the main folder. Nothing is uploaded without being asked (0.9.3).
     */
    val pendingUploads: Map<String, String>
        get() = decodePairs(preferences.getStringSet(KEY_PENDING_UPLOADS, emptySet()))

    fun requestUpload(contentHash: String, category: String?) = update {
        val current = pendingUploads + (contentHash to category.orEmpty())
        putStringSet(KEY_PENDING_UPLOADS, encodePairs(current))
    }

    fun clearUpload(contentHash: String) = update {
        putStringSet(KEY_PENDING_UPLOADS, encodePairs(pendingUploads - contentHash))
    }

    /** Category path -> Drive folder id, from the last listing; what "Subir a Drive" offers. */
    var categoryFolders: Map<String, String>
        get() = decodePairs(preferences.getStringSet(KEY_CATEGORY_FOLDERS, emptySet()))
        set(value) = update { putStringSet(KEY_CATEGORY_FOLDERS, encodePairs(value)) }

    private fun encodePairs(map: Map<String, String>): Set<String> =
        map.mapTo(mutableSetOf()) { (key, value) -> key + PAIR_SEPARATOR + value }

    private fun decodePairs(values: Set<String>?): Map<String, String> =
        values.orEmpty().mapNotNull { entry ->
            val at = entry.indexOf(PAIR_SEPARATOR)
            if (at < 0) null else entry.substring(0, at) to entry.substring(at + 1)
        }.toMap()

    fun markRunning() = update { putString(KEY_STATUS, STATUS_RUNNING).remove(KEY_ERROR) }

    fun markSuccess(timestamp: Long, bookCount: Int, summary: Summary, account: String?) = update {
        putString(KEY_STATUS, STATUS_SUCCESS)
        putLong(KEY_LAST_SYNC, timestamp)
        putInt(KEY_BOOK_COUNT, bookCount)
        putInt(KEY_ADDED, summary.added)
        putInt(KEY_UPDATED, summary.updated)
        putInt(KEY_UPLOADED, summary.uploaded)
        putInt(KEY_REMOVED, summary.removed)
        putInt(KEY_ALREADY_IN_DRIVE, summary.alreadyInDrive)
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
        personal = preferences.getString(KEY_ACCOUNT_KIND, KIND_CREA) == KIND_PERSONAL,
        lastSummary = Summary(
            added = preferences.getInt(KEY_ADDED, 0),
            updated = preferences.getInt(KEY_UPDATED, 0),
            uploaded = preferences.getInt(KEY_UPLOADED, 0),
            removed = preferences.getInt(KEY_REMOVED, 0),
            alreadyInDrive = preferences.getInt(KEY_ALREADY_IN_DRIVE, 0),
        ),
    )

    data class Summary(
        val added: Int = 0,
        val updated: Int = 0,
        val uploaded: Int = 0,
        val removed: Int = 0,
        /** Uploads asked for but skipped because Drive already had the book. */
        val alreadyInDrive: Int = 0,
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
        /** The connected account is not a CREA one (see [isPersonal]). */
        val personal: Boolean,
        val lastSummary: Summary,
    ) {
        /** A catalog to show: the connected account's Drive, or else the public folder (no account). */
        val catalogAvailable: Boolean get() = enabled || PublicCatalog.isConfigured

        /** A CREA account is connected. */
        val crea: Boolean get() = enabled && !personal
    }

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
        private const val KEY_ACCOUNT_KIND = "account_kind"
        private const val KIND_CREA = "crea"
        private const val KIND_PERSONAL = "personal"
        private const val KEY_FOLDER_ID = "folder_id"
        private const val KEY_FOLDER_MIGRATED = "folder_migrated"
        private const val KEY_ACCOUNT_DEVICES = "account_devices"
        private const val KEY_ADDED = "last_added"
        private const val KEY_UPDATED = "last_updated"
        private const val KEY_UPLOADED = "last_uploaded"
        private const val KEY_REMOVED = "last_removed"
        private const val KEY_ALREADY_IN_DRIVE = "last_already_in_drive"
        private const val KEY_PENDING_UPLOADS = "pending_uploads"
        private const val KEY_CATEGORY_FOLDERS = "category_folders"
        private const val PAIR_SEPARATOR = '\t'
    }
}
