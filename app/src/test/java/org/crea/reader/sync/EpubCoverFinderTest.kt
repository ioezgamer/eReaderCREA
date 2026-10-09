package org.crea.reader.sync

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubCoverFinderTest {

    private class MemorySource(private val bytes: ByteArray) : RangeSource {
        var fetched = 0L
        override val size: Long get() = bytes.size.toLong()
        override suspend fun read(start: Long, endInclusive: Long): ByteArray {
            val end = minOf(endInclusive, size - 1).toInt()
            val part = bytes.copyOfRange(start.toInt(), end + 1)
            fetched += part.size
            return part
        }
    }

    private val cover = Random(1).nextBytes(3000)

    private fun epub(opf: String, coverName: String, stored: Boolean = false, opfPath: String = "OEBPS/content.opf"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String, data: ByteArray, store: Boolean = false) {
                val entry = ZipEntry(name)
                if (store) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = java.util.zip.CRC32().also { it.update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
            add("mimetype", "application/epub+zip".toByteArray(), store = true)
            add(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray()
            )
            add(opfPath, opf.toByteArray())
            add(coverName, cover, store = stored)
            // A large chapter the cover lookup must never download.
            add("OEBPS/big.xhtml", Random(2).nextBytes(2_000_000))
        }
        return out.toByteArray()
    }

    @Test
    fun findsEpub2CoverThroughMetaAndFetchesOnlyAFewKilobytes() = runBlocking {
        val opf = """<package><metadata><meta name="cover" content="cov"/></metadata>
            <manifest><item id="cov" href="images/cover%201.jpg" media-type="image/jpeg"/></manifest></package>"""
        val source = MemorySource(epub(opf, "OEBPS/images/cover 1.jpg"))
        assertArrayEquals(cover, EpubCoverFinder.find(source))
        assertTrue("fetched ${source.fetched} of ${source.size}", source.fetched < 100_000)
    }

    @Test
    fun findsEpub3CoverImageProperty() = runBlocking {
        val opf = """<package><manifest><item id="a" href="../img/c.png" properties="cover-image" media-type="image/png"/></manifest></package>"""
        val source = MemorySource(epub(opf, "img/c.png", stored = true, opfPath = "OEBPS/content.opf"))
        assertArrayEquals(cover, EpubCoverFinder.find(source))
    }

    @Test
    fun fallsBackToAFileNamedCover() = runBlocking {
        val opf = """<package><manifest></manifest></package>"""
        val source = MemorySource(epub(opf, "OEBPS/Images/Cover.jpg"))
        assertArrayEquals(cover, EpubCoverFinder.find(source))
    }

    @Test
    fun returnsNullWhenThereIsNoCoverOrItIsNotAZip() = runBlocking {
        val noCover = MemorySource(epub("""<package><manifest></manifest></package>""", "OEBPS/chapter.jpg"))
        assertNull(EpubCoverFinder.find(noCover))
        assertNull(EpubCoverFinder.find(MemorySource(ByteArray(5000) { 7 })))
        assertNull(EpubCoverFinder.find(MemorySource(ByteArray(10))))
    }

    @Test
    fun resolvesRelativePaths() {
        assertEquals("OEBPS/images/a b.jpg", EpubCoverFinder.resolve("OEBPS/content.opf", "images/a%20b.jpg"))
        assertEquals("img/c.png", EpubCoverFinder.resolve("OEBPS/content.opf", "../img/c.png"))
        assertEquals("cover.jpg", EpubCoverFinder.resolve("content.opf", "./cover.jpg#x"))
    }
}
