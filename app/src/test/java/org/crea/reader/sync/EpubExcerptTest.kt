package org.crea.reader.sync

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubExcerptTest {

    private class MemorySource(private val bytes: ByteArray) : RangeSource {
        var fetched = 0L
        override val size: Long get() = bytes.size.toLong()
        override suspend fun read(start: Long, endInclusive: Long): ByteArray {
            val end = minOf(endInclusive, size - 1).toInt()
            return bytes.copyOfRange(start.toInt(), end + 1).also { fetched += it.size }
        }
    }

    private fun page(body: String) = "<html><body><p>$body</p></body></html>"

    private val chapterOne = "Era una tarde de lluvia en Macondo y nadie sabía que el pueblo iba a cambiar para siempre. ".repeat(12)
    private val chapterTwo = "Úrsula buscaba a su hijo por todo el pueblo sin encontrar rastro alguno de él. ".repeat(12)

    private fun epub(opf: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            add("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""")
            add("OEBPS/content.opf", opf)
            add("OEBPS/cover.xhtml", page("Portada"))
            add("OEBPS/toc.xhtml", page("Capítulo 1 Capítulo 2 Capítulo 3 ".repeat(20)))
            add("OEBPS/text/ch1.xhtml", page(chapterOne))
            add("OEBPS/text/ch2.xhtml", page(chapterTwo))
            zip.putNextEntry(ZipEntry("OEBPS/big.bin"))
            zip.write(Random(3).nextBytes(1_500_000))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private val opf = """<package><manifest>
        <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
        <item id="toc" href="toc.xhtml" media-type="application/xhtml+xml"/>
        <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
        <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
        </manifest><spine><itemref idref="cover"/><itemref idref="toc"/><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""

    @Test
    fun skipsCoverAndContentsAndStartsAtTheFirstRealText() = runBlocking {
        val source = MemorySource(epub(opf))
        val text = EpubExcerpt.find(source, maxChars = 3500)
        assertNotNull(text)
        assertTrue(text!!.startsWith("Era una tarde de lluvia"))
        assertFalse(text.contains("Portada"))
        assertFalse(text.contains("Capítulo 1 Capítulo 2"))
        assertTrue("fetched ${source.fetched} of ${source.size}", source.fetched < 200_000)
    }

    @Test
    fun respectsTheCharacterLimit() = runBlocking {
        val text = EpubExcerpt.find(MemorySource(epub(opf)), maxChars = 300)
        assertEquals(300, text!!.length)
    }

    @Test
    fun returnsNullWhenThereIsNoReadableText() = runBlocking {
        val onlyShort = opf.replace("""<itemref idref="c1"/><itemref idref="c2"/>""", "")
        assertNull(EpubExcerpt.find(MemorySource(epub(onlyShort))))
        assertNull(EpubExcerpt.find(MemorySource(ByteArray(2000) { 1 })))
    }
}
