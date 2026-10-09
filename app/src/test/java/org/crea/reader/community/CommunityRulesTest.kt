package org.crea.reader.community

import org.crea.reader.data.model.Book
import org.crea.reader.data.model.DriveBook
import org.crea.reader.online.CatalogRow
import org.crea.reader.sync.CommunityMember
import org.crea.reader.sync.DeviceReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityRulesTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun locator(progress: Double) = """{"locations":{"totalProgression":$progress}}"""

    private fun row(id: String, title: String, ownProgress: Double = 0.0, downloaded: Boolean = false): CatalogRow {
        val book = DriveBook(
            driveFileId = id, fileName = "$title.epub", title = title, format = "EPUB", size = 1,
            modifiedAt = 0, progression = if (ownProgress > 0) locator(ownProgress) else null,
            progressionAt = if (ownProgress > 0) now else 0, readerId = -1,
        )
        val local = if (downloaded) Book(
            id = 1, href = "file:///x", title = title, identifier = "i", rawMediaType = "application/epub+zip",
            cover = "c", contentHash = "h$id", fileSize = 1, updatedAt = 0,
        ) else null
        return CatalogRow(book, local)
    }

    private fun member(id: String, vararg readings: Triple<String, Int, Long>) = CommunityMember(
        userId = id, nickname = "Lector $id", fileId = "f$id", fileModified = "t", updatedAt = now,
        readings = readings.map { DeviceReading(it.first, it.second, it.third) },
    )

    private val rows = listOf(
        row("a", "Cien años", ownProgress = 0.5),
        row("b", "Rayuela"),
        row("c", "Pedro Páramo"),
        row("d", "Ficciones"),
    ).associateBy { it.book.driveFileId }

    @Test
    fun noMembersMeansNothingToShow() {
        assertTrue(CommunityRules.build(emptyList(), rows, now).isEmpty)
    }

    @Test
    fun popularRanksByReadersThenFinishers() {
        val members = listOf(
            member("1", Triple("b", 100, now), Triple("c", 20, now)),
            member("2", Triple("b", 30, now)),
            member("3", Triple("c", 100, now), Triple("b", 10, now)),
        )
        val popular = CommunityRules.build(members, rows, now).popular
        assertEquals(listOf("b", "c"), popular.map { it.row.book.driveFileId })
        assertEquals(3, popular[0].readers)
        assertEquals(1, popular[0].finishers)
        assertEquals(2, popular[1].readers)
    }

    @Test
    fun recommendationsSkipBooksYouStartedAndFavourSimilarReaders() {
        // Ana reads what I read (book a) and also c; Beto reads b and d only.
        val members = listOf(
            member("ana", Triple("a", 100, now), Triple("c", 100, now)),
            member("beto", Triple("b", 100, now), Triple("d", 50, now)),
        )
        val feed = CommunityRules.build(members, rows, now)
        val ids = feed.recommended.map { it.row.book.driveFileId }
        assertFalse("a" in ids)
        assertEquals("c", ids.first())
        assertTrue(feed.recommended.first().similarReaders)
        assertFalse(feed.recommended.first { it.row.book.driveFileId == "b" }.similarReaders)
    }

    @Test
    fun readingNowListsRecentUnfinishedReadingsNewestFirst() {
        val members = listOf(
            member("1", Triple("b", 40, now - 1 * day), Triple("c", 100, now), Triple("d", 10, now - 60 * day)),
            member("2", Triple("c", 70, now - 2 * 60 * 60 * 1000)),
        )
        val now = CommunityRules.build(members, rows, now).readingNow
        assertEquals(listOf("c", "b"), now.map { it.row.book.driveFileId })
        assertEquals(70, now.first().percent)
    }

    @Test
    fun ignoresBooksMissingFromTheCatalogAndUnstartedReadings() {
        val members = listOf(member("1", Triple("zzz", 50, now), Triple("b", 0, now)))
        assertTrue(CommunityRules.build(members, rows, now).isEmpty)
    }
}
