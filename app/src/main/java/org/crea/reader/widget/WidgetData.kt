package org.crea.reader.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.crea.reader.Application
import org.crea.reader.bookshelf.LibraryRules
import org.crea.reader.utils.ReadingProgress

/** What the home-screen widgets show, read from the same library and stats the app uses. */
object WidgetData {

    data class ReadingBook(
        val id: Long,
        val title: String,
        val author: String?,
        val percent: Int,
        val cover: String,
    )

    data class Stats(val minutes: Int, val pages: Int, val streak: Int, val finished: Int)

    /** Books being read, the most recently read first. */
    suspend fun reading(context: Context, limit: Int, fillWithUnread: Boolean = false): List<ReadingBook> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as Application
            val books = app.bookRepository.allBooksSnapshot()
            val inProgress = books
                .filter { ReadingProgress.percent(it.progression) in 1 until LibraryRules.FINISHED_AT }
                .sortedByDescending { it.progressUpdatedAt }
            // A tall widget with few books in progress is completed with the newest unread ones.
            val unread = if (fillWithUnread) {
                books.filter { ReadingProgress.percent(it.progression) == 0 }
                    .sortedByDescending { it.creation ?: 0L }
            } else {
                emptyList()
            }
            (inProgress + unread).take(limit).mapNotNull { book ->
                book.id?.let {
                    ReadingBook(it, book.title.orEmpty(), book.author, ReadingProgress.percent(book.progression), book.cover)
                }
            }
        }

    suspend fun stats(context: Context): Stats = withContext(Dispatchers.IO) {
        val app = context.applicationContext as Application
        val today = app.readingStats.last(1).first()
        val finished = app.bookRepository.allBooksSnapshot()
            .count { ReadingProgress.percent(it.progression) >= LibraryRules.FINISHED_AT }
        Stats(
            minutes = (today.seconds / 60).toInt(),
            pages = today.pages.toInt(),
            streak = app.readingStats.streak(),
            finished = finished,
        )
    }

    /**
     * The cover cropped to [widthPx] x [heightPx] with rounded corners. Widgets cannot clip their
     * images, so the corners are drawn into the bitmap itself.
     */
    fun cover(path: String, widthPx: Int, heightPx: Int, radiusPx: Float): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx && bounds.outHeight / (sample * 2) >= heightPx) sample *= 2
        val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val scale = maxOf(widthPx / source.width.toFloat(), heightPx / source.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((widthPx - source.width * scale) / 2f, (heightPx - source.height * scale) / 2f)
        }
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        val result = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        Canvas(result).drawRoundRect(
            RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()),
            radiusPx,
            radiusPx,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader },
        )
        if (source !== result) source.recycle()
        result
    }.getOrNull()
}
