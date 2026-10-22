package org.crea.reader.ui.glass

import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import org.crea.reader.GlassNavigationBar
import org.crea.reader.MainActivity
import org.crea.reader.R
import org.crea.reader.utils.compose.AppTheme

/**
 * Swaps the View-based pill of the main screen for the [LiquidNavigationBar].
 *
 * The existing [GlassNavigationBar] keeps doing the navigation (back stack, saved tab state); this
 * only changes how the bar looks. The screens grow to the bottom of the window so their content
 * scrolls under the glass.
 */
object LiquidNavHost {
    private val items = listOf(
        LiquidNavItem(R.drawable.ic_nav_library_outline, R.drawable.ic_nav_library_filled, R.string.title_bookshelf),
        LiquidNavItem(R.drawable.ic_nav_online_outline, R.drawable.ic_nav_online_filled, R.string.tab_online_catalog),
        LiquidNavItem(R.drawable.ic_nav_explore_outline, R.drawable.ic_nav_explore_filled, R.string.title_catalogs),
        LiquidNavItem(R.drawable.ic_nav_profile_outline, R.drawable.ic_nav_profile_filled, R.string.profile_title),
        LiquidNavItem(R.drawable.ic_nav_settings_outline, R.drawable.ic_nav_settings_filled, R.string.title_settings),
    )

    fun install(activity: MainActivity, bar: GlassNavigationBar) {
        val host = activity.findViewById<View>(R.id.nav_host_fragment)
        val pill = activity.findViewById<View>(R.id.nav_container)
        val compose = activity.findViewById<ComposeView>(R.id.liquid_nav)

        (host.layoutParams as ConstraintLayout.LayoutParams).let { params ->
            params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            host.layoutParams = params
        }
        pill.visibility = View.GONE
        compose.visibility = View.VISIBLE
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose.setContent {
            AppTheme {
                val selected by bar.selectedIndex.collectAsState()
                val effects = remember { GlassSupport.effectsEnabled(activity) }
                val backdrop = rememberViewBackdrop(listOf(host), activity.window.decorView.background)
                LiquidNavigationBar(
                    items = items,
                    selectedIndex = selected,
                    onSelect = bar::openAt,
                    backdrop = backdrop,
                    effects = effects,
                )
            }
        }
    }
}
