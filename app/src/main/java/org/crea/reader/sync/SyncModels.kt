package org.crea.reader.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncManifest(
    val schemaVersion: Int = 1,
    val generatedAt: Long,
    val deviceId: String,
    val books: List<SyncedBook> = emptyList(),
    val tombstones: List<SyncedTombstone> = emptyList(),
    val devices: List<SyncedDevice> = emptyList(),
)

@Serializable
data class SyncedBook(
    val contentHash: String,
    val fileName: String,
    val mediaType: String,
    val title: String,
    val author: String? = null,
    val identifier: String = "",
    val fileSize: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val progression: String = "{}",
    val progressUpdatedAt: Long = 0,
    val driveFileId: String? = null,
)

@Serializable
data class SyncedTombstone(
    val contentHash: String,
    val deletedAt: Long,
)

data class MergePlan(
    val uploadBooks: List<SyncedBook>,
    val downloadBooks: List<SyncedBook>,
    val applyRemoteProgress: List<SyncedBook>,
    val deleteLocalHashes: List<String>,
    val deleteRemoteBooks: List<SyncedBook>,
    val survivingBooks: List<SyncedBook>,
    val survivingTombstones: List<SyncedTombstone>,
)

/** What one installation of the app is reading, so each device's progress can be shown separately. */
@Serializable
data class SyncedDevice(
    val deviceId: String,
    val name: String,
    val updatedAt: Long,
    val readings: List<DeviceReading> = emptyList(),
)

@Serializable
data class DeviceReading(
    val driveFileId: String,
    val percent: Int,
    val updatedAt: Long,
)
