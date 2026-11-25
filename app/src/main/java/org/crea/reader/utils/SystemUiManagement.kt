/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.utils

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat

// Using ViewCompat and WindowInsetsCompat does not work properly in all versions of Android
/** Returns `true` if fullscreen or immersive mode is not set. */
@Suppress("DEPRECATION")
private fun Activity.isSystemUiVisible(): Boolean {
    return this.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
}

// Using ViewCompat and WindowInsetsCompat does not work properly in all versions of Android

/** Enable fullscreen or immersive mode. */
@Suppress("DEPRECATION")
fun Activity.hideSystemUi() {
    this.window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_IMMERSIVE
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
}

// Using ViewCompat and WindowInsetsCompat does not work properly in all versions of Android

/** Implemented by screens that know whether what lies under the status bar (a book page) is dark. */
interface StatusBarBackdrop {
    val backdropIsDark: Boolean
}

/** Dark status-bar icons over a light backdrop; light icons over a dark one. */
private fun Activity.wantsDarkIcons(): Boolean =
    (this as? StatusBarBackdrop)?.backdropIsDark?.not()
        ?: ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES)

/** Disable fullscreen or immersive mode. */
@Suppress("DEPRECATION")
fun Activity.showSystemUi() {
    // Setting the flags replaces all of them: the "light bar" ones must be set again, or the
    // icons turn white and vanish over a white page.
    var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    if (wantsDarkIcons()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    this.window.decorView.systemUiVisibility = flags
}

/** Toggle fullscreen or immersive mode. */
fun Activity.toggleSystemUi() {
    if (this.isSystemUiVisible()) {
        this.hideSystemUi()
    } else {
        this.showSystemUi()
    }
}

/** Set padding around view so that content doesn't overlap system UI */
fun View.padSystemUi(insets: WindowInsets, activity: AppCompatActivity) =
    WindowInsetsCompat.toWindowInsetsCompat(insets, this)
        // Actual insets are inexplicably wrong when a SearchView is expanded.
        .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()).apply {
            setPadding(
                left,
                top + activity.supportActionBar!!.height,
                right,
                bottom
            )
        }

/** Clear padding around view */
fun View.clearPadding() =
    setPadding(0, 0, 0, 0)

