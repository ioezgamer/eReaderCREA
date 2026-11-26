/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.reader

import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import org.crea.reader.utils.StatusBarBackdrop
import org.crea.reader.utils.launchWebBrowser

/*
 * An activity to read a publication
 *
 * This class can be used as it is or be inherited from.
 */
open class ReaderActivity : AppCompatActivity(), StatusBarBackdrop {

    /** The book page lies under the status bar, unless the outline or DRM screen covers it. */
    override val backdropIsDark: Boolean
        get() {
            val reader = if (::readerFragment.isInitialized) readerFragment as? VisualReaderFragment else null
            val pageDark = reader?.takeUnless { it.isHidden }?.let { runCatching { it.isPageDark() }.getOrNull() }
            return pageDark ?: run {
                (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }

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
        placeBarCapsule()

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

    override fun onStop() {
        super.onStop()
        // Reading time and pages changed: refresh the statistics widget.
        org.crea.reader.widget.WidgetUpdater.refreshAll(this)
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

    /** Toolbar position the solid capsule was placed for (-1: no capsule, the bar is transparent). */
    private var capsuleTop = -1

    private fun placeBarCapsule() {
        val container = findViewById<View>(androidx.appcompat.R.id.action_bar_container) ?: return
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBarBackground() }
        container.post { updateBarBackground() }
    }

    /**
     * Over the book the top bar is transparent: the reader draws Liquid Glass behind its icons
     * (VisualReaderFragment). Over the outline and DRM screens there is no glass, so the bar gets a
     * solid floating capsule around the toolbar. The bar's container also spans the status bar (its
     * height varies per device), so the capsule follows the toolbar's position.
     */
    private fun updateBarBackground() {
        val container = findViewById<View>(androidx.appcompat.R.id.action_bar_container) ?: return
        val toolbar = findViewById<View>(androidx.appcompat.R.id.action_bar) ?: return
        val readerShown = ::readerFragment.isInitialized &&
            readerFragment is VisualReaderFragment && !readerFragment.isHidden
        if (readerShown) {
            if (capsuleTop != -1 || container.background != null) {
                container.background = null
                capsuleTop = -1
            }
            return
        }
        if (toolbar.top == capsuleTop) return
        capsuleTop = toolbar.top
        val side = resources.getDimensionPixelSize(R.dimen.reader_bar_capsule_side)
        val gap = resources.getDimensionPixelSize(R.dimen.reader_bar_capsule_gap)
        val capsule = ContextCompat.getDrawable(this, R.drawable.bg_reader_bar) ?: return
        container.background = LayerDrawable(arrayOf(capsule)).apply {
            setLayerInset(0, side, toolbar.top + gap, side, 0)
        }
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
        updateBarBackground()
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

