package org.crea.reader.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.crea.reader.Application

class LibrarySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as Application
        if (!app.syncStateStore.isEnabled) {
            // No CREA account: keep the public catalog, if this build has one, up to date.
            val publicDrive = PublicCatalog.client(applicationContext) ?: return Result.success()
            return try {
                app.syncEngine.syncPublic(publicDrive)
                Result.success()
            } catch (_: Throwable) {
                if (runAttemptCount < 5) Result.retry() else Result.failure()
            }
        }

        return try {
            when (val authorization = DriveAuthorizationProvider(applicationContext).requestToken()) {
                is DriveAuthorizationProvider.TokenResult.Authorized -> {
                    app.syncEngine.sync(authorization.accessToken, PublicCatalog.client(applicationContext))
                    Result.success()
                }
                is DriveAuthorizationProvider.TokenResult.ConsentRequired -> {
                    app.syncStateStore.markError(
                        "Abre CREA Reader para renovar el permiso de Google Drive."
                    )
                    Result.success()
                }
            }
        } catch (error: GoogleDriveRestClient.DriveApiException) {
            if ((error.statusCode in 500..599 || error.statusCode == 429) && runAttemptCount < 5) Result.retry()
            else Result.failure()
        } catch (_: Throwable) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
