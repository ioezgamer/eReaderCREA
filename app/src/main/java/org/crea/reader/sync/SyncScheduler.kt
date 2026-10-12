package org.crea.reader.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val PERIODIC_NAME = "crea-library-periodic-sync"
    private const val IMMEDIATE_NAME = "crea-library-immediate-sync"
    private const val SOON_NAME = "crea-library-soon-sync"
    private const val SOON_DELAY_SECONDS = 30L
    private const val STALE_AFTER_MS = 15 * 60 * 1000L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Keeps the library fresh when the app is opened, without hammering Drive on every resume. */
    fun runIfStale(context: Context, state: SyncStateStore.Snapshot, maxAgeMillis: Long = STALE_AFTER_MS) {
        if (!state.enabled) return
        if (System.currentTimeMillis() - state.lastSync < maxAgeMillis) return
        runNow(context)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    /**
     * For follow-ups that can wait (for example after a download): at most one sync is queued, a
     * little later, however many books were downloaded in the meantime.
     */
    fun runSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(SOON_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
