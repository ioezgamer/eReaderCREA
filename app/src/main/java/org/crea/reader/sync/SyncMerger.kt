package org.crea.reader.sync

object SyncMerger {
    fun plan(
        localBooks: List<SyncedBook>,
        localTombstones: List<SyncedTombstone>,
        remoteManifest: SyncManifest,
    ): MergePlan {
        val localByHash = localBooks.associateBy { it.contentHash }
        val remoteByHash = remoteManifest.books.associateBy { it.contentHash }
        val tombstones = (localTombstones + remoteManifest.tombstones)
            .groupBy { it.contentHash }
            .mapValues { (_, values) -> values.maxBy { it.deletedAt } }

        val upload = mutableListOf<SyncedBook>()
        val download = mutableListOf<SyncedBook>()
        val applyProgress = mutableListOf<SyncedBook>()
        val deleteLocal = mutableListOf<String>()
        val deleteRemote = mutableListOf<SyncedBook>()
        val survivingBooks = mutableListOf<SyncedBook>()
        val survivingTombstones = mutableListOf<SyncedTombstone>()

        val hashes = localByHash.keys + remoteByHash.keys + tombstones.keys
        for (hash in hashes) {
            val local = localByHash[hash]
            val remote = remoteByHash[hash]
            val tombstone = tombstones[hash]
            val newestBookUpdate = maxOf(local?.updatedAt ?: 0, remote?.updatedAt ?: 0)

            if (tombstone != null && tombstone.deletedAt >= newestBookUpdate) {
                if (local != null) deleteLocal += hash
                if (remote?.driveFileId != null) deleteRemote += remote
                survivingTombstones += tombstone
                continue
            }

            when {
                local != null && remote == null -> {
                    upload += local
                    survivingBooks += local
                }
                local == null && remote != null -> {
                    download += remote
                    survivingBooks += remote
                }
                local != null && remote != null -> {
                    val metadataWinner = if (local.updatedAt >= remote.updatedAt) local else remote
                    val progressWinner = if (local.progressUpdatedAt >= remote.progressUpdatedAt) local else remote
                    val merged = metadataWinner.copy(
                        progression = progressWinner.progression,
                        progressUpdatedAt = progressWinner.progressUpdatedAt,
                        driveFileId = remote.driveFileId ?: local.driveFileId
                    )
                    if (remote.driveFileId == null) upload += merged
                    if (remote.progressUpdatedAt > local.progressUpdatedAt) applyProgress += merged
                    survivingBooks += merged
                }
            }
        }

        return MergePlan(
            uploadBooks = upload,
            downloadBooks = download,
            applyRemoteProgress = applyProgress,
            deleteLocalHashes = deleteLocal,
            deleteRemoteBooks = deleteRemote,
            survivingBooks = survivingBooks,
            survivingTombstones = survivingTombstones,
        )
    }
}
