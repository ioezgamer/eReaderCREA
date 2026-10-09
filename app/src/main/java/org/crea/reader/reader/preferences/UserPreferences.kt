/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(ExperimentalReadiumApi::class)

package org.crea.reader.reader.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.readium.adapter.exoplayer.audio.ExoPlayerPreferencesEditor
import org.readium.adapter.pdfium.navigator.PdfiumPreferencesEditor
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.r2.navigator.epub.EpubPreferencesEditor
import org.readium.r2.navigator.preferences.*
import org.readium.r2.navigator.preferences.TextAlign as ReadiumTextAlign
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.util.Language
import org.crea.reader.LITERATA
import org.crea.reader.R
import org.crea.reader.reader.ReaderViewModel
import org.crea.reader.reader.tts.TtsPreferencesEditor
import org.crea.reader.shared.views.*
import org.crea.reader.utils.compose.DropdownMenuButton

/**
 * Stateful user settings component paired with a [ReaderViewModel].
 */
@Composable
fun UserPreferences(
    model: UserPreferencesViewModel<*, *>,
    title: String,
) {
    val editor by model.editor.collectAsState()

    UserPreferences(
        editor = editor,
        commit = model::commit,
        title = title
    )
}

@Composable
private fun <P : Configurable.Preferences<P>, E : PreferencesEditor<P>> UserPreferences(
    editor: E,
    commit: () -> Unit,
    title: String,
) {
    Column(
        modifier = Modifier.padding(vertical = 24.dp)
    ) {
        Text(
            text = title,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PresetsMenuButton(presets = editor.presets, commit = commit, clear = editor::clear)

            Button(
                onClick = {
                    editor.clear()
                    commit()
                }
            ) {
                Text(stringResource(R.string.pref_reset))
            }
        }

        Divider()

        when (editor) {
            is PdfiumPreferencesEditor ->
                FixedLayoutUserPreferences(
                    commit = commit,
                    readingProgression = editor.readingProgression,
                    scroll = editor.scroll,
                    scrollAxis = editor.scrollAxis,
                    fit = editor.fit,
                    pageSpacing = editor.pageSpacing
                )

            is EpubPreferencesEditor ->
                when (editor.layout) {
                    Layout.REFLOWABLE, Layout.SCROLLED ->
                        ReflowableUserPreferences(
                            commit = commit,
                            backgroundColor = editor.backgroundColor,
                            columnCount = editor.columnCount,
                            fontFamily = editor.fontFamily,
                            fontSize = editor.fontSize,
                            fontWeight = editor.fontWeight,
                            hyphens = editor.hyphens,
                            imageFilter = editor.imageFilter,
                            language = editor.language,
                            letterSpacing = editor.letterSpacing,
                            ligatures = editor.ligatures,
                            lineHeight = editor.lineHeight,
                            pageMargins = editor.pageMargins,
                            paragraphIndent = editor.paragraphIndent,
                            paragraphSpacing = editor.paragraphSpacing,
                            publisherStyles = editor.publisherStyles,
                            readingProgression = editor.readingProgression,
                            scroll = editor.scroll,
                            textAlign = editor.textAlign,
                            textColor = editor.textColor,
                            textNormalization = editor.textNormalization,
                            theme = editor.theme,
                            typeScale = editor.typeScale,
                            verticalText = editor.verticalText,
                            wordSpacing = editor.wordSpacing
                        )
                    Layout.FIXED ->
                        FixedLayoutUserPreferences(
                            commit = commit,
                            backgroundColor = editor.backgroundColor,
                            language = editor.language,
                            readingProgression = editor.readingProgression,
                            spread = editor.spread
                        )
                }
            is TtsPreferencesEditor ->
                MediaUserPreferences(
                    commit = commit,
                    language = editor.language,
                    voice = editor.voice,
                    speed = editor.speed,
                    pitch = editor.pitch
                )
            is ExoPlayerPreferencesEditor ->
                MediaUserPreferences(
                    commit = commit,
                    speed = editor.speed,
                    pitch = editor.pitch
                )
        }
    }
}

@Composable
private fun MediaUserPreferences(
    commit: () -> Unit,
    language: Preference<Language?>? = null,
    voice: EnumPreference<AndroidTtsEngine.Voice.Id?>? = null,
    speed: RangePreference<Double>? = null,
    pitch: RangePreference<Double>? = null,
) {
    val lblDefault = stringResource(R.string.pref_default)
    Column {
        if (speed != null) {
            StepperItem(
                title = stringResource(R.string.speed_rate),
                preference = speed,
                commit = commit
            )
        }

        if (pitch != null) {
            StepperItem(
                title = stringResource(R.string.pitch_rate),
                preference = pitch,
                commit = commit
            )
        }
        if (language != null) {
            LanguageItem(
                preference = language,
                commit = commit
            )
        }

        if (voice != null) {
            MenuItem(
                title = stringResource(R.string.tts_voice),
                preference = voice,
                formatValue = { it?.value ?: lblDefault },
                commit = commit
            )
        }
    }
}

/**
 * User settings for a publication with a fixed layout, such as fixed-layout EPUB, PDF or comic book.
 */
@Composable
private fun FixedLayoutUserPreferences(
    commit: () -> Unit,
    language: Preference<Language?>? = null,
    readingProgression: EnumPreference<ReadingProgression>? = null,
    backgroundColor: Preference<Color>? = null,
    scroll: Preference<Boolean>? = null,
    scrollAxis: EnumPreference<Axis>? = null,
    fit: EnumPreference<Fit>? = null,
    spread: EnumPreference<Spread>? = null,
    offsetFirstPage: Preference<Boolean>? = null,
    pageSpacing: RangePreference<Double>? = null,
) {
    val lblHorizontal = stringResource(R.string.pref_horizontal)
    val lblVertical = stringResource(R.string.pref_vertical)
    val lblAuto = stringResource(R.string.pref_auto)
    val lblNever = stringResource(R.string.pref_never)
    val lblAlways = stringResource(R.string.pref_always)
    val lblContain = stringResource(R.string.pref_contain)
    val lblCover = stringResource(R.string.pref_cover)
    val lblWidth = stringResource(R.string.pref_width)
    val lblHeight = stringResource(R.string.pref_height)
    val lblLtr = stringResource(R.string.pref_ltr)
    val lblRtl = stringResource(R.string.pref_rtl)
    if (language != null || readingProgression != null) {
        if (language != null) {
            LanguageItem(
                preference = language,
                commit = commit
            )
        }

        if (readingProgression != null) {
            ButtonGroupItem(
                title = stringResource(R.string.pref_reading_progression),
                preference = readingProgression,
                commit = commit,
                formatValue = { if (it == ReadingProgression.RTL) lblRtl else lblLtr }
            )
        }

        Divider()
    }

    if (backgroundColor != null) {
        ColorItem(
            title = stringResource(R.string.pref_background_color),
            preference = backgroundColor,
            commit = commit
        )

        Divider()
    }

    if (scroll != null) {
        SwitchItem(
            title = stringResource(R.string.pref_scroll),
            preference = scroll,
            commit = commit
        )
    }

    if (scrollAxis != null) {
        ButtonGroupItem(
            title = stringResource(R.string.pref_scroll_axis),
            preference = scrollAxis,
            commit = commit
        ) { value ->
            when (value) {
                Axis.HORIZONTAL -> lblHorizontal
                Axis.VERTICAL -> lblVertical
            }
        }
    }

    if (spread != null) {
        ButtonGroupItem(
            title = stringResource(R.string.pref_spread),
            preference = spread,
            commit = commit
        ) { value ->
            when (value) {
                Spread.AUTO -> lblAuto
                Spread.NEVER -> lblNever
                Spread.ALWAYS -> lblAlways
            }
        }

        if (offsetFirstPage != null) {
            SwitchItem(
                title = stringResource(R.string.pref_offset),
                preference = offsetFirstPage,
                commit = commit
            )
        }
    }

    if (fit != null) {
        ButtonGroupItem(
            title = stringResource(R.string.pref_fit),
            preference = fit,
            commit = commit
        ) { value ->
            when (value) {
                Fit.CONTAIN -> lblContain
                Fit.COVER -> lblCover
                Fit.WIDTH -> lblWidth
                Fit.HEIGHT -> lblHeight
            }
        }
    }

    if (pageSpacing != null) {
        StepperItem(
            title = stringResource(R.string.pref_page_spacing),
            preference = pageSpacing,
            commit = commit
        )
    }
}

/**
 * User settings for a publication with adjustable fonts and dimensions, such as
 * a reflowable EPUB, HTML document or PDF with reflow mode enabled.
 */
@Composable
private fun ReflowableUserPreferences(
    commit: () -> Unit,
    backgroundColor: Preference<Color>? = null,
    columnCount: EnumPreference<ColumnCount>? = null,
    fontFamily: Preference<FontFamily?>? = null,
    fontSize: RangePreference<Double>? = null,
    fontWeight: RangePreference<Double>? = null,
    hyphens: Preference<Boolean>? = null,
    imageFilter: EnumPreference<ImageFilter?>? = null,
    language: Preference<Language?>? = null,
    letterSpacing: RangePreference<Double>? = null,
    ligatures: Preference<Boolean>? = null,
    lineHeight: RangePreference<Double>? = null,
    pageMargins: RangePreference<Double>? = null,
    paragraphIndent: RangePreference<Double>? = null,
    paragraphSpacing: RangePreference<Double>? = null,
    publisherStyles: Preference<Boolean>? = null,
    readingProgression: EnumPreference<ReadingProgression>? = null,
    scroll: Preference<Boolean>? = null,
    textAlign: EnumPreference<ReadiumTextAlign?>? = null,
    textColor: Preference<Color>? = null,
    textNormalization: Preference<Boolean>? = null,
    theme: EnumPreference<Theme>? = null,
    typeScale: RangePreference<Double>? = null,
    verticalText: Preference<Boolean>? = null,
    wordSpacing: RangePreference<Double>? = null,
) {
    val lblAuto = stringResource(R.string.pref_auto)
    val lblLight = stringResource(R.string.pref_theme_light)
    val lblDark = stringResource(R.string.pref_theme_dark)
    val lblSepia = stringResource(R.string.pref_theme_sepia)
    val lblDarken = stringResource(R.string.pref_darken)
    val lblInvert = stringResource(R.string.pref_invert)
    val lblNone = stringResource(R.string.pref_none)
    val lblOriginal = stringResource(R.string.pref_original)
    val lblSansSerif = stringResource(R.string.pref_sans_serif)
    val lblCenter = stringResource(R.string.pref_align_center)
    val lblJustify = stringResource(R.string.pref_align_justify)
    val lblStart = stringResource(R.string.pref_align_start)
    val lblEnd = stringResource(R.string.pref_align_end)
    val lblLeft = stringResource(R.string.pref_align_left)
    val lblRight = stringResource(R.string.pref_align_right)
    val lblDefault = stringResource(R.string.pref_default)
    val lblLtr = stringResource(R.string.pref_ltr)
    val lblRtl = stringResource(R.string.pref_rtl)
    if (language != null || readingProgression != null || verticalText != null) {
        if (language != null) {
            LanguageItem(
                preference = language,
                commit = commit
            )
        }

        if (readingProgression != null) {
            ButtonGroupItem(
                title = stringResource(R.string.pref_reading_progression),
                preference = readingProgression,
                commit = commit,
                formatValue = { if (it == ReadingProgression.RTL) lblRtl else lblLtr }
            )
        }

        if (verticalText != null) {
            SwitchItem(
                title = stringResource(R.string.pref_vertical_text),
                preference = verticalText,
                commit = commit
            )
        }

        Divider()
    }

    if (scroll != null || columnCount != null || pageMargins != null) {
        if (scroll != null) {
            SwitchItem(
                title = stringResource(R.string.pref_scroll),
                preference = scroll,
                commit = commit
            )
        }

        if (columnCount != null) {
            ButtonGroupItem(
                title = stringResource(R.string.pref_columns),
                preference = columnCount,
                commit = commit
            ) { value ->
                when (value) {
                    ColumnCount.AUTO -> lblAuto
                    ColumnCount.ONE -> "1"
                    ColumnCount.TWO -> "2"
                }
            }
        }

        if (pageMargins != null) {
            StepperItem(
                title = stringResource(R.string.pref_page_margins),
                preference = pageMargins,
                commit = commit
            )
        }

        Divider()
    }

    if (theme != null || textColor != null || imageFilter != null) {
        if (theme != null) {
            ButtonGroupItem(
                title = stringResource(R.string.pref_theme),
                preference = theme,
                commit = commit
            ) { value ->
                when (value) {
                    Theme.LIGHT -> lblLight
                    Theme.DARK -> lblDark
                    Theme.SEPIA -> lblSepia
                }
            }
        }

        if (imageFilter != null) {
            ButtonGroupItem(
                title = stringResource(R.string.pref_image_filter),
                preference = imageFilter,
                commit = commit
            ) { value ->
                when (value) {
                    ImageFilter.DARKEN -> lblDarken
                    ImageFilter.INVERT -> lblInvert
                    null -> lblNone
                }
            }
        }

        if (textColor != null) {
            ColorItem(
                title = stringResource(R.string.pref_text_color),
                preference = textColor,
                commit = commit
            )
        }

        if (backgroundColor != null) {
            ColorItem(
                title = stringResource(R.string.pref_background_color),
                preference = backgroundColor,
                commit = commit
            )
        }

        Divider()
    }

    if (fontFamily != null || fontSize != null || textNormalization != null) {
        if (fontFamily != null) {
            MenuItem(
                title = stringResource(R.string.pref_typeface),
                preference = fontFamily
                    .withSupportedValues(
                        null,
                        FontFamily.LITERATA,
                        FontFamily.SANS_SERIF,
                        FontFamily.IA_WRITER_DUOSPACE,
                        FontFamily.ACCESSIBLE_DFA,
                        FontFamily.OPEN_DYSLEXIC
                    ),
                commit = commit
            ) { value ->
                when (value) {
                    null -> lblOriginal
                    FontFamily.SANS_SERIF -> lblSansSerif
                    else -> value.name
                }
            }
        }

        if (fontSize != null) {
            StepperItem(
                title = stringResource(R.string.pref_font_size),
                preference = fontSize,
                commit = commit
            )
        }

        if (fontWeight != null) {
            StepperItem(
                title = stringResource(R.string.pref_font_weight),
                preference = fontWeight,
                commit = commit
            )
        }

        if (textNormalization != null) {
            SwitchItem(
                title = stringResource(R.string.pref_text_normalization),
                preference = textNormalization,
                commit = commit
            )
        }

        Divider()
    }

    if (publisherStyles != null) {
        SwitchItem(
            title = stringResource(R.string.pref_publisher_styles),
            preference = publisherStyles,
            commit = commit
        )

        if (!(publisherStyles.value ?: publisherStyles.effectiveValue)) {
            if (textAlign != null) {
                ButtonGroupItem(
                    title = stringResource(R.string.pref_alignment),
                    preference = textAlign,
                    commit = commit
                ) { value ->
                    when (value) {
                        ReadiumTextAlign.CENTER -> lblCenter
                        ReadiumTextAlign.JUSTIFY -> lblJustify
                        ReadiumTextAlign.START -> lblStart
                        ReadiumTextAlign.END -> lblEnd
                        ReadiumTextAlign.LEFT -> lblLeft
                        ReadiumTextAlign.RIGHT -> lblRight
                        null -> lblDefault
                    }
                }
            }

            if (typeScale != null) {
                StepperItem(
                    title = stringResource(R.string.pref_type_scale),
                    preference = typeScale,
                    commit = commit
                )
            }

            if (lineHeight != null) {
                StepperItem(
                    title = stringResource(R.string.pref_line_height),
                    preference = lineHeight,
                    commit = commit
                )
            }

            if (paragraphIndent != null) {
                StepperItem(
                    title = stringResource(R.string.pref_paragraph_indent),
                    preference = paragraphIndent,
                    commit = commit
                )
            }

            if (paragraphSpacing != null) {
                StepperItem(
                    title = stringResource(R.string.pref_paragraph_spacing),
                    preference = paragraphSpacing,
                    commit = commit
                )
            }

            if (wordSpacing != null) {
                StepperItem(
                    title = stringResource(R.string.pref_word_spacing),
                    preference = wordSpacing,
                    commit = commit
                )
            }

            if (letterSpacing != null) {
                StepperItem(
                    title = stringResource(R.string.pref_letter_spacing),
                    preference = letterSpacing,
                    commit = commit
                )
            }

            if (hyphens != null) {
                SwitchItem(
                    title = stringResource(R.string.pref_hyphens),
                    preference = hyphens,
                    commit = commit
                )
            }

            if (ligatures != null) {
                SwitchItem(
                    title = stringResource(R.string.pref_ligatures),
                    preference = ligatures,
                    commit = commit
                )
            }
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun PresetsMenuButton(
    presets: List<Preset>,
    clear: () -> Unit,
    commit: () -> Unit,
) {
    if (presets.isEmpty()) return

    DropdownMenuButton(
        text = { Text(stringResource(R.string.pref_presets)) }
    ) { dismiss ->

        for (preset in presets) {
            DropdownMenuItem(
                text = { Text(stringResource(preset.titleRes)) },
                onClick = {
                    dismiss()
                    clear()
                    preset.apply()
                    commit()
                }
            )
        }
    }
}

/**
 * A preset is a named group of settings applied together.
 */
class Preset(
    @StringRes val titleRes: Int,
    val apply: () -> Unit,
)

/**
 * Returns the presets associated with the [Configurable.Settings] receiver.
 */
val <P : Configurable.Preferences<P>> PreferencesEditor<P>.presets: List<Preset> get() =
    when (this) {
        is EpubPreferencesEditor ->
            when (layout) {
                Layout.FIXED -> emptyList()
                Layout.REFLOWABLE, Layout.SCROLLED -> listOf(
                    Preset(R.string.pref_preset_legibility) {
                        wordSpacing.set(0.6)
                        fontSize.set(1.4)
                        fontWeight.set(2.0)
                    },
                    Preset(R.string.pref_preset_document) {
                        scroll.set(true)
                    },
                    Preset(R.string.pref_preset_ebook) {
                        scroll.set(false)
                    },
                    Preset(R.string.pref_preset_manga) {
                        scroll.set(false)
                        readingProgression.set(ReadingProgression.RTL)
                    }
                )
            }
        else ->
            emptyList()
    }

