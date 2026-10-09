/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.reader.tts

import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesEditor
import org.readium.r2.navigator.preferences.*
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language

@OptIn(ExperimentalReadiumApi::class)
class TtsPreferencesEditor(
    private val editor: AndroidTtsPreferencesEditor,
    private val availableVoices: Set<AndroidTtsEngine.Voice>,
) : PreferencesEditor<AndroidTtsPreferences> {

    private val rankedVoices: List<AndroidTtsEngine.Voice> =
        rankVoices(availableVoices.filter { it.language.removeRegion() == editor.language.effectiveValue?.removeRegion() })

    override val preferences: AndroidTtsPreferences
        get() = editor.preferences

    override fun clear() {
        editor.clear()
    }

    val language: Preference<Language?> =
        editor.language

    val pitch: RangePreference<Double> =
        editor.pitch

    val speed: RangePreference<Double> =
        editor.speed

    /**
     * [AndroidTtsPreferencesEditor] supports choosing voices for any language or region.
     * For this test app, we've chosen to present to the user only the voice for the
     * TTS default language and to ignore regions.
     */
    val voice: EnumPreference<AndroidTtsEngine.Voice.Id?> = run {
        val currentLanguage = language.effectiveValue?.removeRegion()

        editor.voices.map(
            from = { voices ->
                currentLanguage?.let { voices[it] }
            },
            to = { voice ->
                currentLanguage
                    ?.let { editor.voices.value.orEmpty().update(it, voice) }
                    ?: editor.voices.value.orEmpty()
            }
        ).withSupportedValues(rankedVoices.map { it.id })
    }

    /** Número de orden (1 = la mejor) y datos de cada voz, para mostrar nombres comprensibles en vez de códigos. */
    val voiceInfo: Map<AndroidTtsEngine.Voice.Id, VoiceInfo> =
        rankedVoices.mapIndexed { index, voice ->
            voice.id to VoiceInfo(
                number = index + 1,
                quality = voice.quality,
                requiresNetwork = voice.requiresNetwork,
                language = voice.language.code.substringBefore('-'),
                region = voice.language.code.substringAfter('-', ""),
            )
        }.toMap()

    class VoiceInfo(
        val number: Int,
        val quality: AndroidTtsEngine.Voice.Quality,
        val requiresNetwork: Boolean,
        val language: String,
        val region: String,
    )

    private fun <K, V> Map<K, V>.update(key: K, value: V?): Map<K, V> =
        buildMap {
            putAll(this@update)
            if (value == null) {
                remove(key)
            } else {
                put(key, value)
            }
        }

    companion object {
        /**
         * Mejor calidad primero; a igual calidad, primero las que funcionan sin Internet y, entre ellas,
         * el español de Latinoamérica antes que el de España (más cercano al de la comunidad lectora).
         */
        fun rankVoices(voices: Collection<AndroidTtsEngine.Voice>): List<AndroidTtsEngine.Voice> =
            voices.sortedWith(
                compareByDescending<AndroidTtsEngine.Voice> { it.quality.ordinal }
                    .thenBy { it.requiresNetwork }
                    .thenBy { it.language.code.equals("es-ES", ignoreCase = true) }
                    .thenBy { it.id.value }
            )

        /** La voz que se usa si la persona no eligió ninguna: la de mayor calidad que no necesita Internet. */
        fun bestOfflineVoice(voices: Collection<AndroidTtsEngine.Voice>, language: Language): AndroidTtsEngine.Voice? =
            rankVoices(voices.filter { it.language.removeRegion() == language.removeRegion() && !it.requiresNetwork })
                .firstOrNull()
    }
}
