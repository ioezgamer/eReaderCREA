package org.crea.reader.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineBookIdsTest {
    @Test
    fun idsAreNegativeSoTheyNeverCollideWithDatabaseIds() {
        listOf("1AbC", "x", "a".repeat(200), "").forEach {
            assertTrue(OnlineBookIds.forDriveFile(it) < 0)
            assertTrue(OnlineBookIds.isOnline(OnlineBookIds.forDriveFile(it)))
        }
        assertFalse(OnlineBookIds.isOnline(1))
    }

    @Test
    fun idIsStableAndDiffersBetweenFiles() {
        assertEquals(OnlineBookIds.forDriveFile("file-1"), OnlineBookIds.forDriveFile("file-1"))
        assertNotEquals(OnlineBookIds.forDriveFile("file-1"), OnlineBookIds.forDriveFile("file-2"))
    }
}
