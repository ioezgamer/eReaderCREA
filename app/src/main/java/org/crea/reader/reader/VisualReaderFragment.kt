/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.reader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.ActionMode
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets as ComposeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.readium.r2.shared.publication.services.positions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.BaseActionModeCallback
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.allAreHtml
import org.readium.r2.shared.publication.allAreHtml
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.util.Language
import org.crea.reader.Application
import org.crea.reader.R
import org.crea.reader.data.model.Highlight
import org.crea.reader.databinding.FragmentReaderBinding
import org.crea.reader.reader.tts.TtsControls
import org.crea.reader.reader.tts.TtsPreferencesBottomSheetDialogFragment
import org.crea.reader.reader.tts.TtsViewModel
import org.crea.reader.stats.PageTurnCounter
import org.crea.reader.stats.ReadingSession
import org.crea.reader.ui.glass.GlassSupport
import org.crea.reader.ui.glass.liquidGlass
import org.crea.reader.ui.glass.liquidGlassSurfaceColor
import org.crea.reader.ui.glass.rememberViewBackdrop
import org.crea.reader.utils.clearPadding
import org.crea.reader.utils.extensions.confirmDialog
import org.crea.reader.utils.extensions.throttleLatest
import org.crea.reader.utils.hideSystemUi
import org.crea.reader.utils.observeWhenStarted
import org.crea.reader.utils.padSystemUi
import org.crea.reader.utils.showSystemUi
import org.crea.reader.utils.toggleSystemUi
import org.crea.reader.utils.viewLifecycle

/*
 * Base reader fragment class
 *
 * Provides common menu items and saves last location on stop.
 */
@OptIn(ExperimentalReadiumApi::class)
abstract class VisualReaderFragment : BaseReaderFragment() {

    protected var binding: FragmentReaderBinding by viewLifecycle()

    private lateinit var navigatorFragment: Fragment

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * When true, the user won't be able to interact with the navigator.
     */
    private var disableTouches by mutableStateOf(false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        navigatorFragment = navigator as Fragment

        (navigator as OverflowableNavigator).apply {
            // This will automatically turn pages when tapping the screen edges or arrow keys.
            addInputListener(DirectionalNavigationAdapter(this, animatedTransition = true))
        }

        (navigator as VisualNavigator).apply {
            // Present the image full screen when the user taps it. Bind it before the
            // toggle-system-UI listener so it takes precedence.
            addInputListener(object : InputListener {
                @OptIn(ExperimentalReadiumApi::class)
                override fun onTap(event: TapEvent): Boolean {
                    val image = event.targetElement?.content as? Content.ImageElement
                        ?: return false
                    showImageViewer(image)
                    return true
                }
            })

            addInputListener(object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    requireActivity().toggleSystemUi()
                    return true
                }
            })
        }

        setupObservers()

        childFragmentManager.addOnBackStackChangedListener {
            updateSystemUiVisibility()
        }
        binding.fragmentReaderContainer.setOnApplyWindowInsetsListener { container, insets ->
            updateSystemUiPadding(container, insets)
            insets
        }

        binding.overlay.setContent {
            if (disableTouches) {
                // Add an invisible box on top of the navigator to intercept touch gestures.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures {
                                requireActivity().toggleSystemUi()
                            }
                        }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                content = { Overlay() }
            )
        }

        val menuHost: MenuHost = requireActivity()

        menuHost.addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menu.findItem(R.id.tts).isVisible = (model.tts != null)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    when (menuItem.itemId) {
                        R.id.tts -> {
                            checkNotNull(model.tts).start(navigator)
                            return true
                        }
                    }
                    return false
                }
            },
            viewLifecycleOwner
        )

        model.visualFragmentChannel.receive(viewLifecycleOwner) { event ->
            when (event) {
                is ReaderViewModel.VisualFragmentCommand.ShowPopup ->
                    showFootnotePopup(event.text)
            }
        }
    }

    @Composable
    private fun BoxScope.PageIndicator() {
        val positions by produceState<List<Locator>>(emptyList(), publication) {
            value = publication.positions()
        }
        if (positions.isEmpty()) return

        val locator by navigator.currentLocator.collectAsState()
        val total = positions.size
        val current = (
            locator.locations.position
                ?: locator.locations.totalProgression?.let { (it * total).toInt() + 1 }
                ?: 1
            ).coerceIn(1, total)

        val barsVisible = ComposeWindowInsets.statusBars.getTop(LocalDensity.current) > 0
        var dragging by remember { mutableStateOf<Float?>(null) }
        val shown = dragging?.roundToInt()?.coerceIn(1, total) ?: current
        val label = stringResource(R.string.page_indicator, shown, total)
        val barSize by (requireContext().applicationContext as Application).readerLook.barSize.collectAsState()
        val k = barSize.relative
        val glassTop = colorResource(R.color.glass_nav_top)
        val glassBottom = colorResource(R.color.glass_nav)
        val onGlass = colorResource(R.color.crea_on_surface)
        // Real Liquid Glass over the book where the device allows it; the translucent gradient otherwise.
        val realGlass = remember { GlassSupport.effectsEnabled(requireContext()) }
        val backdrop = rememberViewBackdrop(listOf(binding.fragmentReaderContainer))
        val glassSurface = liquidGlassSurfaceColor(alpha = 0.6f)
        val barShape = RoundedCornerShape(topStart = (24 * k).dp, topEnd = (24 * k).dp)

        if (barsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(
                        if (realGlass) Modifier.liquidGlass(backdrop, barShape, glassSurface)
                        else Modifier.background(Brush.verticalGradient(listOf(glassTop, glassBottom)))
                    )
                    .padding(horizontal = (16 * k).dp, vertical = (4 * k).dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(label, color = onGlass, fontSize = (13 * k).sp)
                if (total > 1) {
                    Slider(
                        value = dragging ?: current.toFloat(),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let {
                                val index = it.roundToInt().coerceIn(1, total) - 1
                                navigator.go(positions[index], animated = false)
                            }
                            dragging = null
                        },
                        valueRange = 1f..total.toFloat(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((48 * k).dp.coerceAtLeast(40.dp))
                    )
                }
            }
        } else {
            Text(
                label,
                color = onGlass,
                fontSize = (12 * k).sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .then(
                        if (realGlass) Modifier.liquidGlass(backdrop, RoundedCornerShape((10 * k).dp), glassSurface, blur = 6.dp, lensHeight = 6.dp, lensAmount = 8.dp)
                        else Modifier.background(glassBottom, RoundedCornerShape((10 * k).dp))
                    )
                    .padding(horizontal = (8 * k).dp, vertical = (2 * k).dp)
            )
        }
    }

    @Composable
    private fun BoxScope.Overlay() {
        PageIndicator()
        model.tts?.let { tts ->
            TtsControls(
                model = tts,
                onPreferences = {
                    TtsPreferencesBottomSheetDialogFragment()
                        .show(childFragmentManager, "TtsSettings")
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
            )
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val stats = (requireContext().applicationContext as Application).readingStats
                val session = ReadingSession(stats).also { it.start() }
                val turns = PageTurnCounter(stats)
                try {
                    navigator.currentLocator
                        .onEach {
                            model.saveProgression(it)
                            it.locations.position?.let(turns::onPosition)
                        }
                        .launchIn(this)
                    awaitCancellation()
                } finally {
                    session.stop()
                }
            }
        }

        (navigator as? DecorableNavigator)
            ?.addDecorationListener("highlights", decorationListener)

        viewLifecycleOwner.lifecycleScope.launch {
            setupHighlights(viewLifecycleOwner.lifecycleScope)
            setupSearch(viewLifecycleOwner.lifecycleScope)
            setupTts()
        }
    }

    private suspend fun setupHighlights(scope: CoroutineScope) {
        (navigator as? DecorableNavigator)?.let { navigator ->
            model.highlightDecorations
                .onEach { navigator.applyDecorations(it, "highlights") }
                .launchIn(scope)
        }
    }

    private suspend fun setupSearch(scope: CoroutineScope) {
        (navigator as? DecorableNavigator)?.let { navigator ->
            model.searchDecorations
                .onEach { navigator.applyDecorations(it, "search") }
                .launchIn(scope)
        }
    }

    /**
     * Setup text-to-speech observers, if available.
     */
    private suspend fun setupTts() {
        model.tts?.apply {
            events
                .observeWhenStarted(viewLifecycleOwner) { event ->
                    when (event) {
                        is TtsViewModel.Event.OnError -> {
                            showError(event.error.toUserError())
                        }
                        is TtsViewModel.Event.OnMissingVoiceData ->
                            confirmAndInstallTtsVoice(event.language)
                    }
                }

            // Navigate to the currently spoken word.
            // This will automatically turn pages when needed.
            position
                .filterNotNull()
                // Improve performances by throttling the moves to maximum one per second.
                .throttleLatest(1.seconds)
                .observeWhenStarted(viewLifecycleOwner) { locator ->
                    navigator.go(locator, animated = false)
                }

            // Prevent interacting with the publication (including page turns) while the TTS is
            // playing.
            isPlaying
                .observeWhenStarted(viewLifecycleOwner) { isPlaying ->
                    disableTouches = isPlaying
                }

            // Highlight the currently spoken utterance.
            (navigator as? DecorableNavigator)?.let { navigator ->
                highlight
                    .observeWhenStarted(viewLifecycleOwner) { locator ->
                        val decoration = locator?.let {
                            Decoration(
                                id = "tts",
                                locator = it,
                                style = Decoration.Style.Highlight(tint = Color.RED)
                            )
                        }
                        navigator.applyDecorations(listOfNotNull(decoration), "tts")
                    }
            }
        }
    }

    /**
     * Confirms with the user if they want to download the TTS voice data for the given language.
     */
    private suspend fun confirmAndInstallTtsVoice(language: Language) {
        val activity = activity ?: return
        model.tts ?: return

        if (
            activity.confirmDialog(
                getString(
                    R.string.tts_error_language_support_incomplete,
                    language.locale.displayLanguage
                )
            )
        ) {
            AndroidTtsEngine.requestInstallVoice(activity)
        }
    }

    override fun onListen() {
        val tts = model.tts
        if (tts == null) super.onListen() else tts.start(navigator)
    }

    override fun onAddNote() {
        viewLifecycleOwner.lifecycleScope.launch {
            val selection = (navigator as? SelectableNavigator)?.currentSelection()
            if (selection?.locator?.text?.highlight.isNullOrBlank()) {
                android.widget.Toast.makeText(requireContext(), R.string.appearance_note_select_text, android.widget.Toast.LENGTH_LONG).show()
            } else {
                showAnnotationPopup()
            }
        }
    }

    override fun go(locator: Locator, animated: Boolean) {
        model.tts?.stop()
        super.go(locator, animated)
    }

    override fun onDestroyView() {
        (navigator as? DecorableNavigator)?.removeDecorationListener(decorationListener)
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        setMenuVisibility(!hidden)
        requireActivity().invalidateOptionsMenu()
    }

    // DecorableNavigator.Listener

    private val decorationListener by lazy { DecorationListener() }

    inner class DecorationListener : DecorableNavigator.Listener {
        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
            val decoration = event.decoration
            // We stored the highlight's database ID in the `Decoration.extras` map, for
            // easy retrieval. You can store arbitrary information in the map.
            val id = (decoration.extras["id"] as Long)
                .takeIf { it > 0 } ?: return false

            // This listener will be called when tapping on any of the decorations in the
            // "highlights" group. To differentiate between the page margin icon and the
            // actual highlight, we check for the type of `decoration.style`. But you could
            // use any other information, including the decoration ID or the extras bundle.
            if (decoration.style is DecorationStyleAnnotationMark) {
                showAnnotationPopup(id)
            } else {
                event.rect?.let { rect ->
                    val isUnderline = (decoration.style is Decoration.Style.Underline)
                    showHighlightPopup(
                        rect,
                        style = if (isUnderline) {
                            Highlight.Style.UNDERLINE
                        } else {
                            Highlight.Style.HIGHLIGHT
                        },
                        highlightId = id
                    )
                }
            }

            return true
        }
    }

    // Highlights

    private var popupWindow: PopupWindow? = null
    private var mode: ActionMode? = null

    // Available tint colors for highlight and underline annotations.
    // Map of View IDs (@IdRes) to their corresponding color values (@ColorInt).
    private val highlightTints = mapOf(
        R.id.red to Color.rgb(247, 124, 124),
        R.id.green to Color.rgb(173, 247, 123),
        R.id.blue to Color.rgb(124, 198, 247),
        R.id.yellow to Color.rgb(249, 239, 125),
        R.id.purple to Color.rgb(182, 153, 255)
    )

    val customSelectionActionModeCallback: ActionMode.Callback by lazy { SelectionActionModeCallback() }

    private inner class SelectionActionModeCallback : BaseActionModeCallback() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_action_mode, menu)
            if (navigator is DecorableNavigator) {
                menu.findItem(R.id.highlight).isVisible = true
                menu.findItem(R.id.underline).isVisible = true
                menu.findItem(R.id.note).isVisible = true
            }
            menu.findItem(R.id.ai_explain).isVisible =
                (requireContext().applicationContext as org.crea.reader.Application).aiService.availability.available.value &&
                publication.readingOrder.allAreHtml
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.highlight -> showHighlightPopupWithStyle(Highlight.Style.HIGHLIGHT)
                R.id.underline -> showHighlightPopupWithStyle(Highlight.Style.UNDERLINE)
                R.id.note -> showAnnotationPopup()
                R.id.ai_explain -> explainSelectionWithAi()
                else -> return false
            }

            mode.finish()
            return true
        }
    }

    /** Sends the selected passage (with a little text around it for context) to the reading assistant. */
    private fun explainSelectionWithAi() {
        viewLifecycleOwner.lifecycleScope.launch {
            val selection = (navigator as? SelectableNavigator)?.currentSelection()?.locator ?: return@launch
            val text = selection.text
            val selected = text.highlight?.trim().orEmpty()
            if (selected.isEmpty()) return@launch
            val surrounding = ((text.before?.takeLast(300).orEmpty()) + selected + (text.after?.take(300).orEmpty())).trim()
            val app = requireContext().applicationContext as org.crea.reader.Application
            org.crea.reader.ai.AiSheets.showSelection(
                requireContext(),
                layoutInflater,
                viewLifecycleOwner.lifecycleScope,
                app.aiService,
                selected,
                surrounding,
                publication.metadata.title,
            )
        }
    }

    private fun showHighlightPopupWithStyle(style: Highlight.Style) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Get the rect of the current selection to know where to position the highlight
            // popup.
            (navigator as? SelectableNavigator)?.currentSelection()?.rect?.let { selectionRect ->
                showHighlightPopup(selectionRect, style)
            }
        }
    }

    private fun showHighlightPopup(rect: RectF, style: Highlight.Style, highlightId: Long? = null) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (popupWindow?.isShowing == true) return@launch

            model.activeHighlightId.value = highlightId

            val isReverse = (rect.top > 60)
            val popupView = layoutInflater.inflate(
                if (isReverse) R.layout.view_action_mode_reverse else R.layout.view_action_mode,
                null,
                false
            )
            popupView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            popupWindow = PopupWindow(
                popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                isFocusable = true
                setOnDismissListener {
                    model.activeHighlightId.value = null
                }
            }

            val x = rect.left
            val y = if (isReverse) rect.top else rect.bottom + rect.height()

            popupWindow?.showAtLocation(popupView, Gravity.NO_GRAVITY, x.toInt(), y.toInt())

            val highlight = highlightId?.let { model.highlightById(it) }
            popupView.run {
                findViewById<View>(R.id.notch).run {
                    setX(rect.left * 2)
                }

                fun selectTint(view: View) {
                    val tint = highlightTints[view.id] ?: return
                    selectHighlightTint(highlightId, style, tint)
                }

                findViewById<View>(R.id.red).setOnClickListener(::selectTint)
                findViewById<View>(R.id.green).setOnClickListener(::selectTint)
                findViewById<View>(R.id.blue).setOnClickListener(::selectTint)
                findViewById<View>(R.id.yellow).setOnClickListener(::selectTint)
                findViewById<View>(R.id.purple).setOnClickListener(::selectTint)

                findViewById<View>(R.id.annotation).setOnClickListener {
                    popupWindow?.dismiss()
                    showAnnotationPopup(highlightId)
                }
                findViewById<View>(R.id.del).run {
                    visibility = if (highlight != null) View.VISIBLE else View.GONE
                    setOnClickListener {
                        highlightId?.let {
                            model.deleteHighlight(highlightId)
                        }
                        popupWindow?.dismiss()
                        mode?.finish()
                    }
                }
            }
        }
    }

    private fun selectHighlightTint(
        highlightId: Long? = null,
        style: Highlight.Style,
        @ColorInt tint: Int,
    ) =
        viewLifecycleOwner.lifecycleScope.launch {
            if (highlightId != null) {
                model.updateHighlightStyle(highlightId, style, tint)
            } else {
                (navigator as? SelectableNavigator)?.let { navigator ->
                    navigator.currentSelection()?.let { selection ->
                        model.addHighlight(
                            locator = selection.locator,
                            style = style,
                            tint = tint
                        )
                    }
                    navigator.clearSelection()
                }
            }

            popupWindow?.dismiss()
            mode?.finish()
        }

    private fun showAnnotationPopup(highlightId: Long? = null) {
        viewLifecycleOwner.lifecycleScope.launch {
            val activity = activity ?: return@launch
            val view = layoutInflater.inflate(R.layout.popup_note, null, false)
            val note = view.findViewById<EditText>(R.id.note)
            val alert = AlertDialog.Builder(activity)
                .setView(view)
                .create()

            fun dismiss() {
                alert.window?.let { window ->
                    WindowCompat.getInsetsController(window, note).hide(WindowInsetsCompat.Type.ime())
                }

                alert.dismiss()
                mode?.finish()
            }

            with(view) {
                val highlight = highlightId?.let { model.highlightById(it) }
                if (highlight != null) {
                    note.setText(highlight.annotation)
                    findViewById<View>(R.id.sidemark).setBackgroundColor(highlight.tint)
                    findViewById<TextView>(R.id.select_text).text =
                        highlight.locator.text.highlight

                    findViewById<TextView>(R.id.positive).setOnClickListener {
                        val text = note.text.toString()
                        model.updateHighlightAnnotation(highlight.id, annotation = text)
                        dismiss()
                    }
                } else {
                    val tint = highlightTints.values.random()
                    findViewById<View>(R.id.sidemark).setBackgroundColor(tint)
                    val navigator =
                        navigator as? SelectableNavigator ?: return@launch
                    val selection = navigator.currentSelection() ?: return@launch
                    navigator.clearSelection()
                    findViewById<TextView>(R.id.select_text).text =
                        selection.locator.text.highlight

                    findViewById<TextView>(R.id.positive).setOnClickListener {
                        model.addHighlight(
                            locator = selection.locator,
                            style = Highlight.Style.HIGHLIGHT,
                            tint = tint,
                            annotation = note.text.toString()
                        )
                        dismiss()
                    }
                }

                findViewById<TextView>(R.id.negative).setOnClickListener {
                    dismiss()
                }
            }

            alert.show()
        }
    }

    private fun showFootnotePopup(
        text: CharSequence,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Initialize a new instance of LayoutInflater service
            val inflater =
                requireActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

            // Inflate the custom layout/view
            val customView = inflater.inflate(R.layout.popup_footnote, null)

            // Initialize a new instance of popup window
            val mPopupWindow = PopupWindow(
                customView,
                ListPopupWindow.WRAP_CONTENT,
                ListPopupWindow.WRAP_CONTENT
            )
            mPopupWindow.isOutsideTouchable = true
            mPopupWindow.isFocusable = true

            // Set an elevation value for popup window
            // Call requires API level 21
            mPopupWindow.elevation = 5.0f

            val textView = customView.findViewById(R.id.footnote) as TextView
            textView.text = text

            // Get a reference for the custom view close button
            val closeButton = customView.findViewById(R.id.ib_close) as ImageButton

            // Set a click listener for the popup window close button
            closeButton.setOnClickListener {
                // Dismiss the popup window
                mPopupWindow.dismiss()
            }

            // Finally, show the popup window at the center location of root relative layout
            // FIXME: should anchor on noteref and be scrollable if the note is too long.
            mPopupWindow.showAtLocation(
                requireView(),
                Gravity.CENTER,
                0,
                0
            )
        }
    }

    // Image Viewer

    @OptIn(ExperimentalReadiumApi::class)
    private fun showImageViewer(image: Content.ImageElement) {
        ImageViewerDialogFragment.newInstance(image)
            .show(childFragmentManager, ImageViewerDialogFragment.TAG)
    }

    fun updateSystemUiVisibility() {
        if (navigatorFragment.isHidden) {
            requireActivity().showSystemUi()
        } else {
            requireActivity().hideSystemUi()
        }

        requireView().requestApplyInsets()
    }

    private fun updateSystemUiPadding(container: View, insets: WindowInsets) {
        if (navigatorFragment.isHidden) {
            container.padSystemUi(insets, requireActivity() as AppCompatActivity)
        } else {
            container.clearPadding()
        }
    }
}

/**
 * Decoration Style for a page margin icon.
 *
 * This is an example of a custom Decoration Style declaration.
 */
@Parcelize
data class DecorationStyleAnnotationMark(@ColorInt val tint: Int) : Decoration.Style

/**
 * Decoration Style for a page number label.
 *
 * This is an example of a custom Decoration Style declaration.
 *
 * @param label Page number label as declared in the `page-list` link object.
 */
@Parcelize
data class DecorationStylePageNumber(val label: String) : Decoration.Style

