package org.crea.reader.ai

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.crea.reader.BuildConfig
import org.crea.reader.sync.DriveAuthorizationProvider

sealed class AiResult {
    data class Ok(val result: JsonElement) : AiResult()

    /** The server is switched off, out of credits or over budget: AI features should disappear quietly. */
    data class Unavailable(val reason: String?) : AiResult()

    /** Daily limit for this device and feature. */
    data object LimitReached : AiResult()

    data class Failed(val kind: Kind) : AiResult()

    enum class Kind { NO_CONNECTION, SIGN_IN, BUSY, REFUSED, ERROR }
}

data class AiStatus(
    val available: Boolean,
    val reason: String?,
    val remaining: Map<String, Int>,
    /** Natural voice (ElevenLabs): whether the server can read aloud right now, and why not. */
    val voiceAvailable: Boolean = false,
    val voiceReason: String? = null,
    /** Voices the server allows choosing, the main one first. */
    val voices: List<VoiceOption> = emptyList(),
    val defaultVoice: String? = null,
)

/** A natural voice approved on the server (Render, ELEVENLABS_VOICES). */
data class VoiceOption(val id: String, val name: String)

sealed class VoiceResult {
    class Audio(val mp3: ByteArray) : VoiceResult()

    /** Switched off, out of credits or over budget: read with the device voice for a good while. */
    data class Unavailable(val reason: String?) : VoiceResult()

    /** Daily limit of this device. */
    data object LimitReached : VoiceResult()

    /** No connection, no Drive account, busy or any other error: try again later. */
    data object Failed : VoiceResult()
}

/**
 * Talks to the CREA Reader AI server, which holds the Anthropic key. The app only sends the content
 * to work on (never instructions) and its Google token, which the server uses to accept only
 * accounts of the organisation. Nothing here ever needs the Anthropic key.
 */
class AiClient(
    private val context: Context,
    private val deviceId: () -> String,
    private val builtInBaseUrl: String = BuildConfig.AI_BASE_URL,
) {
    private val settings = context.getSharedPreferences("ai", Context.MODE_PRIVATE)

    /** The address chosen in Ajustes, or the one this build was made with. */
    private val baseUrl: String get() = savedServer() ?: builtInBaseUrl

    val configured: Boolean get() = baseUrl.isNotBlank()

    /** Address typed in Ajustes (already validated), if any. */
    fun savedServer(): String? = settings.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }

    /** Address of the server in use, if there is one. */
    fun activeServer(): String? = baseUrl.takeIf { it.isNotBlank() }

    /** Saves [url] (null removes it, going back to the one built into the app). */
    fun saveServer(url: String?) {
        settings.edit().apply { if (url == null) remove(KEY_SERVER) else putString(KEY_SERVER, url) }.apply()
    }

    suspend fun status(): AiStatus? {
        val response = call("GET", "/v1/status", null)
        if (response !is Raw.Success) return null
        val body = response.body
        return AiStatus(
            available = body["available"]?.jsonPrimitive?.booleanOrNull == true,
            reason = body["reason"]?.jsonPrimitive?.contentOrNull,
            remaining = (body["remaining"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.int }.orEmpty(),
            voiceAvailable = (body["voice"] as? JsonObject)?.get("available")?.jsonPrimitive?.booleanOrNull == true,
            voiceReason = (body["voice"] as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull,
            voices = ((body["voice"] as? JsonObject)?.get("voices") as? JsonArray).orEmpty().mapNotNull { item ->
                val voice = item as? JsonObject ?: return@mapNotNull null
                val id = voice["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                VoiceOption(id, voice["name"]?.jsonPrimitive?.contentOrNull ?: id)
            },
            defaultVoice = (body["voice"] as? JsonObject)?.get("defaultVoice")?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun ask(task: String, input: JsonObject): AiResult {
        val payload = buildJsonObject {
            put("task", task)
            put("input", input)
        }
        return when (val response = call("POST", "/v1/ask", payload.toString())) {
            is Raw.Success -> response.body["result"]?.let { AiResult.Ok(it) } ?: AiResult.Failed(AiResult.Kind.ERROR)
            is Raw.Error -> when {
                response.status == 503 && response.code == "unavailable" -> AiResult.Unavailable(response.reason)
                response.status == 429 && response.code == "daily_limit" -> AiResult.LimitReached
                response.status == 429 -> AiResult.Failed(AiResult.Kind.BUSY)
                response.status == 422 -> AiResult.Failed(AiResult.Kind.REFUSED)
                response.status == 401 -> AiResult.Failed(AiResult.Kind.SIGN_IN)
                response.status == 0 -> AiResult.Failed(AiResult.Kind.NO_CONNECTION)
                else -> AiResult.Failed(AiResult.Kind.ERROR)
            }
        }
    }

    /**
     * Audio (MP3) of [text] read by the natural voice. Short timeouts: when the voice is slow or
     * missing, reading goes on with the device voice instead of leaving a silence.
     */
    suspend fun speech(text: String, language: String?, voice: String? = null): VoiceResult = withContext(Dispatchers.IO) {
        if (!configured) return@withContext VoiceResult.Unavailable("not_configured")
        val token = accessToken() ?: return@withContext VoiceResult.Unavailable("needs_drive")
        val payload = buildJsonObject {
            put("text", text)
            if (language != null) put("language", language)
            if (voice != null) put("voice", voice)
        }
        val connection = (URI(baseUrl.trimEnd('/') + "/v1/voice").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-Device-Id", deviceId())
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "audio/mpeg")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 200) return@withContext VoiceResult.Audio(connection.inputStream.use { it.readBytes() })
            val json = connection.errorStream?.bufferedReader()?.use { it.readText() }
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            val code = json?.get("code")?.jsonPrimitive?.contentOrNull
            when {
                status == 503 && code == "unavailable" -> VoiceResult.Unavailable(json.get("reason")?.jsonPrimitive?.contentOrNull)
                status == 429 && code == "daily_limit" -> VoiceResult.LimitReached
                else -> VoiceResult.Failed
            }
        } catch (_: IOException) {
            VoiceResult.Failed
        } finally {
            connection.disconnect()
        }
    }

    /** The Google token Drive already has (the server uses it to check the account), or null. */
    private suspend fun accessToken(): String? = try {
        when (val auth = DriveAuthorizationProvider(context).requestToken()) {
            is DriveAuthorizationProvider.TokenResult.Authorized -> auth.accessToken
            is DriveAuthorizationProvider.TokenResult.ConsentRequired -> null
        }
    } catch (_: Exception) {
        null
    }

    private sealed class Raw {
        class Success(val body: JsonObject) : Raw()
        class Error(val status: Int, val code: String?, val reason: String?) : Raw()
    }

    private suspend fun call(method: String, path: String, body: String?): Raw = withContext(Dispatchers.IO) {
        if (!configured) return@withContext Raw.Error(0, "not_configured", null)
        val token = try {
            when (val auth = DriveAuthorizationProvider(context).requestToken()) {
                is DriveAuthorizationProvider.TokenResult.Authorized -> auth.accessToken
                is DriveAuthorizationProvider.TokenResult.ConsentRequired -> return@withContext Raw.Error(401, "unauthorized", null)
            }
        } catch (_: Exception) {
            return@withContext Raw.Error(0, null, null)
        }
        val connection = (URI(baseUrl.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-Device-Id", deviceId())
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (status in 200..299 && json != null) Raw.Success(json)
            else Raw.Error(
                status,
                json?.get("code")?.jsonPrimitive?.contentOrNull,
                json?.get("reason")?.jsonPrimitive?.contentOrNull,
            )
        } catch (_: IOException) {
            Raw.Error(0, null, null)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val KEY_SERVER = "server_url"

        fun input(vararg pairs: Pair<String, String?>): JsonObject = buildJsonObject {
            for ((key, value) in pairs) if (value != null) put(key, JsonPrimitive(value))
        }
    }
}
