package org.crea.reader

import org.crea.reader.data.model.Book
import org.crea.reader.domain.DuplicateRules
import org.crea.reader.domain.DuplicateRules.Candidate
import org.crea.reader.domain.DuplicateRules.Match
import org.crea.reader.online.CategoryPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryAndDuplicateTest {

    private fun book(id: Long, hash: String, title: String?, author: String? = null, identifier: String = "", type: String = "application/epub+zip") =
        Book(
            id = id, href = "file:///b$id", title = title, author = author, identifier = identifier,
            rawMediaType = type, cover = "", contentHash = hash, fileSize = 1, updatedAt = 0,
        )

    @Test
    fun nestedCategoriesMatchTheirParents() {
        val bio = CategoryPaths.join(CategoryPaths.join(null, "Ciencias"), "Biología")
        assertEquals("Ciencias / Biología", bio)
        assertTrue(CategoryPaths.matches(bio, "Ciencias"))
        assertTrue(CategoryPaths.matches(bio, bio))
        assertFalse(CategoryPaths.matches("Ciencias sociales", "Ciencias"))
        assertFalse(CategoryPaths.matches(null, "Ciencias"))
        assertTrue(CategoryPaths.matches(null, null))
        assertEquals("Ciencias", CategoryPaths.parent(bio))
        assertEquals("Biología", CategoryPaths.leaf(bio))
    }

    @Test
    fun chipsOpenOnlyTheChosenBranch() {
        val paths = listOf("Ciencias / Biología / Plantas", "Ciencias / Física", "Lectura", "Matemáticas / Álgebra")
        assertEquals(listOf("Ciencias", "Lectura", "Matemáticas"), CategoryPaths.chips(paths, null))
        assertEquals(
            listOf("Ciencias", "Ciencias / Biología", "Ciencias / Física", "Lectura", "Matemáticas"),
            CategoryPaths.chips(paths, "Ciencias"),
        )
        assertEquals(
            listOf("Ciencias", "Ciencias / Biología", "Ciencias / Biología / Plantas", "Ciencias / Física", "Lectura", "Matemáticas"),
            CategoryPaths.chips(paths, "Ciencias / Biología"),
        )
    }

    @Test
    fun sameBytesIsTheSameFile() {
        val existing = book(1, "abc", "Cien años de soledad")
        val match = DuplicateRules.find(Candidate("abc", "Otro título", null, null, "application/epub+zip"), listOf(existing))
        assertEquals(Match.SameFile(existing), match)
    }

    @Test
    fun sameTitleAndAuthorIgnoringAccentsAndCaseIsSimilar() {
        val existing = book(1, "abc", "Cien años de soledad", "Gabriel García Márquez")
        val match = DuplicateRules.find(
            Candidate("def", "CIEN AÑOS DE SOLEDAD ", "gabriel garcia marquez", null, "application/epub+zip"),
            listOf(existing),
        )
        assertEquals(Match.Similar(existing), match)
    }

    @Test
    fun sameIdentifierIsSimilarButOnlyInTheSameFormat() {
        val epub = book(1, "abc", "Libro", identifier = "urn:isbn:9780000000001")
        val candidate = Candidate("def", "Otro nombre", null, "urn:isbn:9780000000001", "application/epub+zip")
        assertEquals(Match.Similar(epub), DuplicateRules.find(candidate, listOf(epub)))
        // The PDF of the same book is a different thing to read.
        assertEquals(Match.None, DuplicateRules.find(candidate.copy(mediaType = "application/pdf"), listOf(epub)))
    }

    @Test
    fun differentBooksAreNotDuplicates() {
        val existing = book(1, "abc", "Cien años de soledad", "García Márquez")
        assertEquals(Match.None, DuplicateRules.find(Candidate("def", "Cien años de soledad", "Otro autor", null, "application/epub+zip"), listOf(existing)))
        // Very short or empty titles are never enough.
        val untitled = book(2, "x", "", null)
        assertEquals(Match.None, DuplicateRules.find(Candidate("y", "", null, "", "application/epub+zip"), listOf(untitled)))
    }
}
