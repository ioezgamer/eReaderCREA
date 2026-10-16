package org.crea.reader.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.crea.reader.MainActivity
import org.crea.reader.R

/** Extras the widgets put on the intent that opens the app. */
object WidgetIntents {
    const val EXTRA_OPEN_BOOK = "widget_open_book"
    const val EXTRA_OPEN_STATS = "widget_open_stats"

    fun openApp(context: Context, requestCode: Int, configure: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            configure()
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Redraws every widget of the app, e.g. after reading progress changed. */
object WidgetUpdater {
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        listOf(ContinueWidgetProvider::class.java, StatsWidgetProvider::class.java).forEach { provider ->
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(context, provider).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                )
            }
        }
    }
}

/** Draws on the size the person gave the widget: a different layout for small, wide and tall. */
abstract class AdaptiveWidgetProvider : AppWidgetProvider() {

    protected abstract suspend fun render(context: Context, widthDp: Int, heightDp: Int): RemoteViews

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = draw(context, manager, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        draw(context, manager, intArrayOf(id))

    private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ids.forEach { id ->
                    val options = manager.getAppWidgetOptions(id)
                    val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                    val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                    manager.updateAppWidget(id, render(context, width, height))
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** "Continuar leyendo": 2x2 cover, 4x2 detail, or a list of up to three books when it is tall. */
class ContinueWidgetProvider : AdaptiveWidgetProvider() {

    override suspend fun render(context: Context, widthDp: Int, heightDp: Int): RemoteViews {
        val density = context.resources.displayMetrics.density
        val tall = heightDp >= TALL_DP && widthDp >= 180
        val wide = !tall && widthDp >= WIDE_DP
        val books = WidgetData.reading(context, if (tall) 3 else 1, fillWithUnread = tall)

        val layout = when {
            tall -> R.layout.widget_continue_large
            wide -> R.layout.widget_continue_wide
            else -> R.layout.widget_continue_small
        }
        val views = RemoteViews(context.packageName, layout)
        views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.openApp(context, 0))

        if (books.isEmpty()) {
            showEmpty(views, tall, wide)
            return views
        }
        views.setViewVisibility(R.id.widget_empty, View.GONE)

        when {
            tall -> books.forEachIndexed { index, book -> fillRow(context, views, index, book, density) }
            wide -> fillWide(context, views, books.first(), density)
            else -> fillSmall(context, views, books.first(), widthDp, heightDp, density)
        }
        return views
    }

    private fun showEmpty(views: RemoteViews, tall: Boolean, wide: Boolean) {
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        when {
            tall -> {
                views.setViewVisibility(R.id.widget_header, View.GONE)
                ROWS.forEach { views.setViewVisibility(it.row, View.GONE) }
            }
            wide -> views.setViewVisibility(R.id.widget_content, View.GONE)
            else -> {
                views.setViewVisibility(R.id.widget_cover, View.GONE)
                views.setViewVisibility(R.id.widget_caption, View.GONE)
            }
        }
    }

    private fun fillSmall(context: Context, views: RemoteViews, book: WidgetData.ReadingBook, w: Int, h: Int, density: Float) {
        views.setTextViewText(R.id.widget_title, book.title)
        views.setProgressBar(R.id.widget_progress, 100, book.percent, false)
        val width = (w * density).toInt().coerceIn(120, 520)
        val height = (h * density).toInt().coerceIn(120, 520)
        WidgetData.cover(book.cover, width, height, 22 * density)?.let { views.setImageViewBitmap(R.id.widget_cover, it) }
        views.setOnClickPendingIntent(R.id.widget_root, openBook(context, book))
    }

    private fun fillWide(context: Context, views: RemoteViews, book: WidgetData.ReadingBook, density: Float) {
        views.setTextViewText(R.id.widget_title, book.title)
        views.setTextViewText(R.id.widget_author, book.author.orEmpty())
        views.setProgressBar(R.id.widget_progress, 100, book.percent, false)
        views.setTextViewText(R.id.widget_percent, context.getString(R.string.book_progress_label, book.percent))
        WidgetData.cover(book.cover, (64 * density).toInt(), (96 * density).toInt(), 10 * density)
            ?.let { views.setImageViewBitmap(R.id.widget_cover, it) }
        views.setOnClickPendingIntent(R.id.widget_root, openBook(context, book))
    }

    private fun fillRow(context: Context, views: RemoteViews, index: Int, book: WidgetData.ReadingBook, density: Float) {
        val ids = ROWS[index]
        views.setViewVisibility(ids.row, View.VISIBLE)
        views.setTextViewText(ids.title, book.title)
        views.setTextViewText(ids.author, book.author.orEmpty())
        views.setProgressBar(ids.progress, 100, book.percent, false)
        views.setTextViewText(ids.percent, "${book.percent}%")
        WidgetData.cover(book.cover, (44 * density).toInt(), (66 * density).toInt(), 8 * density)
            ?.let { views.setImageViewBitmap(ids.cover, it) }
        views.setOnClickPendingIntent(ids.row, openBook(context, book))
    }

    private fun openBook(context: Context, book: WidgetData.ReadingBook): PendingIntent =
        WidgetIntents.openApp(context, book.id.toInt()) { putExtra(WidgetIntents.EXTRA_OPEN_BOOK, book.id) }

    private class RowIds(val row: Int, val cover: Int, val title: Int, val author: Int, val progress: Int, val percent: Int)

    private companion object {
        const val WIDE_DP = 220
        const val TALL_DP = 230
        val ROWS = listOf(
            RowIds(R.id.widget_row1, R.id.widget_cover1, R.id.widget_title1, R.id.widget_author1, R.id.widget_progress1, R.id.widget_percent1),
            RowIds(R.id.widget_row2, R.id.widget_cover2, R.id.widget_title2, R.id.widget_author2, R.id.widget_progress2, R.id.widget_percent2),
            RowIds(R.id.widget_row3, R.id.widget_cover3, R.id.widget_title3, R.id.widget_author3, R.id.widget_progress3, R.id.widget_percent3),
        )
    }
}

/** "Estadísticas de lectura": today's minutes and streak when small, four numbers when wide. */
class StatsWidgetProvider : AdaptiveWidgetProvider() {

    override suspend fun render(context: Context, widthDp: Int, heightDp: Int): RemoteViews {
        val stats = WidgetData.stats(context)
        val wide = widthDp >= WIDE_DP
        val views = RemoteViews(
            context.packageName,
            if (wide) R.layout.widget_stats_wide else R.layout.widget_stats_small,
        )
        views.setTextViewText(R.id.widget_minutes, stats.minutes.toString())
        if (wide) {
            views.setTextViewText(R.id.widget_pages, stats.pages.toString())
            views.setTextViewText(R.id.widget_streak, stats.streak.toString())
            views.setTextViewText(R.id.widget_finished, stats.finished.toString())
        } else {
            views.setTextViewText(
                R.id.widget_streak,
                context.getString(R.string.widget_stats_streak) + " · " +
                    context.resources.getQuantityString(R.plurals.widget_stats_streak_days, stats.streak, stats.streak),
            )
        }
        views.setOnClickPendingIntent(
            R.id.widget_root,
            WidgetIntents.openApp(context, 1) { putExtra(WidgetIntents.EXTRA_OPEN_STATS, true) },
        )
        return views
    }

    private companion object {
        const val WIDE_DP = 220
    }
}
