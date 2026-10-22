package org.crea.reader.readers

import kotlinx.serialization.json.Json
import org.crea.reader.sync.GoogleDriveRestClient
import timber.log.Timber

/**
 * Keeps reader profiles in step with the shared Drive folder: one small file per reader
 * ("crea-perfil-<id>.json"), so a person continues on any tablet of the account. Each tablet
 * uploads the profiles that changed on it and joins in the ones that changed elsewhere; joining
 * never loses reading (see [ReaderRules.merge]). A failure here never stops the rest of the sync.
 */
class ReaderSync(private val store: ReaderStore) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun sync(drive: GoogleDriveRestClient, folderId: String) {
        try {
            val files = drive.listReaderFiles(folderId).associateBy { idOf(it.name) }
            // 1. Bring in what changed on other tablets.
            for ((id, file) in files) {
                if (id == null) continue
                val version = versionOf(file)
                if (store.knownVersion(id) == version) continue
                val remote = runCatching {
                    json.decodeFromString(ReaderProfile.serializer(), drive.downloadText(file.id))
                }.getOrNull() ?: continue
                if (remote.id != id) continue
                store.mergeRemote(remote, version)
            }
            // 2. Send what changed here (merged with Drive above, so nothing is overwritten).
            for (id in store.dirtyIds()) {
                if (id == ReaderStore.GUEST_ID) continue
                val profile = store.localCopy(id) ?: continue
                val uploaded = drive.uploadReader(
                    folderId = folderId,
                    name = fileName(id),
                    value = json.encodeToString(ReaderProfile.serializer(), profile),
                    existingFileId = files[id]?.id,
                )
                store.markUploaded(id, versionOf(uploaded))
            }
        } catch (error: Exception) {
            Timber.w(error, "Reader profiles sync skipped")
        }
    }

    private fun versionOf(file: GoogleDriveRestClient.DriveFile): String =
        file.md5Checksum ?: file.modifiedTime.orEmpty()

    companion object {
        fun fileName(id: String) = "${GoogleDriveRestClient.READER_PREFIX}$id.json"

        fun idOf(name: String): String? =
            name.removePrefix(GoogleDriveRestClient.READER_PREFIX).removeSuffix(".json")
                .takeIf { name.startsWith(GoogleDriveRestClient.READER_PREFIX) && name.endsWith(".json") && it.isNotBlank() }
    }
}
