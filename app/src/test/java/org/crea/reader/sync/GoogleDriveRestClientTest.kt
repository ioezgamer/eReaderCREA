package org.crea.reader.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.crea.reader.sync.GoogleDriveRestClient.Companion.copyAtMost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The Drive client against a small local HTTP server that answers like Drive does. */
class GoogleDriveRestClientTest {

    private lateinit var server: HttpServer
    private val requests = mutableListOf<String>()
    private var handler: (HttpExchange) -> Pair<Int, String> = { 200 to "{}" }

    private val client get() = GoogleDriveRestClient(
        accessToken = "token",
        api = "http://127.0.0.1:${server.address.port}/drive/v3",
        uploadApi = "http://127.0.0.1:${server.address.port}/upload/drive/v3",
    )

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += URLDecoder.decode(exchange.requestURI.rawQuery.orEmpty(), "UTF-8")
            val (status, body) = handler(exchange)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun driveError(status: Int, reason: String) =
        """{"error":{"code":$status,"errors":[{"domain":"usageLimits","reason":"$reason"}]}}"""

    @Test
    fun aMissingFileIsNull() = runBlocking {
        handler = { 404 to driveError(404, "notFound") }
        assertNull(client.getFile("gone"))
    }

    @Test
    fun aFileNoLongerSharedIsNull() = runBlocking {
        handler = { 403 to driveError(403, "insufficientFilePermissions") }
        assertNull(client.getFile("private"))
    }

    @Test
    fun aRateLimitIsATemporaryFailureNotADeletedFile() = runBlocking {
        for (reason in listOf("userRateLimitExceeded", "rateLimitExceeded")) {
            handler = { 403 to driveError(403, reason) }
            try {
                client.getFile("busy")
                fail("a rate limit must not look like a deleted file ($reason)")
            } catch (error: GoogleDriveRestClient.DriveApiException) {
                assertEquals(403, error.statusCode)
                assertTrue(error.isTemporary)
            }
        }
    }

    @Test
    fun aTrashedFileIsNullAndALiveOneIsRead() = runBlocking {
        handler = { 200 to """{"id":"f1","name":"Libro.epub","trashed":true}""" }
        assertNull(client.getFile("f1"))
        handler = { 200 to """{"id":"f1","name":"Libro.epub","modifiedTime":"2026-11-10T10:00:00.000Z"}""" }
        assertEquals("2026-11-10T10:00:00.000Z", client.getFile("f1")?.modifiedTime)
    }

    @Test
    fun namesWithQuotesAreEscapedInQueries() = runBlocking {
        handler = { 200 to """{"files":[]}""" }
        client.listByName("folder", "L'Étranger \\ copia")
        val query = requests.single().substringAfter("q=").substringBefore("&pageToken")
        assertTrue(query, query.contains("name = 'L\\'Étranger \\\\ copia'"))
    }

    @Test
    fun temporaryErrorsAreRecognised() {
        fun error(status: Int, body: String = "") = GoogleDriveRestClient.DriveApiException(status, body)
        assertTrue(error(500).isTemporary)
        assertTrue(error(503).isTemporary)
        assertTrue(error(429).isTemporary)
        assertTrue(error(403, driveError(403, "quotaExceeded")).isTemporary)
        assertFalse(error(403, driveError(403, "insufficientPermissions")).isTemporary)
        assertFalse(error(401).isTemporary)
        assertFalse(error(404).isTemporary)
    }

    @Test
    fun escapeQueryHandlesBackslashesAndQuotes() {
        assertEquals("a\\'b\\\\c", GoogleDriveRestClient.escapeQuery("a'b\\c"))
    }

    @Test
    fun copiesAreCappedEvenWithoutAContentLength() {
        val small = ByteArrayOutputStream()
        assertTrue(ByteArrayInputStream(ByteArray(1000)).copyAtMost(small, 1000))
        assertEquals(1000, small.size())
        val big = ByteArrayOutputStream()
        assertFalse(ByteArrayInputStream(ByteArray(50_000)).copyAtMost(big, 10_000))
        assertTrue(big.size() <= 10_000)
    }
}
