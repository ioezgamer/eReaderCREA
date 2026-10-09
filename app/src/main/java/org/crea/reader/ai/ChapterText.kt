package org.crea.reader.ai

import org.jsoup.Jsoup
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** The plain text of the chapter (the file of the book) the reader is on, for the AI helpers. */
object ChapterText {
    class Chapter(val text: String, val title: String?)

    /** Null for books without readable text, such as scanned PDFs, or when it is too short to be useful. */
    suspend fun read(publication: Publication, locator: Locator): Chapter? {
        val link = publication.linkWithHref(locator.href) ?: return null
        if (link.mediaType?.isHtml != true) return null
        val bytes = publication.get(link)?.read()?.getOrNull() ?: return null
        val document = Jsoup.parse(String(bytes, Charsets.UTF_8))
        val text = document.text().replace(Regex("""\s+"""), " ").trim()
        if (text.length < MIN_CHARS) return null
        return Chapter(text.take(MAX_CHARS), locator.title ?: document.title().takeIf { it.isNotBlank() })
    }

    private const val MIN_CHARS = 200
    private const val MAX_CHARS = 30_000
}
