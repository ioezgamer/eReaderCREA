package org.crea.reader.online

import java.io.File
import org.crea.reader.data.model.DriveBook

/**
 * Private, temporary copies of the books opened with "Leer en línea".
 *
 * Readium needs the whole file to open an EPUB or PDF, so reading online fetches the book once
 * into the app cache (never into "Mi biblioteca"), and the copy is discarded automatically:
 * anything untouched for [MAX_AGE_MS] goes, and the oldest files go first when the cache would
 * pass [MAX_BYTES]. Android may also empty a cache directory by itself when storage runs low.
 */
class OnlineReadingCache(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
) {

    fun fileFor(book: DriveBook): File =
        File(dir, "${book.driveFileId}_${book.version.take(VERSION_LENGTH)}.${book.format.lowercase()}")

    fun partialFor(book: DriveBook): File = File(dir, fileFor(book).name + PARTIAL_SUFFIX)

    /** The cached copy of this exact version of the book, if it is still complete. */
    fun cached(book: DriveBook): File? =
        fileFor(book).takeIf { it.isFile && (book.size <= 0 || it.length() == book.size) }

    fun ensureDir(): File = dir.apply { mkdirs() }

    /** Space taken by the temporary copies, in bytes. */
    fun sizeBytes(): Long = dir.listFiles()?.filter { it.isFile }.orEmpty().sumOf { it.length() }

    /**
     * Deletes every temporary copy that is not being downloaded right now. Returns the bytes freed.
     * [protectedFiles] (a book that is open) are kept.
     */
    fun clear(protectedFiles: Set<File> = emptySet()): Long {
        val keep = protectedFiles.map { it.absolutePath }.toSet()
        var freed = 0L
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(PARTIAL_SUFFIX) && it.absolutePath !in keep }?.forEach {
            val size = it.length()
            if (it.delete()) freed += size
        }
        return freed
    }

    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /**
     * Deletes expired and surplus files. [protectedFiles] (a book that is open or about to open)
     * are never removed.
     */
    fun cleanUp(protectedFiles: Set<File> = emptySet(), now: Long = System.currentTimeMillis()) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val keep = protectedFiles.map { it.absolutePath }.toSet()
        val removable = files.filter { it.absolutePath !in keep }

        // Interrupted downloads and expired books.
        removable
            .filter { file ->
                val limit = if (file.name.endsWith(PARTIAL_SUFFIX)) PARTIAL_MAX_AGE_MS else MAX_AGE_MS
                now - file.lastModified() > limit
            }
            .forEach { it.delete() }

        // Size cap, oldest first.
        val remaining = dir.listFiles()?.filter { it.isFile }.orEmpty()
        var total = remaining.sumOf { it.length() }
        // Downloads in progress (.part) are never trimmed: another transfer is still writing them.
        val trimmable = remaining.filter { it.absolutePath !in keep && !it.name.endsWith(PARTIAL_SUFFIX) }
        for (file in trimmable.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val MAX_BYTES = 400L * 1024 * 1024
        private const val PARTIAL_MAX_AGE_MS = 60L * 60 * 1000
        private const val PARTIAL_SUFFIX = ".part"
        private const val VERSION_LENGTH = 12
    }
}
