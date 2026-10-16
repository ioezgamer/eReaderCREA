package org.crea.reader.reader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How big the reader's own bars (page number, page slider) are drawn. [MEDIUM] is the base size. */
enum class BarSize(val scale: Float) {
    SMALL(0.72f),
    MEDIUM(0.9f),
    LARGE(1.15f),
    ;

    /** Factor to apply to sizes that were designed for [MEDIUM]. */
    val relative: Float get() = scale / MEDIUM.scale

    companion object {
        fun fromIndex(index: Int): BarSize = entries.getOrElse(index) { MEDIUM }
    }
}

object BrightnessRules {
    /** Never let the screen go fully dark. */
    const val MIN = 0.03f

    /** Value shown on the control while the brightness is automatic. */
    const val AUTO_THUMB = 0.5f

    /** Same value Android uses for "do not override the system brightness". */
    const val NO_OVERRIDE = -1f

    fun fromPercent(percent: Int): Float = (percent.coerceIn(0, 100) / 100f).coerceAtLeast(MIN)

    fun toPercent(brightness: Float): Int = Math.round(brightness.coerceAtLeast(MIN) * 100f)
}

/**
 * Reader-only appearance that Readium's preferences do not cover: the size of the bars and the
 * screen brightness (null = follow the system). Kept on this device.
 */
class ReaderLook(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("reader_look", Context.MODE_PRIVATE)

    private val _barSize = MutableStateFlow(BarSize.fromIndex(prefs.getInt(KEY_BARS, BarSize.MEDIUM.ordinal)))
    val barSize: StateFlow<BarSize> = _barSize

    private val _brightness = MutableStateFlow(
        prefs.getFloat(KEY_BRIGHTNESS, BrightnessRules.NO_OVERRIDE).takeIf { it >= 0f },
    )
    val brightness: StateFlow<Float?> = _brightness

    fun setBarSize(size: BarSize) {
        prefs.edit().putInt(KEY_BARS, size.ordinal).apply()
        _barSize.value = size
    }

    fun setBrightness(value: Float?) {
        prefs.edit().putFloat(KEY_BRIGHTNESS, value ?: BrightnessRules.NO_OVERRIDE).apply()
        _brightness.value = value
    }

    private companion object {
        const val KEY_BARS = "bars"
        const val KEY_BRIGHTNESS = "brightness"
    }
}
