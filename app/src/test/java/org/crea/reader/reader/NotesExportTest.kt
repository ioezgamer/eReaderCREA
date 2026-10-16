package org.crea.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotesExportTest {
    private val labels = ExportLabels(bookmarks = "Marcadores", notes = "Notas", note = "Nota")

    @Test
    fun `nothing to export when there are no bookmarks or notes`() {
        assertNull(NotesExport.build("Libro", "Autor", emptyList(), emptyList(), labels))
    }

    @Test
    fun `exports bookmarks and notes with their headings`() {
        val text = NotesExport.build(
            title = "Rayuela",
            author = "Julio Cortázar",
            bookmarks = listOf(ExportBookmark("Capítulo 1", 12), ExportBookmark("Capítulo 7", null)),
            notes = listOf(
                ExportNote("Capítulo 2", "  una frase  ", "Me gustó"),
                ExportNote("", "otra frase", ""),
            ),
            labels = labels,
        )
        assertEquals(
            """
            Rayuela — Julio Cortázar

            Marcadores
            • Capítulo 1 (12 %)
            • Capítulo 7

            Notas
            • Capítulo 2: “una frase”
              Nota: Me gustó
            • “otra frase”
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `omits the title line when the book has no metadata`() {
        val text = NotesExport.build(null, " ", listOf(ExportBookmark("Inicio", 0)), emptyList(), labels)
        assertEquals("Marcadores\n• Inicio (0 %)", text)
    }

    @Test
    fun `an empty chapter name is shown as a dash`() {
        val text = NotesExport.build("T", null, listOf(ExportBookmark("", 5)), emptyList(), labels)
        assertEquals("T\n\nMarcadores\n• — (5 %)", text)
    }
}
