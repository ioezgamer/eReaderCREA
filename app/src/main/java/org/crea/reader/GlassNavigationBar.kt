package org.crea.reader

import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Floating glass tab bar. Mirrors what a BottomNavigationView does with the nav graph (single top,
 * save and restore each tab's state), but shows the name of the selected tab only so the pill
 * stays as small as its content.
 */
class GlassNavigationBar(
    private val pill: ViewGroup,
    private val navController: NavController,
    private val tabs: List<Tab>,
) {
    class Tab(val view: View, val label: View, val graphId: Int)

    private val _selectedIndex = MutableStateFlow(0)

    /** Index of the tab that matches the current screen; the Liquid Glass bar follows it. */
    val selectedIndex: StateFlow<Int> = _selectedIndex

    /** Same navigation as tapping the tab, for the other bar that can replace this one. */
    fun openAt(index: Int) {
        tabs.getOrNull(index)?.let(::open)
    }

    init {
        tabs.forEach { tab ->
            tab.view.setOnClickListener { open(tab) }
        }
        navController.addOnDestinationChangedListener { _, destination, _ -> select(destination) }
        navController.currentDestination?.let { select(it, animate = false) }
    }

    private fun open(tab: Tab) {
        val options = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setEnterAnim(R.anim.nav_fade_in)
            .setExitAnim(R.anim.nav_fade_out)
            .setPopEnterAnim(R.anim.nav_fade_in)
            .setPopExitAnim(R.anim.nav_fade_out)
            .setPopUpTo(navController.graph.startDestinationId, inclusive = false, saveState = true)
            .build()
        runCatching { navController.navigate(tab.graphId, null, options) }
    }

    private fun select(destination: NavDestination, animate: Boolean = true) {
        val active = tabs.firstOrNull { tab -> destination.hierarchy.any { it.id == tab.graphId } }
        tabs.indexOf(active).takeIf { it >= 0 }?.let { _selectedIndex.value = it }
        if (animate) {
            TransitionManager.beginDelayedTransition(pill, AutoTransition().setDuration(220))
        }
        tabs.forEach { tab ->
            val selected = tab === active
            tab.view.isSelected = selected
            tab.label.isVisible = selected
        }
    }

    companion object {
        fun attach(activity: MainActivity, navHost: NavHostFragment): GlassNavigationBar {
            val tabs = listOf(
                Tab(activity.findViewById(R.id.nav_bookshelf), activity.findViewById(R.id.nav_bookshelf_label), R.id.bookshelf),
                Tab(activity.findViewById(R.id.nav_online), activity.findViewById(R.id.nav_online_label), R.id.online_catalog),
                Tab(activity.findViewById(R.id.nav_catalogs), activity.findViewById(R.id.nav_catalogs_label), R.id.catalogs),
                Tab(activity.findViewById(R.id.nav_community), activity.findViewById(R.id.nav_community_label), R.id.community),
                Tab(activity.findViewById(R.id.nav_settings), activity.findViewById(R.id.nav_settings_label), R.id.settings),
            )
            return GlassNavigationBar(activity.findViewById(R.id.nav_container), navHost.navController, tabs)
        }
    }
}
