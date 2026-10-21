package org.crea.reader.reader.tts

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.crea.reader.ai.AiClient
import org.crea.reader.ai.VoiceOption
import org.crea.reader.ai.VoiceResult
import timber.log.Timber

/**
 * Natural voice (ElevenLabs, through the CREA server) for reading aloud.
 *
 * It is an extra on top of the device voice, never a replacement: when it is switched off, when
 * there is no connection or Drive account, or when the credits run out, [audioFor] returns null and
 * the reader keeps going with the device voice. After a failure it stays out of the way for a while
 * so that every sentence does not wait for a server that is not answering.
 */
class NaturalVoice(
    context: Context,
    private val client: AiClient,
    private val driveConnected: () -> Boolean,
) {

    /** What the server said about the natural voice the last time Ajustes asked. */
    enum class ServerState { UNKNOWN, CHECKING, AVAILABLE, UNAVAILABLE, UNREACHABLE }

    private val preferences = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val cacheDir = File(context.cacheDir, "natural-voice")

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))

    /** The person wants the natural voice (Ajustes). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _lastProblem = MutableStateFlow<String?>(null)

    /** Why the device voice is being used right now (null: the natural voice is in use or untried). */
    val lastProblem: StateFlow<String?> = _lastProblem.asStateFlow()

    private val _serverState = MutableStateFlow(ServerState.UNKNOWN)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _serverReason = MutableStateFlow<String?>(null)

    /** Why the server cannot read aloud (no_credits, misconfigured, voice_not_found, budget...). */
    val serverReason: StateFlow<String?> = _serverReason.asStateFlow()

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())

    /** Voices approved on the server, the main one first (empty until the server is asked). */
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private val _selectedVoice = MutableStateFlow(preferences.getString(KEY_VOICE, null))

    /** Voice chosen in Ajustes (null: the server's main voice). */
    val selectedVoice: StateFlow<String?> = _selectedVoice.asStateFlow()

    /** The voice that will actually be used: the chosen one if the server still offers it. */
    val currentVoice: VoiceOption?
        get() = _voices.value.firstOrNull { it.id == _selectedVoice.value } ?: _voices.value.firstOrNull()

    @Volatile
    private var pausedUntil = 0L

    val configured: Boolean get() = client.configured

    fun isDriveConnected(): Boolean = driveConnected()

    /** Asks the server whether it can read aloud now (for Ajustes). */
    suspend fun check() {
        if (!client.configured || !driveConnected()) return
        _serverState.value = ServerState.CHECKING
        val status = client.status()
        if (status != null) {
            _voices.value = status.voices
            _serverReason.value = status.voiceReason
        }
        _serverState.value = when {
            status == null -> ServerState.UNREACHABLE
            status.voiceAvailable -> ServerState.AVAILABLE.also { retryNow() }
            else -> ServerState.UNAVAILABLE
        }
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        if (enabled) retryNow()
    }

    /** Reads with [voiceId] from now on (null: the server's main voice). */
    fun selectVoice(voiceId: String?) {
        preferences.edit().apply { if (voiceId == null) remove(KEY_VOICE) else putString(KEY_VOICE, voiceId) }.apply()
        _selectedVoice.value = voiceId
        retryNow()
    }

    /** A short sample of the current voice, to try it in Ajustes (null if it cannot be heard now). */
    suspend fun sample(text: String): File? {
        retryNow()
        return audioFor(text, "es")
    }

    /** Forget a previous failure (for example after checking the server again in Ajustes). */
    fun retryNow() {
        pausedUntil = 0L
        _lastProblem.value = null
    }

    /** Whether it is worth asking the server for the next sentence. */
    fun shouldTry(): Boolean =
        _enabled.value && client.configured && driveConnected() && SystemClock.elapsedRealtime() >= pausedUntil

    /** MP3 file with [text] read aloud, or null to read it with the device voice. */
    suspend fun audioFor(text: String, language: String?): File? = withContext(Dispatchers.IO) {
        val voice = currentVoice?.id
        val file = File(cacheDir, key(text, language, voice) + ".mp3")
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }
        when (val result = client.speech(text, language, voice)) {
            is VoiceResult.Audio -> {
                cacheDir.mkdirs()
                val partial = File(cacheDir, file.name + ".part")
                partial.writeBytes(result.mp3)
                partial.renameTo(file)
                _lastProblem.value = null
                trimCache()
                file
            }
            is VoiceResult.Unavailable -> {
                _serverReason.value = result.reason
                _serverState.value = ServerState.UNAVAILABLE
                // Out of credits or over budget lasts; a key or voice to fix in Render, less.
                val long = result.reason in setOf("no_credits", "suspended", "budget")
                pause(if (long) LONG_PAUSE else MEDIUM_PAUSE, result.reason ?: "unavailable")
            }
            VoiceResult.LimitReached -> pause(LONG_PAUSE, "daily_limit")
            VoiceResult.Failed -> pause(SHORT_PAUSE, "no_connection")
        }
    }

    /** Called when an audio file could not be played: use the device voice for a little while. */
    fun playbackFailed() {
        pause(SHORT_PAUSE, "playback")
    }

    private fun pause(millis: Long, reason: String): File? {
        Timber.i("Natural voice paused for ${millis / 1000} s: $reason")
        pausedUntil = SystemClock.elapsedRealtime() + millis
        _lastProblem.value = reason
        return null
    }

    private fun key(text: String, language: String?, voice: String?): String {
        val server = client.activeServer().orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest("$server|$voice|$language|$text".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    /** Keeps the cache under [MAX_CACHE_BYTES], removing the files played longest ago. */
    private fun trimCache() {
        val files = cacheDir.listFiles { file -> file.extension == "mp3" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            total -= file.length()
            file.delete()
            if (total <= MAX_CACHE_BYTES) break
        }
    }

    companion object {
        private const val KEY_ENABLED = "natural_voice"
        private const val KEY_VOICE = "natural_voice_id"
        private const val SHORT_PAUSE = 2 * 60 * 1000L
        private const val MEDIUM_PAUSE = 10 * 60 * 1000L
        private const val LONG_PAUSE = 30 * 60 * 1000L
        private const val MAX_CACHE_BYTES = 60L * 1024 * 1024
    }
}
