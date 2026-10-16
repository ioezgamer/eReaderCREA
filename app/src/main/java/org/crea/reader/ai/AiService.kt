package org.crea.reader.ai

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class BookInfo(
    val title: String,
    val author: String? = null,
    val summary: String,
    val categories: List<String> = emptyList(),
    val level: String = "general",
    val confidence: String = "medium",
)

@Serializable
data class TeacherQuestion(val type: String, val question: String, val answer_hint: String)

@Serializable
data class VocabularyItem(val word: String, val meaning: String)

@Serializable
data class TeacherGuide(
    val questions: List<TeacherQuestion> = emptyList(),
    val activities: List<String> = emptyList(),
    val vocabulary: List<VocabularyItem> = emptyList(),
)

enum class ExplainMode(val wire: String) { EXPLAIN("explain"), SIMPLIFY("simplify"), TRANSLATE("translate"), DEFINE("define") }

sealed class AiOutcome<out T> {
    data class Success<T>(val value: T) : AiOutcome<T>()
    data class Failure(val result: AiResult) : AiOutcome<Nothing>()
}

/**
 * Whether AI features exist right now. They are an extra: they need a configured server, a connected
 * Drive account, the user's consent (a setting) and a server that says it can still pay for them.
 * The moment any of that is missing - including when the credits run out - the buttons disappear and
 * everything else in the app works as before.
 */
class AiAvailability(
    context: Context,
    private val client: AiClient,
    private val driveConnected: () -> Boolean,
) {
    private val preferences = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val _userEnabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))
    val userEnabled: StateFlow<Boolean> = _userEnabled.asStateFlow()

    private val _readerEnabled = MutableStateFlow(preferences.getBoolean(KEY_READER, true))
    private val _catalogEnabled = MutableStateFlow(preferences.getBoolean(KEY_CATALOG, true))

    /** The person allows AI in the reader: explain, summarise, teacher guide. */
    val readerEnabled: StateFlow<Boolean> = _readerEnabled.asStateFlow()

    /** The person allows AI to prepare the catalog cards (summary, categories, level). */
    val catalogEnabled: StateFlow<Boolean> = _catalogEnabled.asStateFlow()

    private val _status = MutableStateFlow<AiStatus?>(null)

    /** What the server said the last time it was asked (null: never asked, or no answer). */
    val status: StateFlow<AiStatus?> = _status.asStateFlow()

    private val _available = MutableStateFlow(false)

    /** True when AI buttons may be shown. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var lastCheck = 0L
    private var lastOk = false

    val configured: Boolean get() = client.configured

    /** True when AI buttons may be shown in the reader. */
    val readerAvailable: Flow<Boolean> = combine(available, readerEnabled) { on, allowed -> on && allowed }

    /** The AI server needs the Google account that Drive uses to know who is asking. */
    fun isDriveConnected(): Boolean = driveConnected()

    fun readerOn(): Boolean = _available.value && _readerEnabled.value

    fun catalogOn(): Boolean = _available.value && _catalogEnabled.value

    fun setReaderEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_READER, enabled).apply()
        _readerEnabled.value = enabled
    }

    fun setCatalogEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CATALOG, enabled).apply()
        _catalogEnabled.value = enabled
    }

    /** Address used to reach the server (null when this build has none and none was typed). */
    fun activeServer(): String? = client.activeServer()

    /** Address typed in Ajustes, if any. */
    fun savedServer(): String? = client.savedServer()

    /** Uses [url] (already validated) from now on; null goes back to the address built into the app. */
    suspend fun changeServer(url: String?) {
        client.saveServer(url)
        _status.value = null
        refresh(force = true)
    }

    fun setUserEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _userEnabled.value = enabled
        if (!enabled) _available.value = false
    }

    /** Asks the server at most every few minutes (sooner after a failure); cheap to call often. */
    suspend fun refresh(force: Boolean = false) {
        if (!client.configured || !_userEnabled.value || !driveConnected()) {
            _available.value = false
            return
        }
        val age = System.currentTimeMillis() - lastCheck
        if (!force && age < (if (lastOk) OK_TTL_MS else FAILED_TTL_MS)) return
        lastCheck = System.currentTimeMillis()
        val status = client.status()
        _status.value = status
        lastOk = status?.available == true
        _available.value = lastOk
    }

    /** A request found the server unavailable (out of credits, switched off): hide AI until the next check. */
    fun markUnavailable() {
        lastOk = false
        lastCheck = System.currentTimeMillis()
        _available.value = false
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_READER = "reader_enabled"
        const val KEY_CATALOG = "catalog_enabled"
        const val OK_TTL_MS = 10 * 60 * 1000L
        const val FAILED_TTL_MS = 2 * 60 * 1000L
    }
}

class AiService(private val client: AiClient, val availability: AiAvailability) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun explain(
        mode: ExplainMode,
        text: String,
        context: String?,
        bookTitle: String?,
        targetLanguage: String? = null,
    ): AiOutcome<String> = text(
        "explain",
        AiClient.input(
            "mode" to mode.wire,
            "text" to text.take(3000),
            "context" to context?.take(1500),
            "bookTitle" to bookTitle?.take(200),
            "targetLanguage" to targetLanguage,
        )
    )

    suspend fun chapterSummary(text: String, bookTitle: String?, chapterTitle: String?): AiOutcome<String> = text(
        "chapter_summary",
        AiClient.input("text" to text.take(30000), "bookTitle" to bookTitle?.take(200), "chapterTitle" to chapterTitle?.take(200))
    )

    suspend fun teacherGuide(text: String, bookTitle: String?, chapterTitle: String?, grade: String?): AiOutcome<TeacherGuide> =
        structured(
            "teacher_questions",
            AiClient.input("text" to text.take(30000), "bookTitle" to bookTitle?.take(200), "chapterTitle" to chapterTitle?.take(200), "grade" to grade)
        ) { json.decodeFromString<TeacherGuide>(it) }

    suspend fun bookInfo(fileName: String, excerpt: String?): AiOutcome<BookInfo> =
        structured("book_info", AiClient.input("fileName" to fileName.take(300), "excerpt" to excerpt?.take(6000))) {
            json.decodeFromString<BookInfo>(it)
        }

    private suspend fun text(task: String, input: kotlinx.serialization.json.JsonObject): AiOutcome<String> =
        when (val result = ask(task, input)) {
            is AiResult.Ok -> runCatching { result.result.jsonPrimitive.content }.fold(
                onSuccess = { AiOutcome.Success(it) },
                onFailure = { AiOutcome.Failure(AiResult.Failed(AiResult.Kind.ERROR)) },
            )
            else -> AiOutcome.Failure(result)
        }

    private suspend fun <T> structured(
        task: String,
        input: kotlinx.serialization.json.JsonObject,
        parse: (String) -> T,
    ): AiOutcome<T> = when (val result = ask(task, input)) {
        is AiResult.Ok -> runCatching { parse(result.result.toString()) }.fold(
            onSuccess = { AiOutcome.Success(it) },
            onFailure = { AiOutcome.Failure(AiResult.Failed(AiResult.Kind.ERROR)) },
        )
        else -> AiOutcome.Failure(result)
    }

    private suspend fun ask(task: String, input: kotlinx.serialization.json.JsonObject): AiResult {
        val result = client.ask(task, input)
        if (result is AiResult.Unavailable) availability.markUnavailable()
        return result
    }
}

/** Plain-text version of a teacher guide, to read on screen, copy or share. */
object TeacherGuideFormatter {
    fun format(guide: TeacherGuide, labels: Labels): String = buildString {
        if (guide.questions.isNotEmpty()) {
            appendLine(labels.questions)
            guide.questions.forEachIndexed { index, q ->
                appendLine("${index + 1}. [${labels.type(q.type)}] ${q.question}")
                appendLine("   ${labels.hint}: ${q.answer_hint}")
            }
            appendLine()
        }
        if (guide.activities.isNotEmpty()) {
            appendLine(labels.activities)
            guide.activities.forEach { appendLine("• $it") }
            appendLine()
        }
        if (guide.vocabulary.isNotEmpty()) {
            appendLine(labels.vocabulary)
            guide.vocabulary.forEach { appendLine("• ${it.word}: ${it.meaning}") }
        }
    }.trim()

    class Labels(
        val questions: String,
        val activities: String,
        val vocabulary: String,
        val hint: String,
        val type: (String) -> String,
    )
}
