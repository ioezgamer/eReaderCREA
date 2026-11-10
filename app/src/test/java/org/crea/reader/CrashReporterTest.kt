package org.crea.reader

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrashReporterTest {

    private lateinit var dir: File

    @Before
    fun create() {
        dir = Files.createTempDirectory("crash").toFile()
    }

    @After
    fun delete() {
        dir.deleteRecursively()
    }

    @Test
    fun aCrashReportIsShownOnceOnTheNextLaunch() {
        assertNull(CrashReporter.takePending(dir))
        CrashReporter.savePending(dir, "java.lang.IllegalStateException: boom")

        assertEquals("java.lang.IllegalStateException: boom", CrashReporter.takePending(dir))
        assertNull("only once", CrashReporter.takePending(dir))
        assertTrue("kept for reference", File(dir, "last-crash.txt").isFile)
        assertFalse(File(dir, "pending-crash.txt").exists())
    }

    @Test
    fun aNewerCrashReplacesTheReportKeptBefore() {
        CrashReporter.savePending(dir, "first")
        CrashReporter.takePending(dir)
        CrashReporter.savePending(dir, "second")
        assertEquals("second", CrashReporter.takePending(dir))
        assertEquals("second", File(dir, "last-crash.txt").readText())
    }
}
