package org.crea.reader.sync

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.crea.reader.online.CategoryPaths

class GoogleDriveRestClient(
    /** The signed-in CREA account's token; null when reading the public catalog with [publicAccess]. */
    private val accessToken: String?,
    private val publicAccess: PublicCatalog.Access? = null,
    /** Drive's addresses; only tests point them elsewhere. */
    private val api: String = API,
    private val uploadApi: String = UPLOAD,
) {
    @Serializable
    data class DriveFile(
        val id: String,
        val name: String = "",
        val mimeType: String? = null,
        val size: String? = null,
        val modifiedTime: String? = null,
        val md5Checksum: String? = null,
        val ownedByMe: Boolean = true,
        val trashed: Boolean = false,
        val appProperties: Map<String, String>? = null,
        val thumbnailLink: String? = null,
    )

    @Serializable
    data class DriveUser(
        val displayName: String? = null,
        val emailAddress: String? = null,
        val permissionId: String? = null,
    )

    @Serializable
    private data class DriveFileList(
        val files: List<DriveFile> = emptyList(),
        val nextPageToken: String? = null,
    )

    @Serializable
    private data class AboutResponse(val user: DriveUser? = null)

    @Serializable
    private data class CreateMetadata(
        val name: String? = null,
        val mimeType: String? = null,
        val parents: List<String>? = null,
        @SerialName("appProperties") val properties: Map<String, String>? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun currentUser(): DriveUser? = withContext(Dispatchers.IO) {
        open("$api/about?fields=${encode("user(displayName,emailAddress,permissionId)")}").using { connection ->
            connection.requestMethod = "GET"
            connection.requireSuccess()
            connection.inputStream.bufferedReader().use { reader ->
                json.decodeFromString<AboutResponse>(reader.readText()).user
            }
        }
    }

    /**
     * Looks for a non-trashed folder called [name]. Folders owned by the user win over folders
     * shared with the user so a stray shared copy never replaces the personal library.
     */
    suspend fun findFolder(name: String): DriveFile? {
        val query = "mimeType = '$FOLDER_MIME' and name = '${escapeQuery(name)}' and trashed = false"
        return list(query = query, spaces = "drive")
            .sortedByDescending { it.ownedByMe }
            .firstOrNull()
    }

    /**
     * A file's catalog metadata, or null when it is gone, trashed or no longer shared with the app.
     * Drive also answers 403 when a quota or rate limit is hit; that is a temporary failure and
     * throws, so a busy moment never looks like a deleted book.
     */
    suspend fun getFile(fileId: String): DriveFile? = withContext(Dispatchers.IO) {
        open("$api/files/$fileId?fields=${encode("$CATALOG_FIELDS,trashed")}").using { connection ->
            connection.requestMethod = "GET"
            val status = connection.responseCode
            if (status == 404) return@using null
            if (status == 403) {
                val body = connection.errorBody()
                if (isRateLimit(body)) throw DriveApiException(status, body)
                return@using null
            }
            connection.requireSuccess()
            connection.inputStream.bufferedReader().use { reader ->
                json.decodeFromString<DriveFile>(reader.readText()).takeUnless { it.trashed }
            }
        }
    }

    suspend fun getFolder(folderId: String): DriveFile? = withContext(Dispatchers.IO) {
        val fields = encode("id,name,mimeType,trashed")
        open("$api/files/$folderId?fields=$fields").using { connection ->
            connection.requestMethod = "GET"
            if (connection.responseCode == 404) return@using null
            connection.requireSuccess()
            connection.inputStream.bufferedReader().use { reader ->
                json.decodeFromString<DriveFile>(reader.readText()).takeUnless { it.trashed }
            }
        }
    }

    suspend fun createFolder(name: String): DriveFile = withContext(Dispatchers.IO) {
        open("$api/files?fields=${encode(FILE_FIELDS)}").using { connection ->
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.outputStream.bufferedWriter().use {
                it.write(json.encodeToString(CreateMetadata.serializer(), CreateMetadata(name = name, mimeType = FOLDER_MIME)))
            }
            connection.requireSuccess()
            connection.inputStream.bufferedReader().use { json.decodeFromString<DriveFile>(it.readText()) }
        }
    }

    class CatalogEntry(val file: DriveFile, val category: String?)

    /** Every book in the folder tree, and every sub-folder by its category path. */
    class CatalogListing(val entries: List<CatalogEntry>, val folders: Map<String, String>)

    /**
     * Metadata of every EPUB/PDF under [rootFolderId], walking sub-folders. The folder path below
     * the root is the category ("Ciencias / Biología"); books at the root have none. Only listings
     * are requested; no file content is read.
     */
    suspend fun listCatalog(rootFolderId: String): CatalogListing {
        val entries = mutableListOf<CatalogEntry>()
        val folders = mutableMapOf<String, String>()
        val visited = mutableSetOf(rootFolderId)
        val pending = ArrayDeque<Pair<String, String?>>()
        pending.addLast(rootFolderId to null)
        while (pending.isNotEmpty()) {
            // A partial listing would look like deleted books, so give up instead of returning less.
            check(visited.size <= MAX_FOLDERS) { "La carpeta de Drive tiene demasiadas subcarpetas." }
            val (folderId, category) = pending.removeFirst()
            val children = list(
                query = "'$folderId' in parents and trashed = false",
                spaces = "drive",
                fields = CATALOG_FIELDS,
            )
            for (child in children) {
                when {
                    child.mimeType == FOLDER_MIME -> if (visited.add(child.id)) {
                        val path = CategoryPaths.join(category, child.name)
                        // Two folders with the same name at the same level: the first one listed wins.
                        folders.putIfAbsent(path, child.id)
                        pending.addLast(child.id to path)
                    }
                    DriveNaming.isBookFile(child.name, child.mimeType) -> entries += CatalogEntry(child, category)
                }
            }
        }
        return CatalogListing(entries, folders)
    }

    suspend fun listAppData(): List<DriveFile> =
        list(query = null, spaces = APP_DATA_FOLDER)

    /**
     * Streams a file to [destination], reporting progress and honouring cancellation.
     * @return the MD5 of what was written, to compare with [DriveFile.md5Checksum].
     */
    suspend fun download(
        fileId: String,
        destination: File,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val connection = open("$api/files/$fileId?alt=media")
        coroutineScope {
            // A blocked read cannot be interrupted, so cancelling closes the connection instead.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    connection.disconnect()
                }
            }
            try {
                connection.requestMethod = "GET"
                connection.requireSuccess()
                val total = connection.contentLengthLong
                val md5 = MessageDigest.getInstance("MD5")
                destination.outputStream().buffered().use { output ->
                    connection.inputStream.buffered().use { input ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER)
                        var copied = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            md5.update(buffer, 0, read)
                            copied += read
                            onProgress(copied, total)
                        }
                    }
                }
                md5.digest().joinToString("") { "%02x".format(it) }
            } finally {
                watcher.cancel()
            }
        }
    }

    /** A Drive file read through HTTP range requests, for looking inside it without downloading it. */
    fun rangeSource(fileId: String, size: Long): RangeSource = object : RangeSource {
        override val size: Long = size

        override suspend fun read(start: Long, endInclusive: Long): ByteArray = withContext(Dispatchers.IO) {
            val connection = open("$api/files/$fileId?alt=media")
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Range", "bytes=$start-$endInclusive")
                connection.requireSuccess()
                // A plain 200 would mean the whole file is coming; never read that.
                if (connection.responseCode != 206) throw IOException("Drive did not honour the range request")
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Saves a Drive thumbnail. The token is only sent to Google hosts, and a missing or expired
     * thumbnail is not an error: the card just keeps its placeholder.
     */
    suspend fun fetchThumbnail(thumbnailLink: String, destination: File): Boolean = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(thumbnailLink.replace(Regex("=s\\d+$"), "=s$THUMBNAIL_SIZE")) }.getOrNull()
            ?: return@withContext false
        val host = uri.host?.lowercase() ?: return@withContext false
        val trusted = uri.scheme == "https" &&
            (host.endsWith(".googleusercontent.com") || host.endsWith(".google.com") || host == "www.googleapis.com")
        if (!trusted) return@withContext false
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            if (connection.responseCode !in 200..299 || connection.contentLengthLong > MAX_THUMBNAIL_BYTES) {
                return@withContext false
            }
            // Content-Length may be missing (chunked): the size is also checked while reading.
            val complete = destination.outputStream().buffered().use { output ->
                connection.inputStream.buffered().use { input -> input.copyAtMost(output, MAX_THUMBNAIL_BYTES) }
            }
            if (!complete) destination.delete()
            complete
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadText(fileId: String): String = withContext(Dispatchers.IO) {
        open("$api/files/$fileId?alt=media").using { connection ->
            connection.requestMethod = "GET"
            connection.requireSuccess()
            connection.inputStream.bufferedReader().use { it.readText() }
        }
    }

    suspend fun uploadBook(
        folderId: String,
        name: String,
        file: File,
        contentHash: String,
    ): DriveFile = upload(
        name = name,
        size = file.length(),
        mimeType = DriveNaming.mimeTypeFor(name),
        parentId = folderId,
        existingFileId = null,
        appProperties = mapOf("kind" to "book", "sha256" to contentHash),
    ) { file.inputStream().buffered() }

    suspend fun uploadManifest(
        name: String,
        value: String,
        existingFileId: String?,
    ): DriveFile = uploadJson(APP_DATA_FOLDER, name, value, existingFileId, kind = "manifest")

    /** Saves [value] as a new file next to the manifest (for example a damaged manifest kept for recovery). */
    suspend fun uploadAppDataCopy(name: String, value: String): DriveFile =
        uploadJson(APP_DATA_FOLDER, name, value, existingFileId = null, kind = "backup")

    /** The shared reading-activity files other people's apps left in [folderId] (see [CommunityProfile]). */
    suspend fun listCommunityFiles(folderId: String): List<DriveFile> = listByPrefix(folderId, COMMUNITY_PREFIX)

    /** Writes this user's own reading-activity file into the shared folder. */
    suspend fun uploadCommunity(folderId: String, name: String, value: String, existingFileId: String?): DriveFile =
        uploadJson(folderId, name, value, existingFileId, kind = "community")

    /** Reader profiles (see [org.crea.reader.readers.ReaderProfile]) stored in the shared folder. */
    suspend fun listReaderFiles(folderId: String): List<DriveFile> = listByPrefix(folderId, READER_PREFIX)

    suspend fun uploadReader(folderId: String, name: String, value: String, existingFileId: String?): DriveFile =
        uploadJson(folderId, name, value, existingFileId, kind = "reader")

    suspend fun listByName(folderId: String, name: String): List<DriveFile> =
        list(
            query = "'$folderId' in parents and trashed = false and name = '${escapeQuery(name)}'",
            spaces = "drive",
        )

    /** The shared AI information file of the folder (see [InsightIndex]). */
    suspend fun uploadInsights(folderId: String, name: String, value: String, existingFileId: String?): DriveFile =
        uploadJson(folderId, name, value, existingFileId, kind = "insights")

    private suspend fun listByPrefix(folderId: String, prefix: String): List<DriveFile> =
        list(
            query = "'$folderId' in parents and trashed = false and name contains '${escapeQuery(prefix)}'",
            spaces = "drive",
        ).filter { it.name.startsWith(prefix) }

    private suspend fun uploadJson(
        parentId: String,
        name: String,
        value: String,
        existingFileId: String?,
        kind: String,
    ): DriveFile {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return upload(
            name = name,
            size = bytes.size.toLong(),
            mimeType = "application/json",
            parentId = parentId,
            existingFileId = existingFileId,
            appProperties = mapOf("kind" to kind),
        ) { bytes.inputStream() }
    }

    private suspend fun list(query: String?, spaces: String, fields: String = FILE_FIELDS): List<DriveFile> =
        withContext(Dispatchers.IO) {
            val fieldMask = encode("nextPageToken,files($fields)")
            val result = mutableListOf<DriveFile>()
            var pageToken: String? = null
            do {
                val url = buildString {
                    append("$api/files?spaces=$spaces&pageSize=$PAGE_SIZE&fields=$fieldMask")
                    if (spaces == "drive" && accessToken != null) append("&corpora=user")
                    if (query != null) append("&q=").append(encode(query))
                    if (pageToken != null) append("&pageToken=").append(encode(pageToken))
                }
                val page = open(url).using { connection ->
                    connection.requestMethod = "GET"
                    connection.requireSuccess()
                    connection.inputStream.bufferedReader().use { reader ->
                        json.decodeFromString<DriveFileList>(reader.readText())
                    }
                }
                result += page.files
                pageToken = page.nextPageToken
            } while (pageToken != null)
            result
        }

    private suspend fun upload(
        name: String,
        size: Long,
        mimeType: String,
        parentId: String,
        existingFileId: String?,
        appProperties: Map<String, String>,
        stream: () -> InputStream,
    ): DriveFile = withContext(Dispatchers.IO) {
        val metadata = CreateMetadata(
            name = name,
            parents = if (existingFileId == null) listOf(parentId) else null,
            properties = appProperties.ifEmpty { null }
        )
        val responseFields = encode(FILE_FIELDS)
        val endpoint = if (existingFileId == null) {
            "$uploadApi/files?uploadType=resumable&fields=$responseFields"
        } else {
            "$uploadApi/files/$existingFileId?uploadType=resumable&fields=$responseFields"
        }
        val sessionUrl = open(endpoint).using { start ->
            if (existingFileId == null) start.requestMethod = "POST" else start.patch()
            start.doOutput = true
            start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            start.setRequestProperty("X-Upload-Content-Type", mimeType)
            start.setRequestProperty("X-Upload-Content-Length", size.toString())
            start.outputStream.bufferedWriter().use { writer ->
                writer.write(json.encodeToString(CreateMetadata.serializer(), metadata))
            }
            start.requireSuccess()
            requireNotNull(start.getHeaderField("Location")) {
                "Google Drive did not return a resumable upload URL"
            }
        }

        open(sessionUrl).using { put ->
            put.requestMethod = "PUT"
            put.doOutput = true
            put.setRequestProperty("Content-Type", mimeType)
            put.setFixedLengthStreamingMode(size)
            stream().use { input ->
                put.outputStream.buffered().use { output -> input.copyTo(output) }
            }
            put.requireSuccess()
            put.inputStream.bufferedReader().use { reader ->
                json.decodeFromString<DriveFile>(reader.readText())
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        val access = publicAccess.takeIf { accessToken == null }
        // Public files are read with the app's API key, restricted to this app's package and certificate.
        val target = if (access == null) url else url + (if ('?' in url) "&" else "?") + "key=" + encode(access.apiKey)
        return (URI(target).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
            if (access != null) {
                setRequestProperty("X-Android-Package", access.packageName)
                setRequestProperty("X-Android-Cert", access.certificateSha1)
            }
        }
    }

    /** HttpURLConnection rejects PATCH on some platforms; Google honours the override header. */
    private fun HttpURLConnection.patch() {
        requestMethod = "POST"
        setRequestProperty("X-HTTP-Method-Override", "PATCH")
    }

    private fun HttpURLConnection.requireSuccess() {
        if (responseCode in 200..299) return
        throw DriveApiException(responseCode, errorBody())
    }

    private fun HttpURLConnection.errorBody(): String =
        runCatching { errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()

    /** Runs [block] and always releases the connection. */
    private inline fun <T> HttpURLConnection.using(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    class DriveApiException(val statusCode: Int, val responseBody: String) :
        Exception("Google Drive request failed ($statusCode): $responseBody") {
        /** Worth trying again later: server trouble, or a quota or rate limit (429, or 403 with that reason). */
        val isTemporary: Boolean
            get() = statusCode in 500..599 || statusCode == 429 || (statusCode == 403 && isRateLimit(responseBody))
    }

    companion object {
        private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded")

        /** Whether a Drive error body is a quota or rate limit (Drive uses 403 for those too). */
        fun isRateLimit(body: String): Boolean =
            RATE_LIMIT_REASONS.any { reason -> body.contains("\"$reason\"") }

        /** Escapes a value for a Drive query string literal ('...'). */
        fun escapeQuery(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

        /** Copies at most [limit] bytes; false when the source had more (the copy is then incomplete). */
        internal fun InputStream.copyAtMost(output: java.io.OutputStream, limit: Long): Boolean {
            val buffer = ByteArray(8 * 1024)
            var copied = 0L
            while (true) {
                val read = read(buffer)
                if (read < 0) return true
                copied += read
                if (copied > limit) return false
                output.write(buffer, 0, read)
            }
        }

        const val APP_DATA_FOLDER = "appDataFolder"
        const val COMMUNITY_PREFIX = "crea-lector-"
        const val READER_PREFIX = "crea-perfil-"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FILE_FIELDS = "id,name,mimeType,size,modifiedTime,md5Checksum,ownedByMe,appProperties"
        private const val CATALOG_FIELDS = "$FILE_FIELDS,thumbnailLink"
        private const val PAGE_SIZE = 1000
        private const val MAX_FOLDERS = 500
        private const val DOWNLOAD_BUFFER = 64 * 1024
        private const val THUMBNAIL_SIZE = 400
        private const val MAX_THUMBNAIL_BYTES = 1_000_000L
    }
}
