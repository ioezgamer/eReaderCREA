package org.crea.reader.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

class DriveAuthorizationProvider(private val context: Context) {
    sealed class TokenResult {
        data class Authorized(val accessToken: String) : TokenResult()
        data class ConsentRequired(val pendingIntent: PendingIntent) : TokenResult()
    }

    private val client get() = Identity.getAuthorizationClient(context)

    suspend fun requestToken(): TokenResult {
        val result = client.authorize(request).await()
        return if (result.hasResolution()) {
            TokenResult.ConsentRequired(requireNotNull(result.pendingIntent))
        } else {
            TokenResult.Authorized(requireNotNull(result.accessToken))
        }
    }

    fun tokenFromIntent(data: Intent): String =
        requireNotNull(client.getAuthorizationResultFromIntent(data).accessToken)

    companion object {
        // `drive` lets the app see files the user drops into the "CREA Reader" folder by hand;
        // `drive.appdata` keeps reading progress in the app's private space.
        private const val DRIVE = "https://www.googleapis.com/auth/drive"
        private const val DRIVE_APP_DATA = "https://www.googleapis.com/auth/drive.appdata"

        private val request: AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE), Scope(DRIVE_APP_DATA)))
            .build()
    }
}
