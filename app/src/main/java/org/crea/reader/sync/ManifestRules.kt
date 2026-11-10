package org.crea.reader.sync

/**
 * Rules for the sync manifest in Drive's app folder, kept apart from [LibrarySyncEngine] so they can
 * be tested on the JVM.
 *
 * Drive does not lock files: two devices that sync at the same moment can each create a manifest (so
 * there are two with the same name) or write one over the other. These rules make both cases safe:
 * every copy is read and combined, the newest one is the one written, and a write is abandoned when
 * someone else changed the manifest since it was read (the next sync starts again from it).
 */
object ManifestRules {

    /** The manifest to keep writing to when Drive holds more than one: the most recently modified. */
    fun primary(files: List<GoogleDriveRestClient.DriveFile>): GoogleDriveRestClient.DriveFile? =
        files.maxWithOrNull(compareBy<GoogleDriveRestClient.DriveFile> { it.modifiedTime.orEmpty() }.thenBy { it.id })

    /**
     * Combines several copies of the manifest without losing anything: for each book the most recent
     * progress, for each device its most recent entry, and every picked file.
     */
    fun merge(manifests: List<SyncManifest>): SyncManifest? {
        if (manifests.size <= 1) return manifests.firstOrNull()
        val newest = manifests.maxBy { it.generatedAt }
        val books = manifests.flatMap { it.books }
            .groupBy { it.contentHash }
            .map { (_, copies) ->
                val best = copies.maxWith(compareBy<SyncedBook> { it.progressUpdatedAt }.thenBy { it.updatedAt })
                if (best.driveFileId != null) best else best.copy(driveFileId = copies.firstNotNullOfOrNull { it.driveFileId })
            }
            .sortedBy { it.contentHash }
        val devices = manifests.flatMap { it.devices }
            .groupBy { it.deviceId }
            .map { (_, copies) -> copies.maxBy { it.updatedAt } }
            .sortedBy { it.deviceId }
        val tombstones = manifests.flatMap { it.tombstones }
            .groupBy { it.contentHash }
            .map { (_, copies) -> copies.maxBy { it.deletedAt } }
        return newest.copy(
            books = books,
            devices = devices,
            tombstones = tombstones,
            pickedFileIds = manifests.flatMap { it.pickedFileIds }.distinct().sorted(),
        )
    }

    /** Name for the copy of a manifest that could not be read, kept next to it for recovery. */
    fun damagedCopyName(manifestName: String, now: Long): String =
        manifestName.removeSuffix(".json") + ".damaged-$now.json"

    /** True when the manifest in Drive is no longer the one read at the start of the sync. */
    fun changedSinceRead(read: GoogleDriveRestClient.DriveFile, current: GoogleDriveRestClient.DriveFile?): Boolean =
        current == null || current.modifiedTime != read.modifiedTime
}

/** Another device wrote the manifest during this sync: nothing was overwritten, and the sync is retried. */
class ConcurrentManifestChangeException :
    java.io.IOException("Another device updated the sync manifest; the sync will run again")
