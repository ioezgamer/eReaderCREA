package org.crea.reader.ai

import java.net.URI

/**
 * Validation of the address of the AI server typed in Ajustes.
 *
 * The app sends its Google token to this address so the server can check the account, so only a
 * plain `https` address of a host is accepted. Plain `http` is allowed only for the local test
 * server and only when [allowLocalHttp] is true (debug builds).
 */
object AiServerUrl {
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    /** The clean address (no trailing slash), or null when [input] is not acceptable. */
    fun normalize(input: String, allowLocalHttp: Boolean = false): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val localHttp = allowLocalHttp && uri.scheme.equals("http", ignoreCase = true) && host in LOCAL_HOSTS
        if (!secure && !localHttp) return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "${uri.scheme.lowercase()}://$host$port$path"
    }

    /** Just the host, for showing in the UI. */
    fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
}
