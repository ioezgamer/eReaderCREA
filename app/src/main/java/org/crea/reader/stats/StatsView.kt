package org.crea.reader.stats

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.databinding.FragmentStatsBinding
import org.crea.reader.databinding.ItemStatTileBinding
import org.crea.reader.utils.ReadingProgress

/** Fills the statistics layout; shared by the Settings screen and the reader's sheet. */
internal object StatsView {
    private const val FINISHED_PERCENT = 99

    fun render(binding: FragmentStatsBinding, context: Context, scope: LifecycleCoroutineScope) {
        val app = context.applicationContext as Application
        val stats = app.readingStats
        val week = stats.last(7)
        val today = week.last()
        val total = stats.totals()
        val streak = stats.streak()

        fun pages(count: Long) = context.resources.getQuantityString(R.plurals.stats_pages, count.toInt(), count.toInt())

        tile(binding.statsToday, R.string.stats_today, formatReadingTime(today.seconds), pages(today.pages))
        tile(
            binding.statsStreak,
            R.string.stats_streak,
            context.resources.getQuantityString(R.plurals.stats_days, streak, streak),
            context.getString(R.string.stats_streak_detail),
        )
        binding.statsWeekSummary.text = context.getString(
            R.string.stats_week_summary,
            formatReadingTime(week.sumOf { it.seconds }),
            pages(week.sumOf { it.pages }),
        )
        binding.statsChart.setData(week)
        tile(binding.statsTotal, R.string.stats_total, formatReadingTime(total.seconds), pages(total.pages))

        scope.launch {
            val books = app.bookRepository.books().first()
            val finished = books.count { ReadingProgress.percent(it.progression) >= FINISHED_PERCENT }
            tile(
                binding.statsFinished,
                R.string.stats_finished,
                finished.toString(),
                context.getString(R.string.stats_finished_detail, books.size),
            )
        }
    }

    private fun tile(tile: ItemStatTileBinding, label: Int, value: String, detail: String) {
        tile.statLabel.setText(label)
        tile.statValue.text = value
        tile.statDetail.text = detail
    }
}
