package org.crea.reader.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import org.crea.reader.Application

/**
 * Google Drive permission for the connected account. A CREA account gets the whole Drive (the shared
 * "CREA Reader" folder); a personal account only gets the files this app creates, in its own
 * "CREA Reader" folder, plus the app's private space. [personal] null means the connected account.
 */
class DriveAuthorizationProvider(private val context: Context, personal: Boolean? = null) {
    sealed class TokenResult {
        data class Authorized(val accessToken: String) : TokenResult()
        data class ConsentRequired(val pendingIntent: PendingIntent) : TokenResult()
    }

    private val personal = personal
        ?: (context.applicationContext as Application).syncStateStore.isPersonal

    private val client get() = Identity.getAuthorizationClient(context)

    suspend fun requestToken(): TokenResult {
        val result = client.authorize(if (personal) personalRequest else creaRequest).await()
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
        // Personal accounts: only what the app itself creates (no Google security review needed).
        private const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

        /** The Workspace domain whose accounts get the full shared library, AI and natural voices. */
        const val CREA_DOMAIN = "creanicaragua.org"

        fun isCreaAccount(email: String?): Boolean =
            email?.substringAfterLast('@', "")?.equals(CREA_DOMAIN, ignoreCase = true) == true

        private val creaRequest: AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE), Scope(DRIVE_APP_DATA)))
            .build()

        private val personalRequest: AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE), Scope(DRIVE_APP_DATA)))
            .build()
    }
}
