@file:OptIn(ExperimentalReadiumApi::class)

package org.crea.reader.reader.preferences

import android.app.Dialog
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.abs
import org.crea.reader.Application
import org.crea.reader.LITERATA
import org.crea.reader.R
import org.crea.reader.reader.ReaderViewModel
import org.crea.reader.reader.ReaderViewModel.ReaderShortcut
import org.crea.reader.utils.compose.ComposeBottomSheetDialogFragment
import org.readium.r2.navigator.epub.EpubPreferencesEditor
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.allAreHtml

/**
 * The reader's "Aspecto" sheet: the few things people change most (text size, margins, paper,
 * typeface, brightness, bar size) and quick actions. The full Readium panel is one tap away.
 */
class AppearanceBottomSheetDialogFragment : ComposeBottomSheetDialogFragment(isScrollable = true) {

    private val viewModel: ReaderViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            // Reduce the dim to see the impact of the settings on the page.
            window?.setDimAmount(0.1f)
            // Open fully so the quick actions at the bottom are not hidden.
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

    @Composable
    override fun Content() {
        val app = LocalContext.current.applicationContext as Application
        val settings = viewModel.settings
        val editor = settings?.editor?.collectAsState()?.value
        val aiAvailable by app.aiService.availability.available.collectAsState()
        val canUseAi = aiAvailable && viewModel.publication.readingOrder.allAreHtml

        fun run(shortcut: ReaderShortcut) {
            viewModel.shortcutChannel.send(shortcut)
            dismiss()
        }

        Column(modifier = Modifier.navigationBarsPadding().padding(vertical = 24.dp)) {
            Text(
                text = stringResource(R.string.appearance_title),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )

            if (editor is EpubPreferencesEditor && editor.layout != Layout.FIXED) {
                ReadingChoices(editor, commit = { settings?.commit() })
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }

            ReaderLookSection(app.readerLook)
            HorizontalDivider()

            Shortcuts(canUseAi = canUseAi, onShortcut = ::run)
        }
    }
}

@Composable
private fun ReadingChoices(editor: EpubPreferencesEditor, commit: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        val fontSize = editor.fontSize
        SectionTitle(R.string.appearance_text_size)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            FilledTonalButton(onClick = { fontSize.decrement(); commit() }, modifier = Modifier.weight(1f)) {
                Text("A−", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = fontSize.formatValue(fontSize.value ?: fontSize.effectiveValue),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = { fontSize.increment(); commit() }, modifier = Modifier.weight(1f)) {
                Text("A+", style = MaterialTheme.typography.headlineSmall)
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
        SectionTitle(R.string.appearance_margins, Modifier.padding(top = 16.dp))
        ChipRow {
            marginOptions.forEach { (value, label) ->
                FilterChip(
                    selected = value == nearestMargin,
                    onClick = { margins.set(value); commit() },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val theme = editor.theme
        SectionTitle(R.string.appearance_paper, Modifier.padding(top = 16.dp))
        ChipRow {
            listOf(
                Theme.LIGHT to R.string.pref_theme_light,
                Theme.SEPIA to R.string.pref_theme_sepia,
                Theme.DARK to R.string.pref_theme_dark,
            ).forEach { (value, label) ->
                FilterChip(
                    selected = theme.effectiveValue == value,
                    onClick = { theme.set(value); commit() },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val family = editor.fontFamily
        SectionTitle(R.string.appearance_typeface, Modifier.padding(top = 16.dp))
        ChipRow {
            listOf<Pair<FontFamily?, Int>>(
                null to R.string.pref_original,
                FontFamily.LITERATA to R.string.appearance_font_literata,
                FontFamily.SANS_SERIF to R.string.pref_sans_serif,
            ).forEach { (value, label) ->
                FilterChip(
                    selected = (family.value ?: family.effectiveValue) == value,
                    onClick = { family.set(value); commit() },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Shortcuts(canUseAi: Boolean, onShortcut: (ReaderShortcut) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SectionTitle(R.string.appearance_more)
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
        TextButton(
            onClick = { onShortcut(ReaderShortcut.MORE_SETTINGS) },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.appearance_all_settings))
        }
    }
}

@Composable
private fun ShortcutRow(
    first: Pair<Int, ReaderShortcut>,
    second: Pair<Int, ReaderShortcut>,
    onShortcut: (ReaderShortcut) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(first, second).forEach { (label, shortcut) ->
            Button(onClick = { onShortcut(shortcut) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(label), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

private const val MARGIN_NARROW = 0.5
private const val MARGIN_NORMAL = 1.0
private const val MARGIN_WIDE = 1.75
