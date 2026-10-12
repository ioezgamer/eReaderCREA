package org.crea.reader.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.fetchWithDecoder
import org.crea.reader.utils.tryOrLog

class CoverStorage(
    private val appStorageDir: File,
    private val httpClient: HttpClient,
) {

    suspend fun storeCover(publication: Publication, overrideUrl: AbsoluteUrl?): Try<File, Exception> {
        val coverBitmap: Bitmap? = overrideUrl?.fetchBitmap()
            ?: publication.cover()
        return try {
            Try.success(storeCover(coverBitmap))
        } catch (e: Exception) {
            Try.failure(e)
        }
    }

    private suspend fun AbsoluteUrl.fetchBitmap(): Bitmap? =
        tryOrLog {
            when {
                isFile -> toFile()?.toBitmap()
                isHttp -> httpClient.fetchBitmap(HttpRequest(this)).getOrNull()
                else -> null
            }
        }

    private suspend fun File.toBitmap(): Bitmap? =
        withContext(Dispatchers.IO) {
            tryOrLog {
                BitmapFactory.decodeFile(path)
            }
        }

    private suspend fun HttpClient.fetchBitmap(request: HttpRequest): Try<Bitmap, HttpError> =
        fetchWithDecoder(request) { response ->
            BitmapFactory.decodeByteArray(response.body, 0, response.body.size)
        }

    private suspend fun storeCover(cover: Bitmap?): File =
        withContext(Dispatchers.IO) {
            val coverImageFile = File(coverDir(), "${Uuid.random()}.jpg")
            // Sharp enough for the tablet grid (it used to be 120x200) and keeps its proportions.
            val resized = cover?.let { scaleToFit(it, COVER_MAX_WIDTH, COVER_MAX_HEIGHT) }
            val fos = FileOutputStream(coverImageFile)
            resized?.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, fos)
            fos.flush()
            fos.close()
            coverImageFile
        }

    private fun scaleToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height, 1f)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private companion object {
        const val COVER_MAX_WIDTH = 300
        const val COVER_MAX_HEIGHT = 450
        const val COVER_QUALITY = 85
    }

    private fun coverDir(): File =
        File(appStorageDir, "covers/")
            .apply { if (!exists()) mkdirs() }
}

