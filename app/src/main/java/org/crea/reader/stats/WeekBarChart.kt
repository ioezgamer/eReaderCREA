package org.crea.reader.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Minutes read per day as bars, oldest day first; the last bar is today. */
class WeekBarChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var days: List<DayStat> = emptyList()
    private var labels: List<String> = emptyList()
    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val rect = RectF()

    fun setData(days: List<DayStat>, today: LocalDate = LocalDate.now()) {
        this.days = days
        labels = days.indices.map {
            today.minusDays((days.size - 1 - it).toLong())
                .dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
        }
        contentDescription = days.indices.joinToString(", ") {
            "${labels[it]} ${formatReadingTime(days[it].seconds)}"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val track = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
        val label = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        text.color = label

        val labelBand = 24 * density
        val chartHeight = height - labelBand
        val slot = width.toFloat() / days.size
        val barWidth = slot * 0.56f
        val radius = 8 * density
        val max = days.maxOf { it.seconds }.coerceAtLeast(60L)

        days.forEachIndexed { index, day ->
            val left = slot * index + (slot - barWidth) / 2
            rect.set(left, 0f, left + barWidth, chartHeight)
            bar.color = track
            canvas.drawRoundRect(rect, radius, radius, bar)
            if (day.seconds > 0) {
                val filled = (chartHeight * day.seconds / max).coerceAtLeast(radius * 1.5f)
                rect.set(left, chartHeight - filled, left + barWidth, chartHeight)
                bar.color = primary
                canvas.drawRoundRect(rect, radius, radius, bar)
            }
            canvas.drawText(labels[index], slot * index + slot / 2, height - 4 * density, text)
        }
    }
}
