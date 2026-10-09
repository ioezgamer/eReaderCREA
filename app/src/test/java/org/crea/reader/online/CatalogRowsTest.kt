package org.crea.reader.online

import org.crea.reader.data.model.Book
import org.crea.reader.data.model.DriveBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRowsTest {

    private fun entry(
        id: String,
        title: String,
        author: String? = null,
        format: String = "EPUB",
        category: String? = null,
        modifiedAt: Long = 0,
        contentHash: String? = null,
    ) = DriveBook(
        driveFileId = id,
        fileName = "$title.${format.lowercase()}",
        title = title,
        author = author,
        format = format,
        size = 1000,
        modifiedAt = modifiedAt,
        category = category,
        contentHash = contentHash,
        progressionAt = 0,
        readerId = OnlineBookIds.forDriveFile(id),
    )

    private fun local(id: Long, hash: String, remoteFileId: String? = null) = Book(
        id = id,
        href = "file:///books/$id.epub",
        title = "local $id",
        identifier = "id-$id",
        rawMediaType = "application/epub+zip",
        cover = "/covers/$id.png",
        contentHash = hash,
        fileSize = 10,
        updatedAt = 0,
        remoteFileId = remoteFileId,
    )

    private val catalog = listOf(
        entry("a", "Cien años de soledad", "Gabriel García Márquez", category = "Literatura", modifiedAt = 5),
        entry("b", "Álgebra lineal", "Seymour Lipschutz", "PDF", category = "Matemáticas", modifiedAt = 9),
        entry("c", "El principito", "Antoine de Saint-Exupéry", modifiedAt = 1),
        entry("d", "Zoología", format = "PDF", modifiedAt = 3),
    )

    @Test
    fun marksBooksDownloadedByDriveIdOrChecksum() {
        val rows = CatalogRows.build(
            catalog + entry("e", "Rayuela", contentHash = "hash-e"),
            listOf(local(1, "x", remoteFileId = "a"), local(2, "hash-e"), local(3, "unrelated")),
        )
        val downloaded = rows.filter { it.downloaded }.map { it.book.driveFileId }
        assertEquals(listOf("a", "e"), downloaded)
        assertEquals(1L, rows.first { it.book.driveFileId == "a" }.local?.id)
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        val rows = CatalogRows.build(catalog, emptyList())
        val found = CatalogRows.filterAndSort(rows, CatalogQuery(text = "garcia MARQUEZ"))
        assertEquals(listOf("a"), found.map { it.book.driveFileId })
        assertEquals(listOf("b"), CatalogRows.filterAndSort(rows, CatalogQuery(text = "algebra")).map { it.book.driveFileId })
        assertTrue(CatalogRows.filterAndSort(rows, CatalogQuery(text = "no existe")).isEmpty())
    }

    @Test
    fun searchMatchesEveryWordInAnyOrder() {
        val rows = CatalogRows.build(catalog, emptyList())
        assertEquals(
            listOf("a"),
            CatalogRows.filterAndSort(rows, CatalogQuery(text = "soledad gabriel")).map { it.book.driveFileId }
        )
    }

    @Test
    fun filtersByDownloadStateFormatAndCategory() {
        val rows = CatalogRows.build(catalog, listOf(local(1, "x", remoteFileId = "c")))
        fun ids(query: CatalogQuery) = CatalogRows.filterAndSort(rows, query).map { it.book.driveFileId }.toSet()

        assertEquals(setOf("c"), ids(CatalogQuery(filter = CatalogFilter.DOWNLOADED)))
        assertEquals(setOf("a", "b", "d"), ids(CatalogQuery(filter = CatalogFilter.NOT_DOWNLOADED)))
        assertEquals(setOf("b", "d"), ids(CatalogQuery(filter = CatalogFilter.PDF)))
        assertEquals(setOf("a", "c"), ids(CatalogQuery(filter = CatalogFilter.EPUB)))
        assertEquals(setOf("b"), ids(CatalogQuery(category = "Matemáticas")))
        assertEquals(emptySet<String>(), ids(CatalogQuery(filter = CatalogFilter.PDF, category = "Literatura")))
    }

    @Test
    fun sortsByTitleIgnoringAccentsAndByRecentChange() {
        val rows = CatalogRows.build(catalog, emptyList())
        assertEquals(
            listOf("b", "a", "c", "d"),
            CatalogRows.filterAndSort(rows, CatalogQuery(sort = CatalogSort.TITLE)).map { it.book.driveFileId }
        )
        assertEquals(
            listOf("b", "a", "d", "c"),
            CatalogRows.filterAndSort(rows, CatalogQuery(sort = CatalogSort.RECENT)).map { it.book.driveFileId }
        )
    }

    @Test
    fun listsCategoriesOnce() {
        val rows = CatalogRows.build(catalog + entry("e", "Otro", category = "Literatura"), emptyList())
        assertEquals(listOf("Literatura", "Matemáticas"), CatalogRows.categories(rows))
        assertTrue(CatalogRows.categories(CatalogRows.build(listOf(entry("z", "Sin categoría")), emptyList())).isEmpty())
    }

    @Test
    fun aFreshRowHasNoTransferAndIsNotDownloaded() {
        val row = CatalogRows.build(listOf(entry("a", "Uno")), emptyList()).single()
        assertNull(row.transfer)
        assertFalse(row.downloaded)
    }

    private fun locator(progress: Double) = """{"locations":{"totalProgression":$progress}}"""

    @Test
    fun generalPercentIsTheMostRecentReadingOnAnyDevice() {
        val other = { percent: Int, at: Long, name: String ->
            org.crea.reader.sync.RemoteReading("dev-$name", name, "a", percent, at)
        }
        val ownBook = local(1, "x", remoteFileId = "a").copy(progression = locator(0.40), progressUpdatedAt = 100)

        // Another device read it more recently than this one.
        val newer = CatalogRows.build(listOf(entry("a", "Uno")), listOf(ownBook), listOf(other(75, 200, "Tablet"))).single()
        assertEquals(40, newer.ownPercent)
        assertEquals(75, newer.generalPercent)

        // This device read it most recently.
        val older = CatalogRows.build(listOf(entry("a", "Uno")), listOf(ownBook), listOf(other(10, 50, "Tablet"))).single()
        assertEquals(40, older.generalPercent)

        // Nobody has read it, or only another device did.
        assertEquals(0, CatalogRows.build(listOf(entry("a", "Uno")), emptyList()).single().generalPercent)
        val onlyOther = CatalogRows.build(listOf(entry("a", "Uno")), emptyList(), listOf(other(30, 5, "Tablet"))).single()
        assertEquals(0, onlyOther.ownPercent)
        assertEquals(30, onlyOther.generalPercent)
    }

    @Test
    fun othersAreListedMostRecentFirstAndZeroReadingsIgnored() {
        val readings = listOf(
            org.crea.reader.sync.RemoteReading("d1", "Uno", "a", 10, 1),
            org.crea.reader.sync.RemoteReading("d2", "Dos", "a", 20, 9),
            org.crea.reader.sync.RemoteReading("d3", "Tres", "a", 0, 99),
        )
        val row = CatalogRows.build(listOf(entry("a", "Libro")), emptyList(), readings).single()
        assertEquals(listOf("Dos", "Uno"), row.others.map { it.deviceName })
    }

    @Test
    fun filtersByReadingStateAndNovelty() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val entries = listOf(
            entry("a", "Leyendo", modifiedAt = now - 50 * day),
            entry("b", "Terminado", modifiedAt = now - 50 * day),
            entry("c", "Nuevo", modifiedAt = now - 2 * day),
        )
        val readings = listOf(
            org.crea.reader.sync.RemoteReading("d", "Tablet", "a", 40, 1),
            org.crea.reader.sync.RemoteReading("d", "Tablet", "b", 100, 1),
        )
        val rows = CatalogRows.build(entries, emptyList(), readings)
        fun ids(filter: CatalogFilter) =
            CatalogRows.filterAndSort(rows, CatalogQuery(filter = filter), now).map { it.book.driveFileId }
        assertEquals(listOf("a"), ids(CatalogFilter.READING))
        assertEquals(listOf("b"), ids(CatalogFilter.FINISHED))
        assertEquals(listOf("c"), ids(CatalogFilter.NEW))
    }

    @Test
    fun anUpdateIsOfferedOnlyWhenTheRecordedVersionDiffers() {
        val drive = entry("a", "Uno").copy(md5 = "bbbb")
        fun row(recorded: String?) = CatalogRows.build(listOf(drive), listOf(local(1, "x", remoteFileId = "a").copy(remoteMd5 = recorded))).single()
        assertTrue(row("aaaa").updateAvailable)
        assertFalse(row("BBBB").updateAvailable)
        assertFalse("unknown baseline", row(null).updateAvailable)
        assertFalse("not downloaded", CatalogRows.build(listOf(drive), emptyList()).single().updateAvailable)
    }
}
