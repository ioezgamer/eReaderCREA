package org.crea.reader.sync

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.crea.reader.data.BookRepository
import org.crea.reader.BuildConfig
import org.crea.reader.ai.AiOutcome
import org.crea.reader.ai.AiResult
import org.crea.reader.ai.AiService
import org.crea.reader.ai.BookInfo
import org.crea.reader.data.db.DriveCatalogDao
import org.crea.reader.data.db.InsightsDao
import org.crea.reader.data.model.BookInsight
import org.crea.reader.data.model.Book
import org.crea.reader.data.model.DriveBook
import org.crea.reader.domain.DuplicateRules
import org.crea.reader.online.OnlineBookIds
import org.crea.reader.utils.ReadingProgress
import org.crea.reader.utils.md5
import timber.log.Timber

/**
 * Keeps the online catalog and the reading progress in step with the "CREA Reader" Drive folder.
 *
 * - The folder is the source of truth for *which books exist*. Each sync reads only metadata
 *   (names, sizes, checksums, thumbnails) into the local catalog; it never downloads a book.
 *   Books reach "Mi biblioteca" only when the user asks for them.
 * - A book imported on this device is uploaded only when the user asks ("Subir a Drive"), to the
 *   folder (category) they choose, and never if the folder already has it (0.9.3). A small manifest
 *   in the app's private Drive space carries reading progress between devices.
 * - Nothing here deletes or modifies a book that is already in the folder.
 */
class LibrarySyncEngine(
    private val bookRepository: BookRepository,
    private val driveCatalog: DriveCatalogDao,
    private val stateStore: SyncStateStore,
    private val coversDir: File,
    private val deviceName: () -> String,
    private val readingsStore: DeviceReadingsStore,
    private val communityStore: CommunityStore,
    private val insightsDao: InsightsDao,
    private val insightsState: InsightsSyncState,
    private val ai: () -> AiService?,
    private val readers: org.crea.reader.readers.ReaderSync? = null,
) {
    enum class Stage { CONNECTING, CHECKING, COVERS, UPLOADING, FINISHING }

    data class Progress(
        val stage: Stage,
        val current: Int = 0,
        val total: Int = 0,
        val title: String? = null,
    )

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _progress = MutableStateFlow<Progress?>(null)

    /** Non-null only while a sync is running, whoever started it (screen or background worker). */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /**
     * [publicDrive] reads the public catalog with the app's key: a personal account only sees what
     * the app created in its own Drive, so the public folder is read separately for it.
     */
    suspend fun sync(accessToken: String, publicDrive: GoogleDriveRestClient? = null): Result = mutex.withLock {
        stateStore.markRunning()
        report(Stage.CONNECTING)
        try {
            run(accessToken, publicDrive)
        } catch (cancelled: CancellationException) {
            stateStore.markError("La sincronización se interrumpió y se reanudará sola.")
            throw cancelled
        } catch (error: Throwable) {
            Timber.e(error, "Library sync failed")
            stateStore.markError(describe(error))
            throw error
        } finally {
            _progress.value = null
        }
    }

    private suspend fun run(accessToken: String, publicDrive: GoogleDriveRestClient?): Result {
        val drive = GoogleDriveRestClient(accessToken)
        val user = runCatching { drive.currentUser() }.getOrNull()
        val account = user?.let { it.emailAddress ?: it.displayName }
        // A personal account: its own folder (only what the app put there) and the public catalog.
        // The shared CREA features (AI summaries, community) stay with CREA accounts.
        val personal = stateStore.isPersonal
        val publicReader = if (personal) publicDrive else drive
        val summary = Counters()

        if (!stateStore.folderMigrated) {
            // Links created by the first release pointed at the hidden app folder; also after
            // switching to another account (SyncStateStore.connect).
            bookRepository.clearRemoteFileIds()
            stateStore.folderMigrated = true
        }

        report(Stage.CHECKING)
        val folderId = resolveFolder(drive)
        // A failed listing throws here, before anything local changes, so a bad connection can
        // never make the catalog look empty.
        val catalogListing = drive.listCatalog(folderId)
        stateStore.categoryFolders = catalogListing.folders
        val listing = catalogListing.entries.toMutableList()

        val manifestFile = drive.listAppData().firstOrNull { it.name == MANIFEST_NAME }
        val storedManifest = manifestFile?.let { file ->
            try {
                json.decodeFromString<SyncManifest>(drive.downloadText(file.id))
            } catch (_: SerializationException) {
                null
            }
        } ?: SyncManifest(generatedAt = 0, deviceId = "remote")

        // Books a personal account picked from elsewhere in its Drive: read where they are.
        val pickedIds = listPicked(drive, storedManifest.pickedFileIds, listing)
        // The public folder's books show up with the rest (once, if it sits inside the private folder).
        val publicListing = publicReader?.let { listPublic(it, folderId) }.orEmpty()
        val publicIds = publicListing.mapTo(hashSetOf()) { it.file.id }
        val privateIds = listing.mapTo(hashSetOf()) { it.file.id }
        listing += publicListing.filter { it.file.id !in privateIds }
        val live = listing.map { it.file.id }.toSet()

        linkLocalCopies(listing)
        // Deletions are no longer propagated in either direction, so old tombstones are dropped.
        val remoteManifest = storedManifest.copy(
            books = storedManifest.books.filter { it.driveFileId == null || it.driveFileId in live },
            tombstones = emptyList(),
        )

        val plan = SyncMerger.plan(
            localBooks = syncableBooks().map { it.toSyncedBook() },
            localTombstones = emptyList(),
            remoteManifest = remoteManifest,
        )

        // Books present in both places but never linked on this device.
        for (surviving in plan.survivingBooks) {
            val driveId = surviving.driveFileId?.takeIf { it in live } ?: continue
            val local = bookRepository.getByContentHash(surviving.contentHash) ?: continue
            if (local.remoteFileId == null) {
                bookRepository.linkToDrive(local.contentHash, driveId, listing.firstOrNull { it.file.id == driveId }?.file?.md5Checksum, local.updatedAt)
            }
        }

        // Only the books the user asked to upload ("Subir a Drive"), each to the folder they chose.
        val requested = stateStore.pendingUploads
        val uploads = requested.keys.mapNotNull { hash ->
            val book = bookRepository.getByContentHash(hash)
            if (book == null || book.remoteFileId != null) {
                stateStore.clearUpload(hash)
                null
            } else {
                book
            }
        }
        for ((index, local) in uploads.withIndex()) {
            report(Stage.UPLOADING, index + 1, uploads.size, local.title)
            val localFile = local.url.toFile()
            if (localFile == null) {
                stateStore.clearUpload(local.contentHash)
                continue
            }
            // The same file may already be in the folder (dropped there by hand): link it instead
            // of uploading a duplicate. Drive reports an MD5 for every file it stores.
            val md5 = localFile.md5()
            val identical = listing.firstOrNull { it.file.md5Checksum == md5 }
            if (identical != null) {
                bookRepository.linkToDrive(local.contentHash, identical.file.id, md5, local.updatedAt)
                stateStore.clearUpload(local.contentHash)
                summary.alreadyInDrive++
                continue
            }
            // Another copy of the same book (same title and author, same format): not uploaded again.
            val format = local.fileExtension().uppercase()
            val sameBook = listing.firstOrNull { entry ->
                val names = DriveNaming.describe(entry.file.name, createdByApp = entry.file.appProperties?.get("kind") == "book")
                DriveNaming.formatOf(entry.file.name, entry.file.mimeType) == format &&
                    // Files put in Drive by hand have no author: their name alone is compared.
                    DuplicateRules.sameTitleAndAuthor(local.title, if (names.author == null) null else local.author, names.title, names.author)
            }
            if (sameBook != null) {
                stateStore.clearUpload(local.contentHash)
                summary.alreadyInDrive++
                continue
            }
            val category = requested[local.contentHash].orEmpty().ifEmpty { null }
            val target = category?.let { catalogListing.folders[it] } ?: folderId
            val uploaded = drive.uploadBook(
                folderId = target,
                name = DriveNaming.bookFileName(local.title, local.author, local.fileExtension()),
                file = localFile,
                contentHash = local.contentHash,
            )
            val uploadedCategory = category?.takeIf { it in catalogListing.folders }
            listing += GoogleDriveRestClient.CatalogEntry(uploaded, category = uploadedCategory)
            bookRepository.linkToDrive(
                local.contentHash,
                uploaded.id,
                uploaded.md5Checksum ?: md5,
                maxOf(local.updatedAt, Clock.System.now().toEpochMilliseconds())
            )
            local.id?.let { bookRepository.setCategory(it, uploadedCategory) }
            stateStore.clearUpload(local.contentHash)
            summary.uploaded++
        }

        report(Stage.CHECKING)
        refreshCatalog(listing, summary)
        // Downloaded books follow their folder if someone moved or renamed it in Drive.
        bookRepository.followDriveCategories()
        recordMissingVersions(listing)
        if (personal) {
            fetchCovers(drive, listing.filter { it.file.id !in publicIds })
            publicReader?.let { reader ->
                fetchCovers(reader, publicListing)
                readPublicInsights(reader, publicListing)
            }
        } else {
            fetchCovers(drive, listing)
            syncInsights(drive, folderId, listing)
            sharePublicInsights(drive, publicIds)
        }

        for (remoteProgress in plan.applyRemoteProgress) {
            bookRepository.saveProgressionFromSync(
                remoteProgress.contentHash,
                remoteProgress.progression,
                remoteProgress.progressUpdatedAt
            )
        }

        report(Stage.FINISHING)
        val now = Clock.System.now().toEpochMilliseconds()
        val localBooks = syncableBooks()
        val localHashes = localBooks.map { it.contentHash }.toSet()
        // Progress of books that are only in the catalog stays in the manifest, so it comes back
        // if the book is downloaded again, here or on another device.
        val finalBooks = (
            localBooks.map { it.toSyncedBook() } +
                remoteManifest.books.filter { it.contentHash !in localHashes && it.driveFileId != null }
            ).sortedBy { it.contentHash }
        // What each device of the account is reading. Every device writes only its own entry and
        // keeps the others as it found them (dropping devices unseen for months).
        val myId = stateStore.deviceId
        val myName = deviceName()
        val ownReadings = ownReadings(live)
        val others = storedManifest.devices
            .filter { it.deviceId != myId && now - it.updatedAt <= DEVICE_TTL_MS }
            .map { device -> device.copy(readings = device.readings.filter { it.driveFileId in live }) }
        val storedOwn = storedManifest.devices.firstOrNull { it.deviceId == myId }
        val ownChanged = storedOwn == null || storedOwn.name != myName || storedOwn.readings != ownReadings
        val own = SyncedDevice(myId, myName, if (ownChanged) now else storedOwn.updatedAt, ownReadings)
        val finalDevices = (others + own).sortedBy { it.deviceId }
        // Many tablets on one account means group tablets, where readers pick their profile.
        stateStore.accountDevices = finalDevices.size
        readingsStore.replace(
            others.flatMap { device ->
                device.readings.map { RemoteReading(device.deviceId, device.name, it.driveFileId, it.percent, it.updatedAt) }
            }
        )

        val unchanged = manifestFile != null &&
            storedManifest.tombstones.isEmpty() &&
            storedManifest.books.map { it.syncKey() }.sorted() == finalBooks.map { it.syncKey() }.sorted() &&
            storedManifest.devices.sortedBy { it.deviceId } == finalDevices &&
            storedManifest.pickedFileIds == pickedIds
        if (!unchanged) {
            val finalManifest = SyncManifest(
                generatedAt = now,
                deviceId = myId,
                books = finalBooks,
                devices = finalDevices,
                pickedFileIds = pickedIds,
            )
            drive.uploadManifest(
                name = MANIFEST_NAME,
                value = json.encodeToString(SyncManifest.serializer(), finalManifest),
                existingFileId = manifestFile?.id,
            )
        }

        if (!personal) syncCommunity(drive, folderId, myId, user?.permissionId, ownReadings, live, now)
        readers?.sync(drive, folderId)

        val result = Result(listing.size, now)
        stateStore.markSuccess(now, listing.size, summary.toSummary(), account)
        return result
    }

    /**
     * Without a CREA account: the catalog is the public folder, read with the app's API key. Only
     * the catalog, covers and the summaries CREA devices published there; nothing is written.
     */
    suspend fun syncPublic(drive: GoogleDriveRestClient): Result = mutex.withLock {
        stateStore.markRunning()
        report(Stage.CHECKING)
        try {
            val summary = Counters()
            val listing = drive.listCatalog(PublicCatalog.folderId).entries
            refreshCatalog(listing, summary)
            fetchCovers(drive, listing)
            readPublicInsights(drive, listing)
            val now = Clock.System.now().toEpochMilliseconds()
            stateStore.markSuccess(now, listing.size, summary.toSummary(), account = null)
            Result(listing.size, now)
        } catch (cancelled: CancellationException) {
            stateStore.markError("La actualización se interrumpió y se reanudará sola.")
            throw cancelled
        } catch (error: Throwable) {
            Timber.e(error, "Public catalog sync failed")
            stateStore.markError(describe(error))
            throw error
        } finally {
            _progress.value = null
        }
    }

    /**
     * Adds to [listing] the books picked with [DrivePicker] (on any device of the account), and
     * returns the ids still valid. Files deleted, trashed or no longer shared drop out; a network
     * failure throws, so a bad connection never drops them.
     */
    private suspend fun listPicked(
        drive: GoogleDriveRestClient,
        stored: List<String>,
        listing: MutableList<GoogleDriveRestClient.CatalogEntry>,
    ): List<String> {
        val requested = (stored + stateStore.pickedFiles).distinct()
        if (requested.isEmpty()) return emptyList()
        val listed = listing.mapTo(hashSetOf()) { it.file.id }
        val valid = mutableListOf<String>()
        for (id in requested) {
            if (id in listed) continue // already in the app's own folder
            val file = drive.getFile(id) ?: continue
            if (!DriveNaming.isBookFile(file.name, file.mimeType)) continue
            listing += GoogleDriveRestClient.CatalogEntry(file, category = null)
            valid += id
        }
        stateStore.pickedFiles = valid.toSet()
        return valid.sorted()
    }

    /**
     * The public folder's books, for a CREA account. A folder that no longer exists (or that this
     * account cannot see) just adds nothing; any other failure stops the sync before anything changes.
     */
    private suspend fun listPublic(drive: GoogleDriveRestClient, privateFolderId: String): List<GoogleDriveRestClient.CatalogEntry> {
        if (!PublicCatalog.isConfigured || PublicCatalog.folderId == privateFolderId) return emptyList()
        return try {
            drive.listCatalog(PublicCatalog.folderId).entries
        } catch (error: GoogleDriveRestClient.DriveApiException) {
            if (error.statusCode != 404) throw error
            Timber.w(error, "Public catalog folder not found")
            emptyList()
        }
    }

    /**
     * Copies the summaries of the public books into the public folder, so readers without an
     * account see them too. Only written when they changed; failures never stop the sync.
     */
    private suspend fun sharePublicInsights(drive: GoogleDriveRestClient, publicIds: Set<String>) {
        if (publicIds.isEmpty()) return
        try {
            val books = insightsDao.getAll()
                .filter { it.driveFileId in publicIds && !it.isPlaceholder }
                .map { it.toShared() }
                .sortedBy { it.driveFileId }
            if (books.isEmpty()) return
            val existing = drive.listByName(PublicCatalog.folderId, InsightsSyncState.FILE_NAME).firstOrNull()
            if (existing != null && readIndex(drive, existing).books.sortedBy { it.driveFileId } == books) return
            drive.uploadInsights(
                PublicCatalog.folderId,
                InsightsSyncState.FILE_NAME,
                json.encodeToString(InsightIndex.serializer(), InsightIndex(books = books)),
                existing?.id,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Public summaries not shared")
        }
    }

    /** Summaries CREA devices left in the public folder, for the books still in it. */
    private suspend fun readPublicInsights(drive: GoogleDriveRestClient, listing: List<GoogleDriveRestClient.CatalogEntry>) {
        try {
            val live = listing.mapTo(hashSetOf()) { it.file.id }
            val file = drive.listByName(PublicCatalog.folderId, InsightsSyncState.FILE_NAME).firstOrNull()
            val fresh = file?.let { readIndex(drive, it).books }.orEmpty()
                .filter { it.driveFileId in live && !it.summary.startsWith(BookInsight.PLACEHOLDER) }
                .map { it.toEntity() }
            if (fresh.isNotEmpty()) insightsDao.upsert(fresh)
            insightsDao.delete(insightsDao.getAll().map { it.driveFileId }.filter { it !in live })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Public summaries not read")
        }
    }

    private fun BookInsight.toShared() = SharedInsight(
        driveFileId = driveFileId,
        version = version,
        title = title,
        author = author,
        summary = summary,
        categories = categoryList,
        level = level,
        confidence = confidence,
    )

    /** Reading position per Drive book on this device, whether the book is downloaded or was read online. */
    private suspend fun ownReadings(live: Set<String>): List<DeviceReading> {
        val readings = HashMap<String, DeviceReading>()
        for (book in bookRepository.allBooksSnapshot()) {
            val id = book.remoteFileId?.takeIf { it in live } ?: continue
            val percent = ReadingProgress.percent(book.progression)
            if (percent > 0 && book.progressUpdatedAt > 0) readings[id] = DeviceReading(id, percent, book.progressUpdatedAt)
        }
        for (row in driveCatalog.getAll()) {
            if (row.driveFileId in readings || row.driveFileId !in live) continue
            val percent = ReadingProgress.percent(row.progression)
            if (percent > 0 && row.progressionAt > 0) readings[row.driveFileId] = DeviceReading(row.driveFileId, percent, row.progressionAt)
        }
        return readings.values.sortedBy { it.driveFileId }
    }

    /**
     * Opt-in sharing of reading activity. A person who turned it on publishes a small file with a
     * nickname and per-book progress in the shared folder and reads everybody else's; one who turned
     * it off gets their file emptied. It never affects the rest of the sync: any failure is logged.
     */
    private suspend fun syncCommunity(
        drive: GoogleDriveRestClient,
        folderId: String,
        deviceId: String,
        accountId: String?,
        ownReadings: List<DeviceReading>,
        live: Set<String>,
        now: Long,
    ) {
        try {
            // Sharing is per installation: several devices on one Google account each have their own
            // nickname and their own file, so they see each other like any other reader.
            val ownName = "${GoogleDriveRestClient.COMMUNITY_PREFIX}$deviceId.json"
            val files = drive.listCommunityFiles(folderId)
            val ownFile = files.firstOrNull { it.name == ownName }

            // Earlier versions published one file per Google account; empty it once so it does not linger.
            val legacyName = accountId?.let { "${GoogleDriveRestClient.COMMUNITY_PREFIX}$it.json" }
            if (!communityStore.legacyCleaned) {
                files.firstOrNull { it.name == legacyName }?.let { legacy ->
                    val inactive = CommunityProfile(userId = accountId.orEmpty(), active = false, updatedAt = now)
                    drive.uploadCommunity(folderId, legacy.name, json.encodeToString(CommunityProfile.serializer(), inactive), legacy.id)
                }
                communityStore.legacyCleaned = true
            }

            if (communityStore.enabled) {
                val readings = ownReadings.filter { it.driveFileId in live }.sortedBy { it.driveFileId }
                val nickname = communityStore.nickname
                val key = nickname + "|" + readings.joinToString(",") { "${it.driveFileId}:${it.percent}" }
                if (ownFile == null || communityStore.lastPublishedKey != key) {
                    val profile = CommunityProfile(userId = deviceId, nickname = nickname, updatedAt = now, readings = readings)
                    drive.uploadCommunity(folderId, ownName, json.encodeToString(CommunityProfile.serializer(), profile), ownFile?.id)
                    communityStore.lastPublishedKey = key
                    communityStore.published = true
                }

                // Only files that changed since the last sync are downloaded again.
                val cached = communityStore.members.value.associateBy { it.fileId }
                val members = files.filter { it.name != ownName && it.name != legacyName }.mapNotNull { file ->
                    val known = cached[file.id]
                    if (known != null && known.fileModified == file.modifiedTime.orEmpty()) return@mapNotNull known
                    val profile = runCatching {
                        json.decodeFromString<CommunityProfile>(drive.downloadText(file.id))
                    }.getOrNull() ?: return@mapNotNull null
                    if (!profile.active || profile.nickname.isBlank() || profile.userId == deviceId) return@mapNotNull null
                    CommunityMember(
                        userId = profile.userId,
                        nickname = profile.nickname,
                        fileId = file.id,
                        fileModified = file.modifiedTime.orEmpty(),
                        updatedAt = profile.updatedAt,
                        readings = profile.readings,
                    )
                }
                communityStore.replaceMembers(members)
            } else if (communityStore.published && ownFile != null) {
                val profile = CommunityProfile(userId = deviceId, active = false, updatedAt = now)
                drive.uploadCommunity(folderId, ownName, json.encodeToString(CommunityProfile.serializer(), profile), ownFile.id)
                communityStore.published = false
                communityStore.lastPublishedKey = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Community sync skipped")
        }
    }

    /**
     * AI information about the catalog books (clean title, author, summary, categories, level).
     * Whoever has AI available generates what is missing, a few books per sync, and shares it in one
     * file of the Drive folder, so each book is analysed once for everybody. Entirely optional: with
     * AI unavailable this only reads what other devices already shared, and any failure is ignored.
     */
    private suspend fun syncInsights(drive: GoogleDriveRestClient, folderId: String, listing: List<GoogleDriveRestClient.CatalogEntry>) {
        val ai = ai() ?: return
        try {
            val live = listing.associateBy { it.file.id }
            val sharedFile = drive.listByName(folderId, InsightsSyncState.FILE_NAME).firstOrNull()

            // 1. What other devices already worked out.
            if (sharedFile != null && sharedFile.modifiedTime != insightsState.lastIndexModified) {
                val index = readIndex(drive, sharedFile)
                val fresh = index.books.filter { it.driveFileId in live }.map { it.toEntity() }.filter { BuildConfig.DEBUG || !it.isPlaceholder }
                if (fresh.isNotEmpty()) insightsDao.upsert(fresh)
                insightsState.lastIndexModified = sharedFile.modifiedTime
            }
            val known = insightsDao.getAll().associateBy { it.driveFileId }.filterValues { BuildConfig.DEBUG || !it.isPlaceholder }
            insightsDao.delete(known.keys.filter { it !in live })

            // 2. Generate a few missing ones, if AI can pay for them right now.
            ai.availability.refresh()
            if (!ai.availability.catalogOn()) return
            val pending = listing.filter { entry ->
                val version = versionOf(entry.file)
                known[entry.file.id]?.version != version
            }.take(InsightsSyncState.MAX_PER_SYNC)
            val created = mutableListOf<SharedInsight>()
            for (entry in pending) {
                val file = entry.file
                val excerpt = if (DriveNaming.formatOf(file.name, file.mimeType) == "EPUB") {
                    runCatching { EpubExcerpt.find(drive.rangeSource(file.id, file.size?.toLongOrNull() ?: 0)) }.getOrNull()
                } else {
                    null
                }
                when (val outcome = ai.bookInfo(file.name, excerpt)) {
                    is AiOutcome.Success -> created += outcome.value.toShared(file.id, versionOf(file))
                    is AiOutcome.Failure -> if (outcome.result !is AiResult.Failed) break
                }
            }
            if (created.isEmpty()) return

            insightsDao.upsert(created.map { it.toEntity() })
            // 3. Share them, merging with whatever others published in the meantime.
            // Sample text from the test server is kept on this device only, never shared.
            val shareable = created.filterNot { it.summary.startsWith(BookInsight.PLACEHOLDER) }
            if (shareable.isEmpty()) return
            val latest = drive.listByName(folderId, InsightsSyncState.FILE_NAME).firstOrNull()
            val merged = (latest?.let { readIndex(drive, it).books }.orEmpty().associateBy { it.driveFileId } +
                shareable.associateBy { it.driveFileId }).values.filter { it.driveFileId in live && !it.summary.startsWith(BookInsight.PLACEHOLDER) }
            val uploaded = drive.uploadInsights(
                folderId,
                InsightsSyncState.FILE_NAME,
                json.encodeToString(InsightIndex.serializer(), InsightIndex(books = merged.sortedBy { it.driveFileId })),
                latest?.id,
            )
            insightsState.lastIndexModified = uploaded.modifiedTime
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "AI catalog information skipped")
        }
    }

    private suspend fun readIndex(drive: GoogleDriveRestClient, file: GoogleDriveRestClient.DriveFile): InsightIndex =
        runCatching { json.decodeFromString<InsightIndex>(drive.downloadText(file.id)) }.getOrDefault(InsightIndex())

    private fun versionOf(file: GoogleDriveRestClient.DriveFile): String = file.md5Checksum ?: parseTime(file.modifiedTime).toString()

    private fun BookInfo.toShared(driveFileId: String, version: String) = SharedInsight(
        driveFileId = driveFileId,
        version = version,
        title = title.trim(),
        author = author?.trim()?.takeIf { it.isNotEmpty() },
        summary = summary.trim(),
        categories = categories.map { it.trim() }.filter { it.isNotEmpty() }.take(3),
        level = level,
        confidence = confidence,
    )

    private fun SharedInsight.toEntity() = BookInsight(
        driveFileId = driveFileId,
        version = version,
        title = title,
        author = author,
        summary = summary,
        categories = categories.joinToString("|"),
        level = level,
        confidence = confidence,
    )

    private suspend fun resolveFolder(drive: GoogleDriveRestClient): String {
        val saved = stateStore.folderId
        if (saved != null) drive.getFolder(saved)?.let { return it.id }
        val folder = drive.findFolder(DriveNaming.FOLDER_NAME) ?: drive.createFolder(DriveNaming.FOLDER_NAME)
        if (saved != null && saved != folder.id) {
            // Another account or a recreated folder: previous file links no longer apply.
            bookRepository.clearRemoteFileIds()
        }
        stateStore.folderId = folder.id
        return folder.id
    }

    /** Local copies of files uploaded by this app are recognised by checksum, with no download. */
    private suspend fun linkLocalCopies(listing: List<GoogleDriveRestClient.CatalogEntry>) {
        for (entry in listing) {
            val hash = entry.file.appProperties?.get("sha256") ?: continue
            val local = bookRepository.getByContentHash(hash) ?: continue
            if (local.remoteFileId == null) bookRepository.linkToDrive(hash, entry.file.id, entry.file.md5Checksum, local.updatedAt)
        }
    }

    /**
     * Books downloaded before updates were tracked have no recorded Drive version. Their own MD5 is
     * the baseline: if it differs from what Drive has now, the file changed and an update is offered.
     */
    private suspend fun recordMissingVersions(listing: List<GoogleDriveRestClient.CatalogEntry>) {
        val remoteById = listing.associateBy { it.file.id }
        for (book in bookRepository.allBooksSnapshot()) {
            val id = book.id ?: continue
            if (book.remoteMd5 != null) continue
            val remote = book.remoteFileId?.let { remoteById[it] }?.file ?: continue
            if (remote.md5Checksum == null) continue
            val md5 = book.url.toFile()?.let { runCatching { it.md5() }.getOrNull() } ?: continue
            bookRepository.setRemoteMd5(id, md5)
        }
    }

    /** Compares the Drive listing with the stored catalog and writes only what changed. */
    private suspend fun refreshCatalog(
        listing: List<GoogleDriveRestClient.CatalogEntry>,
        summary: Counters,
    ) {
        val existing = driveCatalog.getAll().associateBy { it.driveFileId }
        val upserts = mutableListOf<DriveBook>()
        for (entry in listing) {
            val file = entry.file
            val format = DriveNaming.formatOf(file.name, file.mimeType) ?: continue
            val names = DriveNaming.describe(file.name, createdByApp = file.appProperties?.get("kind") == "book")
            val previous = existing[file.id]
            val book = DriveBook(
                driveFileId = file.id,
                fileName = file.name,
                title = names.title,
                author = names.author,
                format = format,
                size = file.size?.toLongOrNull() ?: 0,
                md5 = file.md5Checksum,
                modifiedAt = parseTime(file.modifiedTime),
                category = entry.category,
                contentHash = file.appProperties?.get("sha256"),
                coverPath = previous?.coverPath,
                coverVersion = previous?.coverVersion,
                progression = previous?.progression,
                progressionAt = previous?.progressionAt ?: 0,
                readerId = OnlineBookIds.forDriveFile(file.id),
            )
            when {
                previous == null -> {
                    summary.added++
                    upserts += book
                }
                previous != book -> {
                    summary.updated++
                    upserts += book
                }
            }
        }
        val liveIds = listing.mapTo(mutableSetOf()) { it.file.id }
        val removed = existing.values.filter { it.driveFileId !in liveIds }
        summary.removed = removed.size
        if (upserts.isNotEmpty() || removed.isNotEmpty()) {
            driveCatalog.applyRefresh(upserts, removed.map { it.driveFileId })
        }
        removed.forEach { deleteCovers(it.driveFileId) }
    }

    /**
     * Gets a cover for every catalog book that lacks one, without downloading any book: the
     * thumbnail Drive generated (usually PDFs) or, for an EPUB, just the cover image read out of
     * the file with a few range requests. Books already on the device use their own cover.
     * A book with no cover at all is remembered (for its current version), so it is not retried on
     * every sync; a network failure is retried next time.
     */
    private suspend fun fetchCovers(
        drive: GoogleDriveRestClient,
        listing: List<GoogleDriveRestClient.CatalogEntry>,
    ) {
        val stored = driveCatalog.getAll().associateBy { it.driveFileId }
        val onDevice = bookRepository.allBooksSnapshot().mapNotNull { it.remoteFileId }.toSet()
        val pending = listing.mapNotNull { entry ->
            val book = stored[entry.file.id] ?: return@mapNotNull null
            if (book.driveFileId in onDevice) return@mapNotNull null
            val attempted = book.coverVersion == book.version
            val fileMissing = book.coverPath != null && !File(book.coverPath).exists()
            if (attempted && !fileMissing) null else Triple(book, entry.file.thumbnailLink, coverFile(book))
        }.take(MAX_COVERS_PER_SYNC)
        if (pending.isEmpty()) return

        coversDir.mkdirs()
        val done = AtomicInteger(0)
        coroutineScope {
            pending.chunked(COVER_PARALLELISM).forEach { chunk ->
                chunk.map { (book, link, target) ->
                    async {
                        val outcome = try {
                            if (fetchCover(drive, book, link, target)) CoverOutcome.SAVED else CoverOutcome.NONE
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: IOException) {
                            CoverOutcome.RETRY_LATER
                        } catch (error: Exception) {
                            Timber.w(error, "No cover for ${book.title}")
                            CoverOutcome.NONE
                        }
                        when (outcome) {
                            CoverOutcome.SAVED -> {
                                deleteCovers(book.driveFileId, keep = target)
                                driveCatalog.setCover(book.driveFileId, target.path, book.version)
                            }
                            CoverOutcome.NONE -> {
                                target.delete()
                                driveCatalog.setCover(book.driveFileId, null, book.version)
                            }
                            CoverOutcome.RETRY_LATER -> target.delete()
                        }
                        report(Stage.COVERS, done.incrementAndGet(), pending.size, book.title)
                    }
                }.awaitAll()
            }
        }
    }

    private enum class CoverOutcome { SAVED, NONE, RETRY_LATER }

    private suspend fun fetchCover(
        drive: GoogleDriveRestClient,
        book: DriveBook,
        thumbnailLink: String?,
        target: File,
    ): Boolean {
        if (thumbnailLink != null && drive.fetchThumbnail(thumbnailLink, target)) return true
        if (book.format != "EPUB" || book.size <= 0) return false
        val image = EpubCoverFinder.find(drive.rangeSource(book.driveFileId, book.size)) ?: return false
        return CoverImageWriter.save(image, target)
    }

    private fun coverFile(book: DriveBook): File =
        File(coversDir, "${book.driveFileId}_${book.version.take(12)}.jpg")

    private fun deleteCovers(driveFileId: String, keep: File? = null) {
        coversDir.listFiles { file -> file.name.startsWith("${driveFileId}_") && file != keep }
            ?.forEach { it.delete() }
    }

    /** Books that live in app storage; legacy links to shared-storage documents cannot be uploaded. */
    private suspend fun syncableBooks(): List<Book> =
        bookRepository.allBooksSnapshot().filter { it.url.toFile() != null }

    private fun Book.fileExtension(): String =
        url.toFile()?.extension?.ifBlank { null } ?: if (rawMediaType.contains("pdf")) "pdf" else "epub"

    private fun Book.toSyncedBook(): SyncedBook = SyncedBook(
        contentHash = contentHash,
        fileName = DriveNaming.bookFileName(title, author, fileExtension()),
        mediaType = rawMediaType,
        title = title ?: url.toFile()?.nameWithoutExtension.orEmpty(),
        author = author,
        identifier = identifier,
        fileSize = fileSize,
        createdAt = creation ?: updatedAt,
        updatedAt = updatedAt,
        progression = progression ?: "{}",
        progressUpdatedAt = progressUpdatedAt,
        driveFileId = remoteFileId,
    )

    /** What other devices actually need from an entry; per-device fields (dates, file name) do not count as a change. */
    private fun SyncedBook.syncKey() = listOf(contentHash, driveFileId, progression, progressUpdatedAt).joinToString("|")

    private fun parseTime(value: String?): Long =
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    private fun report(stage: Stage, current: Int = 0, total: Int = 0, title: String? = null) {
        _progress.value = Progress(stage, current, total, title)
    }

    private fun describe(error: Throwable): String = when {
        error is GoogleDriveRestClient.DriveApiException && error.statusCode == 401 ->
            "Google Drive rechazó el acceso. Vuelve a conectar tu cuenta."
        error is GoogleDriveRestClient.DriveApiException && error.statusCode == 403 ->
            "Falta el permiso para usar tu carpeta de Drive. Vuelve a conectar tu cuenta."
        error is GoogleDriveRestClient.DriveApiException && error.statusCode == 429 ->
            "Google Drive recibió demasiadas solicitudes. Se reintentará en unos minutos."
        error is IOException -> "No hay conexión con Google Drive. Se reintentará cuando haya red."
        else -> error.message ?: error::class.java.simpleName
    }

    private class Counters {
        var added = 0
        var updated = 0
        var removed = 0
        var uploaded = 0
        var alreadyInDrive = 0
        fun toSummary() = SyncStateStore.Summary(
            added = added, updated = updated, uploaded = uploaded, removed = removed, alreadyInDrive = alreadyInDrive,
        )
    }

    data class Result(val bookCount: Int, val completedAt: Long)

    companion object {
        const val MANIFEST_NAME = "crea-reader-library-v2.json"
        private const val MAX_COVERS_PER_SYNC = 120
        private const val COVER_PARALLELISM = 4
        private const val DEVICE_TTL_MS = 180L * 24 * 60 * 60 * 1000
    }
}
