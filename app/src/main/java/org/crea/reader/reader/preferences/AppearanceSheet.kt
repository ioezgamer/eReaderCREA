@file:OptIn(ExperimentalReadiumApi::class)

package org.crea.reader.reader.preferences

import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import kotlin.math.abs
import org.crea.reader.Application
import org.crea.reader.LITERATA
import org.crea.reader.R
import org.crea.reader.reader.ReaderViewModel
import org.crea.reader.reader.ReaderViewModel.ReaderShortcut
import org.crea.reader.utils.compose.AppTheme
import org.readium.r2.navigator.epub.EpubPreferencesEditor
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.allAreHtml

/**
 * The reader's "Aspecto" panel: a compact card that drops from the top-right corner, under the
 * toolbar, with the few things people change most (text size, margins, paper, typeface,
 * brightness, bar size) and quick actions. The full Readium panel is one tap away.
 */
class AppearanceBottomSheetDialogFragment : DialogFragment() {

    private val viewModel: ReaderViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            window?.apply {
                setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
                // Light dim so the effect of every setting can be seen on the page.
                setDimAmount(0.1f)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent { AppTheme { Panel() } }
        }

    override fun onStart() {
        super.onStart()
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else 0
        dialog?.window?.apply {
            setLayout(minOf((PANEL_WIDTH_DP * density).toInt(), screenWidth - (16 * density).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP or Gravity.END)
            attributes = attributes.apply {
                x = (8 * density).toInt()
                y = statusBar + (48 * density).toInt()
            }
        }
    }

    @Composable
    private fun Panel() {
        val app = LocalContext.current.applicationContext as Application
        val settings = viewModel.settings
        val editor = settings?.editor?.collectAsState()?.value
        val aiAvailable by app.aiService.availability.readerAvailable.collectAsState(initial = false)
        val canUseAi = aiAvailable && viewModel.publication.readingOrder.allAreHtml
        val maxHeight = (LocalConfiguration.current.screenHeightDp - 120).dp

        fun run(shortcut: ReaderShortcut) {
            viewModel.shortcutChannel.send(shortcut)
            dismiss()
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.appearance_title).uppercase(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )

                if (editor is EpubPreferencesEditor && editor.layout != Layout.FIXED) {
                    ReadingChoices(editor, commit = { settings?.commit() })
                }

                ReaderLookSection(app.readerLook)
                Shortcuts(canUseAi = canUseAi, onShortcut = ::run)
            }
        }
    }

    private companion object {
        const val PANEL_WIDTH_DP = 340
    }
}

@Composable
private fun ReadingChoices(editor: EpubPreferencesEditor, commit: () -> Unit) {
    val fontSize = editor.fontSize
    OptionCard(R.string.appearance_text_size) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            StepButton("−") { fontSize.decrement(); commit() }
            Text(
                text = fontSize.formatValue(fontSize.value ?: fontSize.effectiveValue),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StepButton("+") { fontSize.increment(); commit() }
        }
    }

    val margins = editor.pageMargins
    val currentMargin = margins.value ?: margins.effectiveValue
    val marginOptions = listOf(
        MARGIN_NARROW to R.string.appearance_margins_narrow,
        MARGIN_NORMAL to R.string.appearance_margins_normal,
        MARGIN_WIDE to R.string.appearance_margins_wide,
    )
    val nearestMargin = marginOptions.minByOrNull { abs(it.first - currentMargin) }?.first
    OptionCard(R.string.appearance_margins) {
        DropdownField(
            options = marginOptions.map { it.first to stringResource(it.second) },
            selected = nearestMargin,
            onSelect = { margins.set(it); commit() },
        )
    }

    val theme = editor.theme
    OptionCard(R.string.appearance_paper) {
        DropdownField(
            options = listOf(
                Theme.LIGHT to stringResource(R.string.pref_theme_light),
                Theme.SEPIA to stringResource(R.string.pref_theme_sepia),
                Theme.DARK to stringResource(R.string.pref_theme_dark),
            ),
            selected = theme.effectiveValue,
            onSelect = { theme.set(it); commit() },
        )
    }

    val family = editor.fontFamily
    OptionCard(R.string.appearance_typeface) {
        DropdownField<FontFamily?>(
            options = listOf(
                null to stringResource(R.string.pref_original),
                FontFamily.LITERATA to stringResource(R.string.appearance_font_literata),
                FontFamily.SANS_SERIF to stringResource(R.string.pref_sans_serif),
            ),
            selected = family.value ?: family.effectiveValue,
            onSelect = { family.set(it); commit() },
        )
    }
}

@Composable
private fun Shortcuts(canUseAi: Boolean, onShortcut: (ReaderShortcut) -> Unit) {
    OptionCard(R.string.appearance_more) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShortcutRow(
                R.string.appearance_listen to ReaderShortcut.LISTEN,
                R.string.appearance_add_note to ReaderShortcut.ADD_NOTE,
                onShortcut = onShortcut,
            )
            if (canUseAi) {
                ShortcutRow(
                    R.string.ai_menu_chapter_summary to ReaderShortcut.SUMMARIZE_CHAPTER,
                    R.string.ai_menu_teacher to ReaderShortcut.TEACHER_GUIDE,
                    onShortcut = onShortcut,
                )
            }
            ShortcutRow(
                R.string.appearance_export_notes to ReaderShortcut.EXPORT_NOTES,
                R.string.appearance_stats to ReaderShortcut.STATS,
                onShortcut = onShortcut,
            )
        }
    }
    TextButton(
        onClick = { onShortcut(ReaderShortcut.MORE_SETTINGS) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.appearance_all_settings).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ShortcutRow(
    first: Pair<Int, ReaderShortcut>,
    second: Pair<Int, ReaderShortcut>,
    onShortcut: (ReaderShortcut) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(first, second).forEach { (label, shortcut) ->
            FilledTonalButton(
                onClick = { onShortcut(shortcut) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(label),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                )
            }
        }
    }
}

/** One option of the panel: a small caps caption over its control, inside a soft rounded card. */
@Composable
internal fun OptionCard(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(title).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

/** Round outlined − / + button used by the steppers. */
@Composable
internal fun StepButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier.size(40.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

/** A field that shows the chosen option and opens a menu with the others. */
@Composable
internal fun <T> DropdownField(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable { open = true },
        ) {
            Text(
                text = options.firstOrNull { it.first == selected }?.second.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { open = false; onSelect(value) },
                )
            }
        }
    }
}

private const val MARGIN_NARROW = 0.5
private const val MARGIN_NORMAL = 1.0
private const val MARGIN_WIDE = 1.75
