package org.crea.reader.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveNamingTest {
    @Test
    fun recognisesBooksByExtensionOrMimeType() {
        assertTrue(DriveNaming.isBookFile("Cien años.EPUB", null))
        assertTrue(DriveNaming.isBookFile("manual.pdf", "application/octet-stream"))
        assertTrue(DriveNaming.isBookFile("sin-extension", "application/epub+zip"))
        assertFalse(DriveNaming.isBookFile("notas.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertFalse(DriveNaming.isBookFile("foto.jpg", "image/jpeg"))
    }

    @Test
    fun buildsReadableFileNames() {
        assertEquals("El principito - Antoine de Saint-Exupéry.epub", DriveNaming.bookFileName("El principito", "Antoine de Saint-Exupéry", "EPUB"))
        assertEquals("Solo título.pdf", DriveNaming.bookFileName("Solo título", null, "pdf"))
        assertEquals("Libro.epub", DriveNaming.bookFileName(null, "  ", ""))
    }

    @Test
    fun removesCharactersDriveAndFileSystemsDislike() {
        assertEquals("A B C D.epub", DriveNaming.bookFileName("A/B:C*D", null, "epub"))
        assertEquals("120 años.epub", DriveNaming.bookFileName("  120   años... ", null, "epub"))
    }

    @Test
    fun capsVeryLongTitles() {
        val name = DriveNaming.bookFileName("x".repeat(500), null, "pdf")
        assertTrue(name.length <= 125)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun detectsFormat() {
        assertEquals("EPUB", DriveNaming.formatOf("a.epub", null))
        assertEquals("PDF", DriveNaming.formatOf("sin-extension", "application/pdf"))
        assertEquals(null, DriveNaming.formatOf("a.docx", "application/msword"))
    }

    @Test
    fun readsAuthorOnlyFromNamesWrittenByTheApp() {
        val own = DriveNaming.describe("El principito - Antoine de Saint-Exupéry.epub", createdByApp = true)
        assertEquals("El principito", own.title)
        assertEquals("Antoine de Saint-Exupéry", own.author)

        val withDash = DriveNaming.describe("Ficciones - Parte 1 - Borges.pdf", createdByApp = true)
        assertEquals("Ficciones - Parte 1", withDash.title)
        assertEquals("Borges", withDash.author)
    }

    @Test
    fun keepsTheWholeNameForFilesUploadedByHand() {
        val foreign = DriveNaming.describe("Cien_años - de soledad.epub", createdByApp = false)
        assertEquals("Cien años - de soledad", foreign.title)
        assertEquals(null, foreign.author)
        assertEquals("Solo título", DriveNaming.describe("Solo título.pdf", createdByApp = true).title)
    }

    @Test
    fun cleansWebDownloadNames() {
        val sleepy = DriveNaming.describe("_libros_epub_La-leyenda-de-Sleepy-Hollow-y-otros-cuentos-de-fantasmas.epub", createdByApp = false)
        assertEquals("La leyenda de Sleepy Hollow y otros cuentos de fantasmas", sleepy.title)
        assertEquals("Fabulas", DriveNaming.describe("_libros_epub_Fabulas.epub", createdByApp = false).title)
        assertEquals("Spider-Man", DriveNaming.describe("Spider-Man.epub", createdByApp = false).title)
    }

    @Test
    fun picksMimeTypeFromExtension() {
        assertEquals("application/pdf", DriveNaming.mimeTypeFor("a.pdf"))
        assertEquals("application/epub+zip", DriveNaming.mimeTypeFor("a.EPUB"))
        assertEquals("application/octet-stream", DriveNaming.mimeTypeFor("a.bin"))
    }
}
