package org.crea.reader.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retryOrFail()
            }
        }

        val authorization = DriveAuthorizationProvider(applicationContext)
        var token: String? = null
        return try {
            when (val result = authorization.requestToken()) {
                is DriveAuthorizationProvider.TokenResult.Authorized -> {
                    token = result.accessToken
                    app.syncEngine.sync(result.accessToken, PublicCatalog.client(applicationContext))
                    Result.success()
                }
                is DriveAuthorizationProvider.TokenResult.ConsentRequired -> {
                    app.syncStateStore.markError(
                        "Abre CREA Reader para renovar el permiso de Google Drive."
                    )
                    Result.success()
                }
            }
        } catch (cancelled: CancellationException) {
            // WorkManager stopped the work (constraints, app update...): let it reschedule it.
            throw cancelled
        } catch (error: GoogleDriveRestClient.DriveApiException) {
            if (error.statusCode == 401 && token != null) {
                // An expired or revoked token: forget it so the retry asks Google for a fresh one.
                authorization.clearToken(token)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            } else if (error.isTemporary && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (_: Exception) {
            retryOrFail()
        }
    }

    /** Network trouble, another device writing the manifest, and similar: try again a few times. */
    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
