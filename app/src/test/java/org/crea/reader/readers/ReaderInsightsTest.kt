package org.crea.reader.readers

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInsightsTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun profile(id: String, group: String? = "Club Martes", hidden: Boolean = false) =
        ReaderProfile(id = id, nickname = id, createdAt = 1, updatedAt = 1, group = group, hidden = hidden)

    private fun book(id: String, author: String? = null, vararg categories: String, added: Long = 0) =
        CandidateBook("drive:$id", "Libro $id", author, categories.toList(), id, added)

    private fun ReaderProfile.reads(id: String, at: Long, percent: Int = 30, author: String? = null): ReaderProfile {
        val ref = BookRef("drive:$id", "Libro $id", author, id)
        return ReaderRules.progressed(ReaderRules.opened(this, ref, at), ref, "{}", percent, at)
    }

    @Test
    fun `group reading shows the latest book of each visible member of my group`() {
        val me = profile("ana")
        val luis = profile("luis", group = "club martes ").reads("a", now - day).reads("b", now - 2 * day)
        val sofia = profile("sofia", hidden = true).reads("c", now)
        val pedro = profile("pedro", group = "Otro").reads("d", now)
        val viejo = profile("viejo").reads("e", now - 60 * day)

        val feed = ReaderInsights.groupReading(me, listOf(me, luis, sofia, pedro, viejo), now)

        assertEquals(listOf("luis" to "drive:a"), feed.map { it.profile.id to it.entry.key })
        assertTrue(ReaderInsights.groupReading(profile("x", group = null), listOf(luis), now).isEmpty())
    }

    @Test
    fun `recommends the same author first, then category, group and new books, never what was opened`() {
        val me = profile("ana").reads("q1", now, percent = 100, author = "Cervantes")
        val luis = profile("luis").reads("poesia", now, percent = 100)
        val catalog = listOf(
            book("q1", "Cervantes", "Novela"),
            book("q2", "Cervantes", "Novela"),
            book("n1", "Galdós", "Novela"),
            book("poesia", "Darío", "Poesía"),
            book("nuevo", "Otro", "Teatro", added = 50),
        )

        val picks = ReaderInsights.recommend(me, listOf(me, luis), catalog, now)

        assertEquals("drive:q2", picks.first().book.key)
        assertEquals(Reason.SameAuthor("Cervantes"), picks.first().reason)
        assertTrue(picks.any { it.book.key == "drive:n1" && it.reason == Reason.SameCategory("Novela") })
        assertTrue(picks.any { it.book.key == "drive:poesia" && it.reason == Reason.GroupFinished(1) })
        assertTrue(picks.any { it.book.key == "drive:nuevo" && it.reason == Reason.New })
        assertFalse(picks.any { it.book.key == "drive:q1" })
        assertEquals(picks.size, picks.map { it.book.key }.distinct().size)
    }

    @Test
    fun `a new reader still gets new books`() {
        val picks = ReaderInsights.recommend(null, emptyList(), listOf(book("a", added = 2), book("b", added = 5)), now)
        assertEquals(listOf("drive:b", "drive:a"), picks.map { it.book.key })
    }

    @Test
    fun `streaks count consecutive days and survive until the end of today`() {
        val today = LocalDate.of(2026, 10, 22)
        val days = listOf("2026-10-18", "2026-10-19", "2026-10-20", "2026-10-21")
        assertEquals(4, ReaderInsights.streak(days, today))
        assertEquals(5, ReaderInsights.streak(days + "2026-10-22", today))
        assertEquals(0, ReaderInsights.streak(listOf("2026-10-10"), today))
        assertEquals(4, ReaderInsights.bestStreak(days + "2026-10-10"))
    }

    @Test
    fun `reading records the day and merging keeps every day`() {
        val a = profile("ana").reads("x", now)
        val b = profile("ana").reads("y", now + day)
        val merged = ReaderRules.merge(a, b)
        assertEquals(2, merged.days.size)
        assertEquals(merged.days.sorted(), merged.days)
    }

    @Test
    fun `achievements unlock with reading`() {
        var me = profile("ana")
        assertTrue(ReaderInsights.achievements(me).none { it.unlocked })
        me = me.reads("a", now, percent = 100, author = "Darío").reads("b", now, percent = 100, author = "Mistral")
            .reads("c", now, percent = 100, author = "darío ")
        val unlocked = ReaderInsights.achievements(me).filter { it.unlocked }.map { it.id }
        assertTrue(AchievementId.FIRST_BOOK in unlocked)
        assertFalse(AchievementId.THREE_AUTHORS in unlocked)
        assertEquals(3, ReaderInsights.achievements(me).first { it.id == AchievementId.FIVE_BOOKS }.progress)
    }
}
