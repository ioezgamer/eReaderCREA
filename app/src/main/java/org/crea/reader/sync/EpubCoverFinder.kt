package org.crea.reader.sync

import java.net.URLDecoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/** Finds an EPUB's cover image fetching only a few kilobytes of the file. */
object EpubCoverFinder {
    private const val CONTAINER = "META-INF/container.xml"

    /** The cover image bytes (any format), or null if the book has none or it cannot be read. */
    suspend fun find(source: RangeSource): ByteArray? {
        val zip = RemoteZip.open(source) ?: return null
        val containerXml = zip.read(CONTAINER)?.toString(Charsets.UTF_8) ?: return null
        val opfPath = xml(containerXml).selectFirst("rootfile[full-path]")?.attr("full-path") ?: return null
        val opf = xml(zip.read(opfPath)?.toString(Charsets.UTF_8) ?: return null)

        val coverPath = coverHref(opf)?.let { resolve(opfPath, it) }
            ?: fallbackByName(zip.names())
            ?: return null
        return zip.read(coverPath)
    }

    internal fun coverHref(opf: Document): String? {
        // EPUB 3: <item properties="cover-image">
        opf.select("item").firstOrNull { item ->
            item.attr("properties").split(' ').contains("cover-image")
        }?.attr("href")?.takeIf { it.isNotBlank() }?.let { return it }

        // EPUB 2: <meta name="cover" content="item-id"/>
        val id = opf.selectFirst("meta[name=cover]")?.attr("content")
        if (!id.isNullOrBlank()) {
            opf.select("item").firstOrNull { it.attr("id") == id }
                ?.attr("href")?.takeIf { it.isNotBlank() }?.let { return it }
        }

        // Some books only list the cover page's image under a conventional id.
        return opf.select("item").firstOrNull { item ->
            item.attr("media-type").startsWith("image/") && item.attr("id").contains("cover", ignoreCase = true)
        }?.attr("href")?.takeIf { it.isNotBlank() }
    }

    /** Resolves a manifest href against the OPF file's folder, handling `..` and %-escapes. */
    internal fun resolve(opfPath: String, href: String): String {
        val folder = opfPath.substringBeforeLast('/', "")
        val decoded = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }
            .getOrDefault(href)
        val parts = ArrayDeque<String>()
        if (folder.isNotEmpty()) parts.addAll(folder.split('/'))
        for (segment in decoded.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun fallbackByName(names: Collection<String>): String? =
        names.firstOrNull { name ->
            val lower = name.lowercase()
            val file = lower.substringAfterLast('/')
            file.startsWith("cover") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))
        }

    private fun xml(text: String): Document = Jsoup.parse(text, "", Parser.xmlParser())
}
