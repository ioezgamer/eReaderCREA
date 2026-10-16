/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.reader

import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.toUri
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.databinding.ActivityReaderBinding
import org.crea.reader.drm.DrmManagementContract
import org.crea.reader.drm.DrmManagementFragment
import org.crea.reader.outline.OutlineContract
import org.crea.reader.outline.OutlineFragment
import org.crea.reader.sync.SyncScheduler
import org.crea.reader.utils.launchWebBrowser

/*
 * An activity to read a publication
 *
 * This class can be used as it is or be inherited from.
 */
open class ReaderActivity : AppCompatActivity() {

    private val model: ReaderViewModel by viewModels()

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = ReaderViewModel.createFactory(
            application as Application,
            ReaderActivityContract.parseIntent(this)
        )

    private lateinit var binding: ActivityReaderBinding
    private lateinit var readerFragment: BaseReaderFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        this.binding = binding

        val readerFragment = supportFragmentManager.findFragmentByTag(READER_FRAGMENT_TAG)
            ?.let { it as BaseReaderFragment }
            ?: run { createReaderFragment(model.readerInitData) }

        if (readerFragment is VisualReaderFragment) {
            val fullscreenDelegate = FullscreenReaderActivityDelegate(this, readerFragment, binding)
            lifecycle.addObserver(fullscreenDelegate)
        }

        readerFragment?.let { this.readerFragment = it }

        model.activityChannel.receive(this) { handleReaderFragmentEvent(it) }

        reconfigureActionBar()

        supportFragmentManager.setFragmentResultListener(
            OutlineContract.REQUEST_KEY,
            this,
            FragmentResultListener { _, result ->
                val locator = OutlineContract.parseResult(result).destination
                closeOutlineFragment(locator)
            }
        )

        supportFragmentManager.setFragmentResultListener(
            DrmManagementContract.REQUEST_KEY,
            this,
            FragmentResultListener { _, result ->
                if (DrmManagementContract.parseResult(result).hasReturned) {
                    finish()
                }
            }
        )

        supportFragmentManager.addOnBackStackChangedListener {
            reconfigureActionBar()
        }

        // Add support for display cutout.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Brightness only affects this window, so it goes back to the system value when leaving.
        val look = (application as Application).readerLook
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                look.brightness.collect { applyBrightness(it) }
            }
        }
    }

    private fun applyBrightness(value: Float?) {
        window.attributes = window.attributes.apply {
            screenBrightness = value ?: BrightnessRules.NO_OVERRIDE
        }
    }

    private fun createReaderFragment(readerData: ReaderInitData): BaseReaderFragment? {
        val readerClass: Class<out Fragment>? = when (readerData) {
            is EpubReaderInitData -> EpubReaderFragment::class.java
            is ImageReaderInitData -> ImageReaderFragment::class.java
            is MediaReaderInitData -> AudioReaderFragment::class.java
            is PdfReaderInitData -> PdfReaderFragment::class.java
            is DummyReaderInitData -> null
        }

        readerClass?.let { it ->
            supportFragmentManager.commitNow {
                replace(R.id.activity_container, it, Bundle(), READER_FRAGMENT_TAG)
            }
        }

        return supportFragmentManager.findFragmentByTag(READER_FRAGMENT_TAG) as BaseReaderFragment?
    }

    override fun onDestroy() {
        super.onDestroy()
        // Publish where the reader stopped so the other devices (and the catalog) see it soon.
        val app = application as Application
        if (isFinishing && app.syncStateStore.isEnabled) SyncScheduler.runSoon(this)
    }

    override fun onStart() {
        super.onStart()
        reconfigureActionBar()
    }

    private fun reconfigureActionBar() {
        val currentFragment = supportFragmentManager.fragments.lastOrNull()

        title = when (currentFragment) {
            is OutlineFragment -> model.publication.metadata.title
            is DrmManagementFragment -> getString(R.string.title_fragment_drm_management)
            else -> model.publication.metadata.title
        }

        // The back arrow is always available: it closes the outline/DRM screens or the book itself.
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
    }

    private fun handleReaderFragmentEvent(command: ReaderViewModel.ActivityCommand) {
        when (command) {
            is ReaderViewModel.ActivityCommand.OpenOutlineRequested ->
                showOutlineFragment()
            is ReaderViewModel.ActivityCommand.OpenDrmManagementRequested ->
                showDrmManagementFragment()
            is ReaderViewModel.ActivityCommand.OpenExternalLink ->
                launchWebBrowser(this, command.url.toUri())
            is ReaderViewModel.ActivityCommand.ToastError ->
                command.error.show(this)
        }
    }

    private fun showOutlineFragment() {
        val outlineFragment = supportFragmentManager.findFragmentByTag(OUTLINE_FRAGMENT_TAG)
        if (outlineFragment == null) {
            supportFragmentManager.commit {
                add(
                    R.id.activity_container,
                    OutlineFragment::class.java,
                    Bundle(),
                    OUTLINE_FRAGMENT_TAG
                )
                hide(readerFragment)
                addToBackStack(null)
            }
        }
    }

    fun closeOutlineFragment(locator: Locator) {
        readerFragment.go(locator, true)
        supportFragmentManager.popBackStack()
    }

    private fun showDrmManagementFragment() {
        supportFragmentManager.commit {
            add(
                R.id.activity_container,
                DrmManagementFragment::class.java,
                Bundle(),
                DRM_FRAGMENT_TAG
            )
            hide(readerFragment)
            addToBackStack(null)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    finish()
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val READER_FRAGMENT_TAG = "reader"
        const val OUTLINE_FRAGMENT_TAG = "outline"
        const val DRM_FRAGMENT_TAG = "drm"
    }
}

