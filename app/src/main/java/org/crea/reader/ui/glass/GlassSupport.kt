package org.crea.reader.ui.glass

import android.content.Context
import android.os.Build
import android.os.PowerManager

/** What the device can do for the Liquid Glass look. Everything falls back to plain translucency. */
object GlassSupport {
    /**
     * Real blur needs RenderEffect (Android 12). The refraction lens needs AGSL shaders (Android 13);
     * the glass library skips the lens by itself below that, so Android 12 gets blur without refraction.
     */
    val realBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Battery saver turns the effects off: the same translucent surface is drawn without the shaders. */
    fun effectsEnabled(context: Context): Boolean =
        realBlur && context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
}
