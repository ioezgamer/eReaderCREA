/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader

import android.content.Intent
import android.os.Bundle
import org.crea.reader.reader.ReaderActivityContract
import org.crea.reader.widget.WidgetIntents
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.crea.reader.sync.SyncScheduler
import org.crea.reader.ui.glass.GlassSupport
import org.crea.reader.ui.glass.LiquidNavHost

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_bookshelf,
                R.id.navigation_online_catalog,
                R.id.navigation_catalog_list,
                R.id.navigation_community,
                R.id.navigation_settings
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        val glassBar = GlassNavigationBar.attach(this, navHostFragment)
        // Android 12+ gets the real-blur Liquid Glass bar; older versions keep the translucent pill.
        if (GlassSupport.realBlur) LiquidNavHost.install(this, glassBar)

        viewModel.channel.receive(this) { handleEvent(it) }
        handleWidgetIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    /** A home-screen widget asked for a book or for the statistics. */
    private fun handleWidgetIntent(intent: Intent?) {
        val bookId = intent?.getLongExtra(WidgetIntents.EXTRA_OPEN_BOOK, 0L) ?: 0L
        if (bookId != 0L) {
            intent?.removeExtra(WidgetIntents.EXTRA_OPEN_BOOK)
            lifecycleScope.launch {
                val app = application as Application
                app.readerRepository.open(bookId).onSuccess {
                    startActivity(ReaderActivityContract().createIntent(this@MainActivity, ReaderActivityContract.Arguments(bookId)))
                }
            }
        }
        if (intent?.getBooleanExtra(WidgetIntents.EXTRA_OPEN_STATS, false) == true) {
            intent.removeExtra(WidgetIntents.EXTRA_OPEN_STATS)
            runCatching { navController.navigate(R.id.navigation_stats) }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as Application
        SyncScheduler.runIfStale(this, app.syncStateStore.snapshot())
        lifecycleScope.launch { app.aiService.availability.refresh() }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun handleEvent(event: MainViewModel.Event) {
        when (event) {
            is MainViewModel.Event.ImportPublicationSuccess ->
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.import_publication_success),
                    Snackbar.LENGTH_LONG
                ).show().also {
                    val app = application as Application
                    if (app.syncStateStore.isEnabled) SyncScheduler.runNow(this)
                }

            is MainViewModel.Event.ImportPublicationError -> {
                event.error.toUserError().show(this)
            }

            is MainViewModel.Event.AlreadyInLibrary ->
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.import_already_in_library, event.title ?: getString(R.string.import_this_book)),
                    Snackbar.LENGTH_LONG
                ).show()

            is MainViewModel.Event.PossibleDuplicate -> {
                var decided = false
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.import_duplicate_title)
                    .setMessage(
                        getString(
                            R.string.import_duplicate_message,
                            event.existingTitle ?: getString(R.string.import_this_book),
                            event.title ?: getString(R.string.import_this_book),
                        )
                    )
                    .setNegativeButton(R.string.import_duplicate_skip) { _, _ ->
                        decided = true
                        viewModel.discardImport(event.token)
                    }
                    .setPositiveButton(R.string.import_duplicate_add) { _, _ ->
                        decided = true
                        viewModel.confirmImport(event.token)
                    }
                    // Closing the dialog any other way keeps the library as it was.
                    .setOnDismissListener { if (!decided) viewModel.discardImport(event.token) }
                    .show()
            }
        }
    }
}

