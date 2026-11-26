package org.crea.reader.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

/**
 * A still picture of a view (the book page), at [SCALE] of its size and in window coordinates.
 *
 * The reader cannot feed its WebView to the glass on every frame (copying it while it draws makes half
 * of the page vanish in alternate frames). A picture taken once per page or appearance change gives the
 * glass the real page to blur and refract, and the page itself is never touched while it draws.
 */
object PageSnapshot {
    /** Half size is plenty: the glass blurs it anyway, and it keeps the copy small and fast. */
    const val SCALE = 0.5f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Draws [source] (and nothing on top of it, like the reader's bars) into a new bitmap. */
    fun take(source: View): Bitmap? {
        val root = source.rootView
        if (root.width == 0 || root.height == 0 || source.width == 0) return null
        val bitmap = Bitmap.createBitmap(
            (root.width * SCALE).toInt().coerceAtLeast(1),
            (root.height * SCALE).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val where = IntArray(2)
        source.getLocationInWindow(where)
        Canvas(bitmap).apply {
            scale(SCALE, SCALE)
            translate(where[0].toFloat(), where[1].toFloat())
            source.draw(this)
        }
        return bitmap
    }

    /** Whether the picture is mostly dark (dark paper): average luminance over a sparse grid. */
    fun isDark(bitmap: Bitmap): Boolean {
        val steps = 16
        var sum = 0.0
        var count = 0
        for (i in 0 until steps) for (j in 0 until steps) {
            val c = bitmap.getPixel(
                ((i + 0.5f) * bitmap.width / steps).toInt().coerceIn(0, bitmap.width - 1),
                ((j + 0.5f) * bitmap.height / steps).toInt().coerceIn(0, bitmap.height - 1),
            )
            if (android.graphics.Color.alpha(c) < 128) continue
            sum += 0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) +
                0.0722 * android.graphics.Color.blue(c)
            count++
        }
        return count > 0 && sum / count < 110
    }

    internal fun draw(canvas: android.graphics.Canvas, bitmap: Bitmap, origin: Offset) {
        canvas.save()
        canvas.translate(-origin.x, -origin.y)
        canvas.scale(1f / SCALE, 1f / SCALE)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        canvas.restore()
    }
}

/**
 * A backdrop that shows [snapshot] (see [PageSnapshot]) lined up with what is really behind the glass.
 * Use one per glass surface: each one tracks the position of its own surface.
 */
@Composable
fun rememberSnapshotBackdrop(snapshot: State<Bitmap?>): ViewBackdrop {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val backdrop = rememberCanvasBackdrop {
        val bitmap = snapshot.value ?: return@rememberCanvasBackdrop
        val own = origin
        drawIntoCanvas { PageSnapshot.draw(it.nativeCanvas, bitmap, own) }
    }
    val track = remember { Modifier.onGloballyPositioned { origin = it.positionInWindow() } }
    return remember(backdrop, track) { ViewBackdrop(backdrop, track) }
}
