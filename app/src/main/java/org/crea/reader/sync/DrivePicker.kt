package org.crea.reader.sync

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest.ResourceParameter
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Google's Drive file picker, for personal accounts. With the limited `drive.file` permission the
 * app only sees the files a person picks, one by one (picking a folder gives no access to what is
 * in it), so this is how books already in someone's Drive reach the catalog, without copying them.
 */
object DrivePicker {
    private const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    /** The picker to show, or null if Google answered without showing it (no files picked then). */
    suspend fun open(context: Context, account: String?): PendingIntent? {
        val builder = AuthorizationRequest.builder()
            // The picker only works with drive.file on its own.
            .setRequestedScopes(listOf(Scope(DRIVE_FILE)))
            .setOptOutIncludingGrantedScopes(true)
            .setPrompt(AuthorizationRequest.Prompt.CONSENT)
            .addResourceParameter(ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
            .addResourceParameter(ResourceParameter.PICKER_ALLOW_MULTIPLE, "true")
        if (account != null) builder.setAccount(Account(account, "com.google"))
        val result = Identity.getAuthorizationClient(context).authorize(builder.build()).await()
        return result.pendingIntent.takeIf { result.hasResolution() }
    }

    /** Drive ids of the files picked, from the picker's result. */
    fun pickedIds(context: Context, data: Intent): List<String> =
        idsOf(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))

    private fun idsOf(result: AuthorizationResult): List<String> {
        val raw = result.tokenResponseParams?.get(PICKED_FILE_IDS) ?: return emptyList()
        val values = when (raw) {
            is Array<*> -> raw.map { it.toString() }
            is Collection<*> -> raw.map { it.toString() }
            else -> listOf(raw.toString())
        }
        return values.flatMap { it.split(',', ' ', '[', ']') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private const val PICKED_FILE_IDS = "picked_file_ids"
}
