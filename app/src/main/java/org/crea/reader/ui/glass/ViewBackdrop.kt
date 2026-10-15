package org.crea.reader.ui.glass

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

/**
 * A [Backdrop] made from regular Android Views.
 *
 * The app's screens are Views, so there is no Compose layer to sample. Instead the glass redraws the
 * Views that sit behind it (and the window background) every time they change, shifted so that each
 * pixel lines up with what is really behind the glass.
 */
class ViewBackdrop internal constructor(
    val backdrop: Backdrop,
    /** Apply to the glass surface so the backdrop knows where it is on screen. */
    val trackPosition: Modifier,
)

@Composable
fun rememberViewBackdrop(sources: List<View>, windowBackground: Drawable? = null): ViewBackdrop {
    var tick by remember { mutableIntStateOf(0) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // Redraw the glass whenever one of the source views is drawn again (scrolling, animations).
    DisposableEffect(sources) {
        val listeners = sources.map { view ->
            val listener = ViewTreeObserver.OnPreDrawListener {
                tick++
                true
            }
            view.viewTreeObserver.addOnPreDrawListener(listener)
            view to listener
        }
        onDispose {
            listeners.forEach { (view, listener) ->
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
    }

    // A private copy: changing the bounds of the real window background would move it on screen.
    val background = remember(windowBackground) { windowBackground?.constantState?.newDrawable()?.mutate() }

    val backdrop = rememberCanvasBackdrop {
        @Suppress("UNUSED_EXPRESSION") tick // read so this is drawn again when a source changes
        val own = origin
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val window = sources.firstOrNull()?.rootView
            if (background != null && window != null) {
                background.setBounds(0, 0, window.width, window.height)
                native.save()
                native.translate(-own.x, -own.y)
                background.draw(native)
                native.restore()
            }
            val where = IntArray(2)
            sources.forEach { view ->
                view.getLocationInWindow(where)
                native.save()
                native.translate(where[0] - own.x, where[1] - own.y)
                view.draw(native)
                native.restore()
            }
        }
    }

    val track = remember { Modifier.onGloballyPositioned { origin = it.positionInWindow() } }
    return remember(backdrop, track) { ViewBackdrop(backdrop, track) }
}
