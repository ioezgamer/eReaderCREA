package org.crea.reader.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.databinding.FragmentStatsBinding
import org.crea.reader.databinding.ItemStatTileBinding
import org.crea.reader.utils.ReadingProgress

/** Reading time, pages, streak and finished books. Everything is computed from this device. */
class StatsFragment : Fragment() {
    private var _binding: FragmentStatsBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    private fun render() {
        val app = requireContext().applicationContext as Application
        val stats = app.readingStats
        val week = stats.last(7)
        val today = week.last()
        val total = stats.totals()
        val streak = stats.streak()

        tile(binding.statsToday, R.string.stats_today, formatReadingTime(today.seconds), pages(today.pages))
        tile(
            binding.statsStreak,
            R.string.stats_streak,
            resources.getQuantityString(R.plurals.stats_days, streak, streak),
            getString(R.string.stats_streak_detail),
        )
        binding.statsWeekSummary.text = getString(
            R.string.stats_week_summary,
            formatReadingTime(week.sumOf { it.seconds }),
            pages(week.sumOf { it.pages }),
        )
        binding.statsChart.setData(week)
        tile(binding.statsTotal, R.string.stats_total, formatReadingTime(total.seconds), pages(total.pages))

        viewLifecycleOwner.lifecycleScope.launch {
            val books = app.bookRepository.books().first()
            val finished = books.count { ReadingProgress.percent(it.progression) >= FINISHED_PERCENT }
            _binding?.let {
                tile(
                    it.statsFinished,
                    R.string.stats_finished,
                    finished.toString(),
                    getString(R.string.stats_finished_detail, books.size),
                )
            }
        }
    }

    private fun pages(count: Long) = resources.getQuantityString(R.plurals.stats_pages, count.toInt(), count.toInt())

    private fun tile(tile: ItemStatTileBinding, label: Int, value: String, detail: String) {
        tile.statLabel.setText(label)
        tile.statValue.text = value
        tile.statDetail.text = detail
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val FINISHED_PERCENT = 99
    }
}
