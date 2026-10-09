package org.crea.reader.online

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * Stand-in cover for a book that has none: its title on a colour taken from the app palette, so
 * every card shows something recognisable and the same book always gets the same colour.
 */
class GeneratedCover(private val title: String, private val author: String?) : Drawable() {

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PALETTE[(title.hashCode() and Int.MAX_VALUE) % PALETTE.size]
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        isFakeBoldText = true
    }
    private val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val stripe = Paint().apply { color = 0x33000000 }

    override fun draw(canvas: Canvas) {
        val bounds: Rect = bounds
        if (bounds.isEmpty) return
        canvas.drawRect(bounds, background)
        canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.left + bounds.width() * 0.07f, bounds.bottom.toFloat(), stripe)

        val width = bounds.width()
        val padding = (width * 0.14f).toInt()
        val textWidth = (width - padding * 2).coerceAtLeast(1)
        titlePaint.textSize = width * 0.13f
        authorPaint.textSize = width * 0.09f

        val titleLayout = layout(title, titlePaint, textWidth, maxLines = 5)
        val authorLayout = author?.takeIf { it.isNotBlank() }?.let { layout(it, authorPaint, textWidth, maxLines = 2) }
        val total = titleLayout.height + (authorLayout?.let { it.height + padding / 2 } ?: 0)
        var top = bounds.top + ((bounds.height() - total) / 2f).coerceAtLeast(padding / 2f)

        canvas.save()
        canvas.translate(bounds.left + padding.toFloat(), top)
        titleLayout.draw(canvas)
        canvas.restore()
        top += titleLayout.height + padding / 2f
        if (authorLayout != null) {
            canvas.save()
            canvas.translate(bounds.left + padding.toFloat(), top)
            authorLayout.draw(canvas)
            canvas.restore()
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    override fun setAlpha(alpha: Int) {
        background.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        background.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        val PALETTE = intArrayOf(0xFF1558D6.toInt(), 0xFFD6246E.toInt(), 0xFFB85F00.toInt(), 0xFF2E9E22.toInt(), 0xFF0E1B3D.toInt(), 0xFF6A3FC1.toInt())
    }
}
