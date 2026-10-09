package org.crea.reader.sync

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What a person who chose to share their reading publishes in the shared Drive folder: a nickname
 * and, per book, how far they are on this device. No e-mail, account name or device name is included.
 */
@Serializable
data class CommunityProfile(
    val schema: Int = 1,
    val userId: String,
    val nickname: String = "",
    /** False after the person stops sharing: the file is kept but empty, so others stop seeing them. */
    val active: Boolean = true,
    val updatedAt: Long = 0,
    val readings: List<DeviceReading> = emptyList(),
)

/** Another person's profile as last downloaded, with the Drive version it came from. */
@Serializable
data class CommunityMember(
    val userId: String,
    val nickname: String,
    val fileId: String,
    val fileModified: String,
    val updatedAt: Long,
    val readings: List<DeviceReading>,
)

/**
 * Opt-in state for sharing reading with the other people of the Drive folder, and the local copy of
 * what they share. Sharing is off until the user turns it on.
 */
class CommunityStore(context: Context) {
    private val preferences = context.getSharedPreferences("community", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "community-members.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val membersSerializer = ListSerializer(CommunityMember.serializer())

    data class Settings(val enabled: Boolean, val nickname: String)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _members = MutableStateFlow(loadMembers())

    /** People who share, other than this user. Empty while sharing is off. */
    val members: StateFlow<List<CommunityMember>> = _members.asStateFlow()

    val enabled: Boolean get() = _settings.value.enabled
    val nickname: String get() = _settings.value.nickname

    /** True once this user's file was written with real content and not yet marked inactive. */
    var published: Boolean
        get() = preferences.getBoolean(KEY_PUBLISHED, false)
        set(value) = preferences.edit().putBoolean(KEY_PUBLISHED, value).apply()

    /** What was last uploaded, to skip uploads when nothing changed. */
    /** True once the old per-account file (from earlier versions) has been emptied. */
    var legacyCleaned: Boolean
        get() = preferences.getBoolean(KEY_LEGACY, false)
        set(value) = preferences.edit().putBoolean(KEY_LEGACY, value).apply()

    var lastPublishedKey: String?
        get() = preferences.getString(KEY_LAST_KEY, null)
        set(value) = preferences.edit().putString(KEY_LAST_KEY, value).apply()

    fun enable(nickname: String) {
        preferences.edit().putBoolean(KEY_ENABLED, true).putString(KEY_NICKNAME, nickname.trim()).apply()
        lastPublishedKey = null
        _settings.value = readSettings()
    }

    fun changeNickname(nickname: String) {
        preferences.edit().putString(KEY_NICKNAME, nickname.trim()).apply()
        lastPublishedKey = null
        _settings.value = readSettings()
    }

    fun disable() {
        preferences.edit().putBoolean(KEY_ENABLED, false).apply()
        _settings.value = readSettings()
        replaceMembers(emptyList())
    }

    fun replaceMembers(members: List<CommunityMember>) {
        if (members == _members.value) return
        runCatching { cacheFile.writeText(json.encodeToString(membersSerializer, members)) }
        _members.value = members
    }

    private fun readSettings() = Settings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        nickname = preferences.getString(KEY_NICKNAME, "").orEmpty(),
    )

    private fun loadMembers(): List<CommunityMember> =
        runCatching { json.decodeFromString(membersSerializer, cacheFile.readText()) }.getOrDefault(emptyList())

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_NICKNAME = "nickname"
        const val KEY_PUBLISHED = "published"
        const val KEY_LAST_KEY = "last_key"
        const val KEY_LEGACY = "legacy_cleaned"
    }
}
