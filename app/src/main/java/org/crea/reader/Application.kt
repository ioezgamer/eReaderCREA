/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader

import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import org.crea.reader.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.crea.reader.bookshelf.CoverImages
import org.crea.reader.data.CatalogRepository
import org.crea.reader.data.model.Book
import org.crea.reader.data.model.Catalog
import org.crea.reader.BuildConfig.DEBUG
import org.crea.reader.data.BookRepository
import org.crea.reader.data.db.AppDatabase
import org.crea.reader.data.db.DriveCatalogDao
import org.crea.reader.data.model.DriveBook
import org.crea.reader.online.OnlineBookManager
import org.crea.reader.online.OnlineReadingCache
import org.crea.reader.online.TransferService
import org.crea.reader.domain.Bookshelf
import org.crea.reader.domain.CoverStorage
import org.crea.reader.domain.PublicationRetriever
import org.crea.reader.reader.ReaderRepository
import org.crea.reader.ai.AiAvailability
import org.crea.reader.ai.AiClient
import org.crea.reader.ai.AiService
import org.crea.reader.data.db.InsightsDao
import org.crea.reader.data.model.BookInsight
import org.crea.reader.reader.ReaderLook
import org.crea.reader.stats.PrefsStatsStore
import org.crea.reader.stats.ReadingStats
import org.crea.reader.sync.CommunityStore
import org.crea.reader.sync.InsightsSyncState
import kotlinx.coroutines.flow.map
import org.crea.reader.sync.DeviceReadingsStore
import org.crea.reader.sync.LibrarySyncEngine
import org.crea.reader.sync.SyncScheduler
import org.crea.reader.sync.SyncStateStore
import org.crea.reader.utils.tryOrLog
import timber.log.Timber

class Application : android.app.Application() {

    lateinit var readium: Readium
        private set

    lateinit var storageDir: File

    lateinit var bookRepository: BookRepository
        private set

    val readingStats: ReadingStats by lazy { ReadingStats(PrefsStatsStore(this)) }

    val readerLook: ReaderLook by lazy { ReaderLook(this) }

    lateinit var bookshelf: Bookshelf
        private set

    lateinit var readerRepository: ReaderRepository
        private set

    lateinit var syncStateStore: SyncStateStore
        private set

    /** Library kept in memory for the whole process: tabs read it instantly instead of re-querying. */
    lateinit var booksState: StateFlow<List<Book>?>
        private set

    lateinit var catalogsState: StateFlow<List<Catalog>?>
        private set

    lateinit var driveCatalogDao: DriveCatalogDao
        private set

    lateinit var onlineCache: OnlineReadingCache
        private set

    /** The online catalog kept in memory for the whole process, like [booksState]. */
    lateinit var driveCatalogState: StateFlow<List<DriveBook>?>
        private set

    lateinit var onlineBooks: OnlineBookManager
        private set

    lateinit var deviceReadingsStore: DeviceReadingsStore
        private set

    lateinit var communityStore: CommunityStore
        private set

    lateinit var aiService: AiService
        private set

    lateinit var insightsDao: InsightsDao
        private set

    /** AI information about catalog books by Drive id, kept in memory like the catalog itself. */
    lateinit var insightsState: StateFlow<Map<String, BookInsight>>
        private set

    lateinit var syncEngine: LibrarySyncEngine
        private set

    private val coroutineScope: CoroutineScope =
        MainScope()

    private val Context.navigatorPreferences: DataStore<Preferences>
        by preferencesDataStore(name = "navigator-preferences")

    override fun onCreate() {
        // The crash screen lives in its own process and must not start the whole app.
        if (CrashReporter.isCrashProcess()) {
            super.onCreate()
            return
        }
        CrashReporter.install(this)

        if (DEBUG) {
            enableStrictMode()
            Timber.plant(Timber.DebugTree())
        }

        super.onCreate()

        AppLanguage.applyAtStartup(this)
        AppThemeMode.applyAtStartup(this)

        readium = Readium(this)

        storageDir = computeStorageDir()

        val database = AppDatabase.getDatabase(this)

        driveCatalogDao = database.driveCatalogDao()
        bookRepository = BookRepository(database.booksDao(), driveCatalogDao, database.collectionsDao())

        val downloadsDir = File(cacheDir, "downloads")

        // Cleans the download dir.
        tryOrLog { downloadsDir.delete() }

        val publicationRetriever =
            PublicationRetriever(
                context = applicationContext,
                assetRetriever = readium.assetRetriever,
                bookshelfDir = storageDir,
                tempDir = downloadsDir,
                httpClient = readium.httpClient,
                lcpService = readium.lcpService.getOrNull()
            )

        bookshelf =
            Bookshelf(
                bookRepository,
                CoverStorage(storageDir, httpClient = readium.httpClient),
                readium.publicationOpener,
                readium.assetRetriever,
                publicationRetriever
            )

        onlineCache = OnlineReadingCache(File(cacheDir, "online-reading"))

        readerRepository = ReaderRepository(
            this@Application,
            readium,
            bookRepository,
            driveCatalogDao,
            onlineCache,
            navigatorPreferences
        )

        booksState = bookRepository.books()
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
        catalogsState = CatalogRepository(database.catalogDao()).getCatalogsFromDatabase()
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
        coroutineScope.launch {
            val books = booksState.filterNotNull().first()
            CoverImages.warmUp(books.map { it.cover })
        }
        // Keep the home-screen widgets in step with the library and reading progress.
        coroutineScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            booksState.filterNotNull()
                .debounce(1500)
                .collect { WidgetUpdater.refreshAll(this@Application) }
        }

        driveCatalogState = driveCatalogDao.observeAll()
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)

        syncStateStore = SyncStateStore(this)
        insightsDao = database.insightsDao()
        val aiClient = AiClient(this, deviceId = { syncStateStore.deviceId })
        aiService = AiService(aiClient, AiAvailability(this, aiClient) { syncStateStore.isEnabled })
        insightsState = insightsDao.observeAll().map { list -> list.associateBy { it.driveFileId } }
            .stateIn(coroutineScope, SharingStarted.Eagerly, emptyMap())
        deviceReadingsStore = DeviceReadingsStore(this)
        communityStore = CommunityStore(this)
        syncEngine = LibrarySyncEngine(
            bookRepository,
            driveCatalogDao,
            syncStateStore,
            coversDir = File(filesDir, "catalog-covers"),
            // The nickname the person gave this device, or the name Android knows it by.
            deviceName = { communityStore.nickname.ifBlank { systemDeviceName() } },
            readingsStore = deviceReadingsStore,
            communityStore = communityStore,
            insightsDao = insightsDao,
            insightsState = InsightsSyncState(this),
            ai = { aiService }
        )
        onlineBooks = OnlineBookManager(
            context = this,
            bookRepository = bookRepository,
            bookshelf = bookshelf,
            cache = onlineCache,
            libraryDir = storageDir,
            tempDir = downloadsDir,
            isDriveConnected = { syncStateStore.isEnabled },
            openOnlineFiles = { readerRepository.openOnlineFiles() },
            // Reading progress travels through the Drive manifest, so refresh it after a download.
            onLibraryChanged = { if (syncStateStore.isEnabled) SyncScheduler.runSoon(this) },
            onTransferStarted = { TransferService.start(this) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        coroutineScope.launch(Dispatchers.IO) {
            // Temporary reading copies expire on their own; also drop transfers a killed process left behind.
            onlineCache.cleanUp()
            downloadsDir.listFiles { file -> file.name.startsWith("drive-") }?.forEach { it.delete() }
        }
        SyncScheduler.schedule(this)
        // Not on every process start (CREA Home, a shared file...): only if the library is stale.
        SyncScheduler.runIfStale(this, syncStateStore.snapshot())
    }

    /** The name the owner gave the device in Android's settings, or its model. */
    fun systemDeviceName(): String =
        runCatching { Settings.Global.getString(contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.orEmpty().ifBlank { "Android" }

    private fun computeStorageDir(): File {
        val properties = Properties()
        val inputStream = assets.open("configs/config.properties")
        properties.load(inputStream)
        val useExternalFileDir =
            properties.getProperty("useExternalFileDir", "false")!!.toBoolean()

        return File(
            if (useExternalFileDir) {
                getExternalFilesDir(null)?.path + "/"
            } else {
                filesDir?.path + "/"
            }
        )
    }

    /**
     * Strict mode will log violation of VM and threading policy.
     * Use it to make sure the app doesn't do too much work on the main thread.
     */
    private fun enableStrictMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }

        val executor = Executors.newSingleThreadExecutor()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyListener(executor) { violation ->
                    Timber.e(violation, "Thread policy violation")
                }
//                .penaltyDeath()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyListener(executor) { violation ->
                    Timber.e(violation, "VM policy violation")
                }
//                .penaltyDeath()
                .build()
        )
    }
}

