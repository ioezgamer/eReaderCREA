package org.crea.reader.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergerTest {
    private fun book(
        hash: String,
        updatedAt: Long = 10,
        progressUpdatedAt: Long = 10,
        progression: String = "{\"p\":0.1}",
        driveFileId: String? = null,
    ) = SyncedBook(
        contentHash = hash,
        fileName = "$hash.epub",
        mediaType = "application/epub+zip",
        title = hash,
        fileSize = 100,
        createdAt = 1,
        updatedAt = updatedAt,
        progression = progression,
        progressUpdatedAt = progressUpdatedAt,
        driveFileId = driveFileId,
    )

    @Test
    fun localOnlyBookIsUploaded() {
        val plan = SyncMerger.plan(
            localBooks = listOf(book("a")),
            localTombstones = emptyList(),
            remoteManifest = SyncManifest(1, 0, "remote")
        )

        assertEquals(listOf("a"), plan.uploadBooks.map { it.contentHash })
        assertTrue(plan.downloadBooks.isEmpty())
    }

    @Test
    fun remoteOnlyBookIsDownloaded() {
        val remote = book("a", driveFileId = "drive-a")
        val plan = SyncMerger.plan(
            localBooks = emptyList(),
            localTombstones = emptyList(),
            remoteManifest = SyncManifest(1, 20, "remote", books = listOf(remote))
        )

        assertEquals(listOf("a"), plan.downloadBooks.map { it.contentHash })
    }

    @Test
    fun newestProgressWinsWithoutReplacingBookFile() {
        val local = book("a", updatedAt = 30, progressUpdatedAt = 10)
        val remote = book(
            "a",
            updatedAt = 20,
            progressUpdatedAt = 40,
            progression = "{\"p\":0.8}",
            driveFileId = "drive-a"
        )
        val plan = SyncMerger.plan(
            localBooks = listOf(local),
            localTombstones = emptyList(),
            remoteManifest = SyncManifest(1, 40, "remote", books = listOf(remote))
        )

        assertEquals("{\"p\":0.8}", plan.survivingBooks.single().progression)
        assertEquals(1, plan.applyRemoteProgress.size)
        assertTrue(plan.uploadBooks.isEmpty())
        assertTrue(plan.downloadBooks.isEmpty())
    }

    @Test
    fun tombstonePreventsDeletedBookFromReturning() {
        val remote = book("a", updatedAt = 20, driveFileId = "drive-a")
        val plan = SyncMerger.plan(
            localBooks = emptyList(),
            localTombstones = listOf(SyncedTombstone("a", deletedAt = 30)),
            remoteManifest = SyncManifest(1, 20, "remote", books = listOf(remote))
        )

        assertTrue(plan.downloadBooks.isEmpty())
        assertEquals(listOf("drive-a"), plan.deleteRemoteBooks.mapNotNull { it.driveFileId })
        assertEquals(listOf("a"), plan.survivingTombstones.map { it.contentHash })
    }

    @Test
    fun newerReimportOverridesOldTombstone() {
        val local = book("a", updatedAt = 50)
        val plan = SyncMerger.plan(
            localBooks = listOf(local),
            localTombstones = listOf(SyncedTombstone("a", deletedAt = 30)),
            remoteManifest = SyncManifest(1, 30, "remote")
        )

        assertEquals(listOf("a"), plan.uploadBooks.map { it.contentHash })
        assertTrue(plan.survivingTombstones.isEmpty())
    }
}
