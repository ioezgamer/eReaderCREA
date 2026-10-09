package org.crea.reader.online

import java.security.MessageDigest

/**
 * The reader identifies every book by a `Long`. Books in "Mi biblioteca" use their database id
 * (always positive); a catalog book opened with "Leer en línea" has no database row, so it gets a
 * stable negative id derived from its Drive file id. Bookmarks and reading position follow that id.
 */
object OnlineBookIds {
    fun forDriveFile(driveFileId: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(driveFileId.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (index in 0 until Long.SIZE_BYTES) value = (value shl 8) or (digest[index].toLong() and 0xff)
        return -(value and Long.MAX_VALUE).coerceAtLeast(1)
    }

    fun isOnline(bookId: Long): Boolean = bookId < 0
}
