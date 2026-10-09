package org.crea.reader.sync

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Reads the first pages of real text of an EPUB (skipping cover, table of contents and legal pages)
 * with a few range requests, so a book can be catalogued without downloading it.
 */
object EpubExcerpt {
    private const val CONTAINER = "META-INF/container.xml"
    private const val MAX_DOCUMENTS = 8
    private const val MIN_USEFUL_CHARS = 200
    private val skip = Regex("""cover|toc|nav|copyright|legal|portada|portadilla|colof|indice|índice|contenido|dedic|agradec|titulo|title|licen""", RegexOption.IGNORE_CASE)

    /** About [maxChars] characters of the beginning of the book, or null if no readable text was found. */
    suspend fun find(source: RangeSource, maxChars: Int = 3500): String? {
        val zip = RemoteZip.open(source) ?: return null
        val container = zip.read(CONTAINER)?.toString(Charsets.UTF_8) ?: return null
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).selectFirst("rootfile[full-path]")?.attr("full-path") ?: return null
        val opf = Jsoup.parse(zip.read(opfPath)?.toString(Charsets.UTF_8) ?: return null, "", Parser.xmlParser())

        val manifest = opf.select("manifest > item").associateBy { it.attr("id") }
        val documents = opf.select("spine > itemref").mapNotNull { ref ->
            val item = manifest[ref.attr("idref")] ?: return@mapNotNull null
            val type = item.attr("media-type")
            val href = item.attr("href")
            if (!(type.contains("html") || type.contains("xml")) || href.isBlank()) return@mapNotNull null
            if (skip.containsMatchIn(item.attr("id")) || skip.containsMatchIn(href.substringAfterLast('/'))) return@mapNotNull null
            EpubCoverFinder.resolve(opfPath, href)
        }

        val text = StringBuilder()
        for (path in documents.take(MAX_DOCUMENTS)) {
            val html = zip.read(path)?.toString(Charsets.UTF_8) ?: continue
            val plain = Jsoup.parse(html).text().replace(Regex("""\s+"""), " ").trim()
            if (plain.length < MIN_USEFUL_CHARS) continue
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(plain)
            if (text.length >= maxChars) break
        }
        return text.takeIf { it.isNotBlank() }?.toString()?.take(maxChars)
    }
}
