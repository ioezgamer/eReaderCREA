package org.crea.reader.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import org.crea.reader.BuildConfig

/**
 * The public catalog: a Drive folder of CREA shared as "anyone with the link can view", read with
 * an API key and no sign-in. People without a CREA account read it; CREA accounts see it together
 * with the private folder. Reading profiles, progress and other data never go in it.
 */
object PublicCatalog {

    /** What the Drive API needs to accept the API key: it is restricted to this app. */
    class Access(val apiKey: String, val packageName: String, val certificateSha1: String)

    val folderId: String get() = BuildConfig.PUBLIC_FOLDER_ID

    val isConfigured: Boolean get() = BuildConfig.PUBLIC_FOLDER_ID.isNotBlank() && BuildConfig.PUBLIC_DRIVE_KEY.isNotBlank()

    /** A Drive client for the public folder, or null when this build has no public catalog. */
    fun client(context: Context): GoogleDriveRestClient? {
        if (!isConfigured) return null
        val sha1 = signingSha1(context) ?: return null
        return GoogleDriveRestClient(null, Access(BuildConfig.PUBLIC_DRIVE_KEY, context.packageName, sha1))
    }

    /** SHA-1 of the certificate this install is signed with (Play's key for Play installs), as hex. */
    private fun signingSha1(context: Context): String? = runCatching {
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = requireNotNull(info.signingInfo)
            (if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory).first()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!.first()
        }
        MessageDigest.getInstance("SHA-1").digest(certificate.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrNull()
}
