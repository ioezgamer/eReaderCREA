package org.crea.reader.reader

/** A bookmark as it appears in the exported text. */
data class ExportBookmark(val chapter: String, val percent: Int?)

/** A highlight or underline, with the note the reader attached to it (may be empty). */
data class ExportNote(val chapter: String, val quote: String, val note: String)

/** Headings of the exported text, already translated. */
data class ExportLabels(val bookmarks: String, val notes: String, val note: String)

/** Plain text with a book's bookmarks and notes, ready to share. */
object NotesExport {
    /** Null when the book has no bookmarks and no notes, so there is nothing to share. */
    fun build(
        title: String?,
        author: String?,
        bookmarks: List<ExportBookmark>,
        notes: List<ExportNote>,
        labels: ExportLabels,
    ): String? {
        if (bookmarks.isEmpty() && notes.isEmpty()) return null
        return buildString {
            val heading = listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }, author?.trim()?.takeIf { it.isNotEmpty() })
            if (heading.isNotEmpty()) append(heading.joinToString(" — ")).append("\n\n")

            if (bookmarks.isNotEmpty()) {
                append(labels.bookmarks).append('\n')
                bookmarks.forEach { b ->
                    append("• ").append(b.chapter.ifBlank { "—" })
                    b.percent?.let { append(" (").append(it).append(" %)") }
                    append('\n')
                }
                if (notes.isNotEmpty()) append('\n')
            }

            if (notes.isNotEmpty()) {
                append(labels.notes).append('\n')
                notes.forEach { n ->
                    append("• ")
                    if (n.chapter.isNotBlank()) append(n.chapter).append(": ")
                    append('“').append(n.quote.trim()).append('”').append('\n')
                    if (n.note.isNotBlank()) append("  ").append(labels.note).append(": ").append(n.note.trim()).append('\n')
                }
            }
        }.trimEnd()
    }
}
