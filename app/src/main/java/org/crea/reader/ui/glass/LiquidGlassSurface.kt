package org.crea.reader.ui.glass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** Tint laid over the glass: the app's light or dark base color at the given opacity. */
@Composable
fun liquidGlassSurfaceColor(alpha: Float = 0.42f): Color =
    if (isSystemInDarkTheme()) Color(0xFF0E1B3D).copy(alpha = alpha) else Color.White.copy(alpha = alpha)

/**
 * Turns a surface into Liquid Glass: it blurs and refracts what is behind it (through [backdrop]),
 * gets a soft luminous edge, and is tinted with [surface].
 *
 * With [effects] off the same tinted surface is drawn without the shaders, so one component covers
 * every device and the battery-saver case.
 */
fun Modifier.liquidGlass(
    backdrop: ViewBackdrop,
    shape: Shape,
    surface: Color,
    effects: Boolean = true,
    blur: Dp = 10.dp,
    lensHeight: Dp = 16.dp,
    lensAmount: Dp = 24.dp,
): Modifier = this
    .then(backdrop.trackPosition)
    .drawBackdrop(
        backdrop = backdrop.backdrop,
        shape = { shape },
        effects = {
            if (effects) {
                vibrancy()
                blur(blur.toPx())
                lens(lensHeight.toPx(), lensAmount.toPx())
            }
        },
        shadow = null, // the default shadow shows as a faint box around the surface
        onDrawSurface = { drawRect(surface) },
    )
