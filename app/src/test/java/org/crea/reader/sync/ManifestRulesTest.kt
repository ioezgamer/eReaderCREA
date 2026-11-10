package org.crea.reader.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestRulesTest {

    private fun file(id: String, modified: String?) =
        GoogleDriveRestClient.DriveFile(id = id, name = LibrarySyncEngine.MANIFEST_NAME, modifiedTime = modified)

    private fun book(hash: String, progressAt: Long, driveId: String? = null, progression: String = "{}") = SyncedBook(
        contentHash = hash,
        fileName = "$hash.epub",
        mediaType = "application/epub+zip",
        title = hash,
        fileSize = 1,
        createdAt = 1,
        updatedAt = 1,
        progression = progression,
        progressUpdatedAt = progressAt,
        driveFileId = driveId,
    )

    @Test
    fun theNewestManifestIsTheOneWritten() {
        val older = file("a", "2026-11-01T10:00:00.000Z")
        val newer = file("b", "2026-11-02T08:00:00.000Z")
        assertEquals("b", ManifestRules.primary(listOf(older, newer))?.id)
        assertEquals("b", ManifestRules.primary(listOf(newer, older))?.id)
        assertNull(ManifestRules.primary(emptyList()))
    }

    @Test
    fun twoManifestsAreCombinedWithoutLosingProgressDevicesOrPickedFiles() {
        val first = SyncManifest(
            generatedAt = 10,
            deviceId = "tablet-1",
            books = listOf(book("h1", progressAt = 100, driveId = "d1", progression = "{\"old\":1}"), book("h2", progressAt = 5)),
            devices = listOf(SyncedDevice("tablet-1", "Aula 1", updatedAt = 50), SyncedDevice("tablet-2", "Aula 2", updatedAt = 10)),
            pickedFileIds = listOf("p1"),
        )
        val second = SyncManifest(
            generatedAt = 20,
            deviceId = "tablet-2",
            books = listOf(book("h1", progressAt = 200, progression = "{\"new\":1}"), book("h3", progressAt = 1)),
            devices = listOf(SyncedDevice("tablet-2", "Aula 2", updatedAt = 60)),
            pickedFileIds = listOf("p2", "p1"),
        )

        val merged = ManifestRules.merge(listOf(first, second))!!

        assertEquals("tablet-2", merged.deviceId)
        assertEquals(listOf("h1", "h2", "h3"), merged.books.map { it.contentHash })
        val h1 = merged.books.first { it.contentHash == "h1" }
        assertEquals("the most recent progress wins", "{\"new\":1}", h1.progression)
        assertEquals("the Drive link is not lost", "d1", h1.driveFileId)
        assertEquals(listOf(50L, 60L), merged.devices.map { it.updatedAt })
        assertEquals(listOf("p1", "p2"), merged.pickedFileIds)
    }

    @Test
    fun aSingleOrNoManifestIsReturnedAsItIs() {
        val only = SyncManifest(generatedAt = 1, deviceId = "x", books = listOf(book("h", 1)))
        assertEquals(only, ManifestRules.merge(listOf(only)))
        assertNull(ManifestRules.merge(emptyList()))
    }

    @Test
    fun aChangeByAnotherDeviceIsDetectedBeforeWriting() {
        val read = file("m", "2026-11-10T10:00:00.000Z")
        assertFalse(ManifestRules.changedSinceRead(read, file("m", "2026-11-10T10:00:00.000Z")))
        assertTrue(ManifestRules.changedSinceRead(read, file("m", "2026-11-10T10:00:05.000Z")))
        assertTrue("deleted in the meantime", ManifestRules.changedSinceRead(read, null))
    }

    @Test
    fun aDamagedManifestIsKeptUnderItsOwnName() {
        val name = ManifestRules.damagedCopyName(LibrarySyncEngine.MANIFEST_NAME, 1234)
        assertEquals("crea-reader-library-v2.damaged-1234.json", name)
        assertTrue(name != LibrarySyncEngine.MANIFEST_NAME)
    }
}
