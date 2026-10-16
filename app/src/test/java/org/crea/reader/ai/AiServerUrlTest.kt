package org.crea.reader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiServerUrlTest {
    @Test
    fun `accepts an https address and removes the trailing slash`() {
        assertEquals("https://crea-reader-ai.onrender.com", AiServerUrl.normalize("  https://crea-reader-ai.onrender.com/  "))
        assertEquals("https://example.org:8443/api", AiServerUrl.normalize("https://Example.org:8443/api/"))
    }

    @Test
    fun `rejects plain http except the local test server in debug`() {
        assertNull(AiServerUrl.normalize("http://crea-reader-ai.onrender.com"))
        assertNull(AiServerUrl.normalize("http://localhost:8787"))
        assertEquals("http://localhost:8787", AiServerUrl.normalize("http://localhost:8787", allowLocalHttp = true))
        assertNull(AiServerUrl.normalize("http://evil.example.com", allowLocalHttp = true))
    }

    @Test
    fun `rejects addresses that could leak the token or are not addresses`() {
        assertNull(AiServerUrl.normalize(""))
        assertNull(AiServerUrl.normalize("crea-reader-ai.onrender.com"))
        assertNull(AiServerUrl.normalize("https://user:pass@example.org"))
        assertNull(AiServerUrl.normalize("https://example.org/?next=https://evil.example.com"))
        assertNull(AiServerUrl.normalize("https://example.org/#x"))
        assertNull(AiServerUrl.normalize("ftp://example.org"))
        assertNull(AiServerUrl.normalize("https://"))
    }

    @Test
    fun `host is read for display`() {
        assertEquals("example.org", AiServerUrl.hostOf("https://example.org:8443/api"))
    }
}
