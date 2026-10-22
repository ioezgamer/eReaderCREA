package org.crea.reader.readers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRulesTest {

    private val ana = ReaderProfile(id = "ana", nickname = "Ana", createdAt = 1, updatedAt = 1)
    private val quijote = BookRef("drive:q", "Don Quijote", "Cervantes", "q")
    private val maria = BookRef("drive:m", "María", "Jorge Isaacs", "m")

    @Test
    fun `opening and reading keeps the last opened first and remembers the position`() {
        var profile = ReaderRules.opened(ana, quijote, now = 10)
        profile = ReaderRules.progressed(profile, quijote, """{"p":1}""", percent = 30, now = 20)
        profile = ReaderRules.opened(profile, maria, now = 30)

        assertEquals(listOf("drive:m", "drive:q"), ReaderRules.recent(profile).map { it.key })
        val entry = profile.history.first { it.key == "drive:q" }
        assertEquals(30, entry.percent)
        assertEquals("""{"p":1}""", entry.progression)
        assertEquals(listOf("drive:m", "drive:q"), ReaderRules.continueReading(profile).map { it.key })
    }

    @Test
    fun `reaching the end marks the book finished once`() {
        var profile = ReaderRules.progressed(ana, quijote, "{}", percent = 96, now = 50)
        profile = ReaderRules.progressed(profile, quijote, "{}", percent = 40, now = 60)

        assertEquals(50L, profile.history.single().finishedAt)
        assertTrue(ReaderRules.continueReading(profile).isEmpty())
        assertEquals(listOf("drive:q"), ReaderRules.finished(profile).map { it.key })
    }

    @Test
    fun `two tablets merge without losing reading`() {
        val tablet1 = ReaderRules.progressed(ana, quijote, "pos-1", percent = 20, now = 100)
        val tablet2 = ReaderRules.progressed(
            ReaderRules.opened(ana, maria, now = 90),
            quijote, "pos-2", percent = 35, now = 200,
        )

        val merged = ReaderRules.merge(tablet1, tablet2)

        assertEquals(setOf("drive:q", "drive:m"), merged.history.map { it.key }.toSet())
        val q = merged.history.first { it.key == "drive:q" }
        assertEquals("pos-2", q.progression)
        assertEquals(35, q.percent)
        assertEquals(ReaderRules.merge(tablet2, tablet1).history.toSet(), merged.history.toSet())
    }

    @Test
    fun `the latest favourite change wins and the newer nickname is kept`() {
        val liked = ReaderRules.favorite(ana, quijote, on = true, now = 10)
        val unliked = ReaderRules.favorite(ana.copy(nickname = "Anita", updatedAt = 5), quijote, on = false, now = 20)

        val merged = ReaderRules.merge(liked, unliked)

        assertFalse(ReaderRules.isFavorite(merged, "drive:q"))
        assertEquals("Anita", merged.nickname)
        assertTrue(ReaderRules.isFavorite(ReaderRules.merge(liked, ana), "drive:q"))
    }

    @Test
    fun `guest reading moves into the profile chosen afterwards`() {
        val guest = ReaderRules.progressed(
            ReaderProfile(id = ReaderStore.GUEST_ID, nickname = "", createdAt = 50, updatedAt = 50),
            maria, "pos", percent = 10, now = 60,
        )

        val adopted = ReaderRules.adopt(ana, guest)

        assertEquals("ana", adopted.id)
        assertEquals("Ana", adopted.nickname)
        assertNotNull(adopted.history.firstOrNull { it.key == "drive:m" })
    }

    @Test
    fun `a deleted profile stays deleted after merging`() {
        val deleted = ana.copy(deleted = true, updatedAt = 10)
        assertTrue(ReaderRules.merge(ana, deleted).deleted)
    }

    @Test
    fun `books are identified by Drive file first, then by content`() {
        assertEquals("drive:abc", ReaderRules.keyFor("abc", "hash"))
        assertEquals("local:hash", ReaderRules.keyFor(null, "hash"))
        assertNull(ReaderRules.keyFor(null, null))
    }

    @Test
    fun `nicknames are compared without case or accents`() {
        assertEquals(ReaderRules.normalize("  Ángela "), ReaderRules.normalize("angela"))
    }

    @Test
    fun `history keeps the most recent books only`() {
        var profile = ana
        for (i in 1..(ReaderRules.MAX_HISTORY + 5)) {
            profile = ReaderRules.opened(profile, BookRef("local:$i", "Libro $i", null, null), now = i.toLong())
        }
        assertEquals(ReaderRules.MAX_HISTORY, profile.history.size)
        assertEquals("local:${ReaderRules.MAX_HISTORY + 5}", ReaderRules.recent(profile).first().key)
    }

    @Test
    fun `sync file names round trip`() {
        assertEquals("abc123", ReaderSync.idOf(ReaderSync.fileName("abc123")))
        assertNull(ReaderSync.idOf("crea-lector-otra.json"))
    }
}
