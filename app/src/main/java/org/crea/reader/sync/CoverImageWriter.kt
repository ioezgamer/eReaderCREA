package org.crea.reader.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stores a cover as a small JPEG so a large catalog does not fill the device with full-size images. */
object CoverImageWriter {
    private const val TARGET_WIDTH = 400

    /** @return false when [bytes] are not a decodable image. */
    suspend fun save(bytes: ByteArray, target: File): Boolean = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@withContext false
        try {
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
