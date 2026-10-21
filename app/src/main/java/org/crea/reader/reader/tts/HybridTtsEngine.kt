@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package org.crea.reader.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesEditor
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try
import timber.log.Timber

/**
 * Reads each sentence with the [NaturalVoice] when it can, and with the device voice otherwise.
 *
 * It wraps the usual Android engine and keeps its settings, voices and preferences, so the rest of
 * the reader does not change. A sentence that the natural voice cannot read (no connection, no
 * credits, an error while playing) is handed to the device voice right away, so reading never stops.
 */
class HybridTtsEngine(
    private val device: AndroidTtsEngine,
    private val natural: NaturalVoice,
) : TtsEngine<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice> {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listener: TtsEngine.Listener<AndroidTtsEngine.Error>? = null
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var playing: TtsEngine.RequestId? = null

    /** The sentence read just before, sent as context so the voice continues the reading's intonation. */
    private var lastSpoken: String? = null

    override val voices: Set<AndroidTtsEngine.Voice> get() = device.voices

    override val settings: StateFlow<AndroidTtsSettings> get() = device.settings

    override fun submitPreferences(preferences: AndroidTtsPreferences) {
        device.submitPreferences(preferences)
        player?.let { applySpeed(it) }
    }

    override fun setListener(listener: TtsEngine.Listener<AndroidTtsEngine.Error>?) {
        this.listener = listener
        device.setListener(listener)
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        val previous = lastSpoken
        lastSpoken = text
        if (!natural.shouldTry() || text.isBlank()) {
            device.speak(requestId, text, language)
            return
        }
        job = scope.launch {
            val file = natural.audioFor(text, language?.code?.substringBefore('-')?.lowercase(), previous)
            if (!isActive) return@launch
            if (file == null) {
                device.speak(requestId, text, language)
                return@launch
            }
            play(requestId, file.path, text, language)
        }
    }

    private fun play(requestId: TtsEngine.RequestId, path: String, text: String, language: Language?) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        playing = requestId
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mediaPlayer.setDataSource(path)
            mediaPlayer.setOnCompletionListener {
                releasePlayer()
                listener?.onDone(requestId)
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Timber.w("Natural voice could not play the audio ($what, $extra)")
                releasePlayer()
                natural.playbackFailed()
                device.speak(requestId, text, language)
                true
            }
            mediaPlayer.prepare()
            applySpeed(mediaPlayer)
            listener?.onStart(requestId)
            mediaPlayer.start()
        } catch (e: Exception) {
            Timber.w(e, "Natural voice could not play the audio")
            releasePlayer()
            natural.playbackFailed()
            device.speak(requestId, text, language)
        }
    }

    private fun applySpeed(mediaPlayer: MediaPlayer) {
        runCatching {
            mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(device.settings.value.speed.toFloat())
        }
    }

    override fun stop() {
        // After a pause or a jump the next sentence does not follow the last one.
        lastSpoken = null
        job?.cancel()
        job = null
        val interrupted = playing
        releasePlayer()
        device.stop()
        if (interrupted != null) listener?.onInterrupted(interrupted)
    }

    private fun releasePlayer() {
        player?.let { mediaPlayer ->
            runCatching { mediaPlayer.stop() }
            mediaPlayer.release()
        }
        player = null
        playing = null
    }

    override fun close() {
        stop()
        scope.cancel()
        device.close()
    }
}

/** Creates [HybridTtsEngine]s; everything else (preferences, voices, speed) is the Android engine's. */
class HybridTtsEngineProvider(
    context: Context,
    private val natural: NaturalVoice,
    private val device: AndroidTtsEngineProvider = AndroidTtsEngineProvider(context),
) : TtsEngineProvider<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsPreferencesEditor, AndroidTtsEngine.Error, AndroidTtsEngine.Voice> {

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: AndroidTtsPreferences,
    ): Try<TtsEngine<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>, org.readium.r2.shared.util.Error> =
        device.createEngine(publication, initialPreferences).map { HybridTtsEngine(it, natural) }

    override fun createPreferencesEditor(publication: Publication, initialPreferences: AndroidTtsPreferences): AndroidTtsPreferencesEditor =
        device.createPreferencesEditor(publication, initialPreferences)

    override fun createEmptyPreferences(): AndroidTtsPreferences = device.createEmptyPreferences()

    override fun getPlaybackParameters(settings: AndroidTtsSettings): PlaybackParameters = device.getPlaybackParameters(settings)

    override fun updatePlaybackParameters(preferences: AndroidTtsPreferences, playbackParameters: PlaybackParameters): AndroidTtsPreferences =
        device.updatePlaybackParameters(preferences, playbackParameters)

    override fun mapEngineError(error: AndroidTtsEngine.Error): PlaybackException = device.mapEngineError(error)
}
