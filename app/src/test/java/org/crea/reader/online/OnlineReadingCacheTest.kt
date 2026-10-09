package org.crea.reader.online

import java.io.File
import org.crea.reader.data.model.DriveBook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnlineReadingCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val book = DriveBook(
        driveFileId = "abc",
        fileName = "Libro.epub",
        title = "Libro",
        format = "EPUB",
        size = 4,
        md5 = "0123456789abcdef0123",
        modifiedAt = 1,
        progressionAt = 0,
        readerId = -1,
    )

    private fun cache() = OnlineReadingCache(folder.newFolder("cache"))

    private fun File.write(bytes: Int, ageMs: Long = 0): File {
        writeBytes(ByteArray(bytes))
        setLastModified(System.currentTimeMillis() - ageMs)
        return this
    }

    @Test
    fun copyIsOnlyUsableWhenComplete() {
        val cache = cache()
        cache.ensureDir()
        assertNull(cache.cached(book))
        cache.fileFor(book).write(3)
        assertNull("truncated copy", cache.cached(book))
        cache.fileFor(book).write(4)
        assertNotNull(cache.cached(book))
    }

    @Test
    fun aNewVersionOfTheBookNeverReusesTheOldCopy() {
        val cache = cache()
        cache.ensureDir()
        cache.fileFor(book).write(4)
        assertNull(cache.cached(book.copy(md5 = "ffffffffffffffffffff")))
    }

    @Test
    fun cleanUpRemovesExpiredFilesAndKeepsFreshOnes() {
        val cache = cache()
        val dir = cache.ensureDir()
        val old = File(dir, "old.epub").write(10, ageMs = OnlineReadingCache.MAX_AGE_MS + 1000)
        val fresh = File(dir, "fresh.epub").write(10, ageMs = 1000)
        val staleDownload = File(dir, "x.epub.part").write(10, ageMs = 2 * 60 * 60 * 1000)

        cache.cleanUp()

        assertFalse(old.exists())
        assertFalse(staleDownload.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun cleanUpNeverTouchesProtectedFiles() {
        val cache = cache()
        val open = File(cache.ensureDir(), "open.epub").write(10, ageMs = OnlineReadingCache.MAX_AGE_MS + 1000)
        cache.cleanUp(protectedFiles = setOf(open))
        assertTrue(open.exists())
    }

    @Test
    fun cleanUpTrimsOldestFirstWhenOverTheSizeCap() {
        val cache = OnlineReadingCache(folder.newFolder("capped"), maxBytes = 100)
        val dir = cache.ensureDir()
        val oldest = File(dir, "1.epub").write(60, ageMs = 3000)
        val middle = File(dir, "2.epub").write(60, ageMs = 2000)
        val newest = File(dir, "3.epub").write(60, ageMs = 1000)

        cache.cleanUp()

        assertFalse(oldest.exists())
        // 180 bytes over a 100 byte cap: two files must go, the newest stays.
        assertFalse(middle.exists())
        assertTrue(newest.exists())
    }
}
