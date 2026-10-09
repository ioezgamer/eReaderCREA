package org.crea.reader.catalogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCatalogsTest {
    @Test
    fun everyDefaultCatalogIsUnique() {
        assertEquals(DefaultCatalogs.all.size, DefaultCatalogs.all.map { it.href }.toSet().size)
        assertEquals(DefaultCatalogs.all.size, DefaultCatalogs.all.map { it.title }.toSet().size)
    }

    @Test
    fun gutenbergFeedsAreRestrictedToSpanish() {
        val gutenberg = DefaultCatalogs.all.filter { it.source == DefaultCatalogs.Source.GUTENBERG }
        assertTrue(gutenberg.isNotEmpty())
        gutenberg.forEach { assertTrue(it.href, "query=l.es" in it.href) }
    }

    @Test
    fun feedsUseHttps() {
        DefaultCatalogs.all.forEach { assertTrue(it.href, it.href.startsWith("https://")) }
    }

    @Test
    fun englishSampleCatalogsAreRetiredNotOffered() {
        DefaultCatalogs.retiredHrefs.forEach { retired ->
            assertFalse(retired, DefaultCatalogs.all.any { it.href == retired })
        }
    }

    @Test
    fun lookupByHrefFindsTheEntry() {
        val first = DefaultCatalogs.all.first()
        assertEquals(first, DefaultCatalogs.find(first.href))
    }
}
