package org.crea.reader.sync

/** Pure helpers for the files that live in the visible "CREA Reader" Drive folder. */
object DriveNaming {
    const val FOLDER_NAME = "CREA Reader"

    private const val MAX_BASE_LENGTH = 120
    private const val SEPARATOR = " - "
    private val forbidden = Regex("""[\/:*?"<>|\u0000-\u001F]""")
    private val spaces = Regex("""\s+""")
    private val webPrefix = Regex("""^(?:libros?\s+)?epub\s+""", RegexOption.IGNORE_CASE)
    private val slugHyphen = Regex("""(?<=\S)-(?=\S)""")

    data class BookNames(val title: String, val author: String?)

    fun isBookFile(name: String, mimeType: String?): Boolean = formatOf(name, mimeType) != null

    /** "EPUB" or "PDF", or null when the file is not a book this app can read. */
    fun formatOf(name: String, mimeType: String?): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension == "epub" || mimeType == "application/epub+zip" -> "EPUB"
            extension == "pdf" || mimeType == "application/pdf" -> "PDF"
            else -> null
        }
    }

    fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            else -> "application/octet-stream"
        }

    /**
     * Title and author to show for a Drive file. Drive stores no author, so it is only read from
     * the "Title - Author" names this app writes ([createdByApp]); any other file keeps its
     * whole name as the title instead of guessing wrong.
     */
    fun describe(fileName: String, createdByApp: Boolean): BookNames {
        val base = if (formatOf(fileName, null) != null) fileName.substringBeforeLast('.') else fileName
        val clean = base.replace('_', ' ').replace(spaces, " ").trim().ifEmpty { fileName.trim() }
        val tidy = if (createdByApp) clean else tidyForeign(clean)
        if (createdByApp) {
            val at = clean.lastIndexOf(SEPARATOR)
            if (at > 0) {
                val title = clean.substring(0, at).trim()
                val author = clean.substring(at + SEPARATOR.length).trim()
                if (title.isNotEmpty() && author.isNotEmpty()) return BookNames(title, author)
            }
        }
        return BookNames(tidy, null)
    }

    /**
     * Web downloads often name a file like "libros epub La-leyenda-de-Sleepy-Hollow": drop the "libros epub" prefix
     * and read the hyphens as spaces when the name is a slug (two or more), so "Spider-Man" keeps its hyphen.
     */
    private fun tidyForeign(name: String): String {
        val noPrefix = name.replace(webPrefix, "").ifEmpty { name }
        val slug = if (slugHyphen.findAll(noPrefix).count() >= 2) noPrefix.replace(slugHyphen, " ") else noPrefix
        return slug.replace(spaces, " ").trim().ifEmpty { name }
    }

    /** "Title - Author.ext", safe for any file system and short enough for Drive listings. */
    fun bookFileName(title: String?, author: String?, extension: String): String {
        val base = listOfNotNull(title?.takeIf { it.isNotBlank() }, author?.takeIf { it.isNotBlank() })
            .joinToString(SEPARATOR)
            .replace(forbidden, " ")
            .replace(spaces, " ")
            .trim()
            .trim('.')
            .take(MAX_BASE_LENGTH)
            .trim()
            .ifEmpty { "Libro" }
        return "$base.${extension.lowercase().ifBlank { "epub" }}"
    }
}
