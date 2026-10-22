package org.crea.reader.readers

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Reader profiles kept on this tablet, who is reading right now, and what they read.
 *
 * Two ways of working, chosen automatically:
 * - **Group tablet** (several tablets share the Google account): whoever holds the tablet may pick
 *   their nickname; until they do they read as a guest, and that reading moves into their profile
 *   if they pick it later. The session ends after [IDLE_MS] without use, at the end of the day or
 *   when they hand the tablet back.
 * - **Personal account**: one profile for the account, always active, nothing to choose.
 *
 * Everything here is optional: reading never waits for a profile.
 */
class ReaderStore(
    context: Context,
    /** Devices that use this Google account (from the last Drive sync); 0 when unknown. */
    private val accountDevices: () -> Int,
    /** E-mail of the Drive account, to name the personal profile the same on all its devices. */
    private val account: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences("readers", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "readers").apply { mkdirs() }
    private val guestFile = File(dir, "guest.json.local")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _profiles = MutableStateFlow(loadAll())

    /** Every profile known on this tablet (also those of other tablets, after a sync), not deleted. */
    val profiles: StateFlow<List<ReaderProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(preferences.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _guest = MutableStateFlow(loadGuest())

    /** What was read without picking a profile in this session (group tablets only, never uploaded). */
    val guest: StateFlow<ReaderProfile?> = _guest.asStateFlow()

    private val _modeOverride = MutableStateFlow(readOverride())

    /** null: decide by the number of devices; true/false: chosen in Ajustes. */
    val modeOverride: StateFlow<Boolean?> = _modeOverride.asStateFlow()

    // region Mode

    /** Several people use this tablet: offer the "who is reading?" choice. */
    val isGroupTablet: Boolean
        get() = _modeOverride.value ?: (accountDevices() >= GROUP_DEVICES)

    fun setModeOverride(group: Boolean?) {
        preferences.edit().apply {
            if (group == null) remove(KEY_MODE) else putBoolean(KEY_MODE, group)
        }.apply()
        _modeOverride.value = group
        if (group == false) endSession()
    }

    // endregion

    // region Session

    /** The profile reading right now: the chosen reader, or the account's profile on a personal tablet. */
    fun current(): ReaderProfile? {
        expireIfIdle()
        if (!isGroupTablet) return personalProfile()
        return _activeId.value?.let(::byId)
    }

    /** Where the reading is recorded: the current profile, or the guest record on a group tablet. */
    private fun target(): ReaderProfile? = current() ?: if (isGroupTablet) guestOrNew() else null

    fun byId(id: String): ReaderProfile? = _profiles.value.firstOrNull { it.id == id }

    /** Profiles most recently read on this tablet first, then the rest by most recent activity. */
    fun choices(): List<ReaderProfile> {
        val recentHere = recentHere()
        return _profiles.value
            .filter { !it.id.startsWith(PERSONAL_PREFIX) }
            .sortedWith(
                compareBy<ReaderProfile> { recentHere.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                    .thenByDescending { profile -> profile.history.maxOfOrNull { it.openedAt } ?: profile.createdAt }
            )
    }

    /** Ids picked on this tablet, the latest first. */
    fun recentHere(): List<String> =
        preferences.getString(KEY_RECENT, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    /** [id] starts reading; any guest reading of this session goes into their profile. */
    fun select(id: String) {
        val profile = byId(id) ?: return
        val guest = _guest.value
        if (guest != null && (guest.history.isNotEmpty() || guest.favorites.isNotEmpty())) {
            save(ReaderRules.adopt(profile, guest))
        }
        clearGuest()
        val recent = (listOf(id) + recentHere().filter { it != id }).take(MAX_RECENT_HERE)
        preferences.edit()
            .putString(KEY_ACTIVE, id)
            .putString(KEY_RECENT, recent.joinToString(","))
            .putLong(KEY_LAST_USE, clock())
            .putString(KEY_DAY, LocalDate.now().toString())
            .apply()
        _activeId.value = id
    }

    /** "Soy nuevo": creates the profile and starts reading with it. */
    fun create(nickname: String, avatar: Int, color: Int): ReaderProfile {
        val now = clock()
        val profile = ReaderProfile(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            nickname = nickname.trim().take(MAX_NICKNAME),
            avatar = avatar,
            color = color,
            createdAt = now,
            updatedAt = now,
        )
        save(profile)
        select(profile.id)
        return profile
    }

    /** Profiles whose nickname is the same as [nickname] (to warn before creating a second "Ana"). */
    fun sameNickname(nickname: String): List<ReaderProfile> {
        val wanted = ReaderRules.normalize(nickname)
        return _profiles.value.filter { ReaderRules.normalize(it.nickname) == wanted }
    }

    /** "Terminé, devolver la tablet": the next person starts fresh. */
    fun endSession() {
        preferences.edit().remove(KEY_ACTIVE).remove(KEY_LAST_USE).apply()
        _activeId.value = null
        clearGuest()
    }

    /** Called when the app comes back: a session left alone ends by itself. */
    fun expireIfIdle() {
        if (!isGroupTablet) return
        val lastUse = preferences.getLong(KEY_LAST_USE, 0)
        val day = preferences.getString(KEY_DAY, null)
        val active = _activeId.value != null || _guest.value != null
        if (active && (clock() - lastUse > IDLE_MS || day != LocalDate.now().toString())) endSession()
    }

    private fun touch() {
        preferences.edit().putLong(KEY_LAST_USE, clock()).putString(KEY_DAY, LocalDate.now().toString()).apply()
    }

    // endregion

    // region Recording

    /** Where the current reader stopped in [key], to open the book there (null: use this tablet's position). */
    fun progressionFor(key: String): String? =
        current()?.history?.firstOrNull { it.key == key }?.progression

    fun opened(book: BookRef) = record { ReaderRules.opened(it, book, clock()) }

    fun progressed(book: BookRef, progression: String, percent: Int) =
        record { ReaderRules.progressed(it, book, progression, percent, clock()) }

    fun setFavorite(book: BookRef, on: Boolean) = record { ReaderRules.favorite(it, book, on, clock()) }

    fun isFavorite(key: String): Boolean = ReaderRules.isFavorite(current(), key)

    private fun record(change: (ReaderProfile) -> ReaderProfile) {
        val profile = target() ?: return
        touch()
        val changed = change(profile)
        if (profile.id == GUEST_ID) saveGuest(changed) else save(changed)
    }

    // endregion

    // region Profile settings

    fun update(id: String, nickname: String? = null, avatar: Int? = null, color: Int? = null, hidden: Boolean? = null) {
        val profile = byId(id) ?: return
        save(
            profile.copy(
                nickname = nickname?.trim()?.take(MAX_NICKNAME)?.ifBlank { null } ?: profile.nickname,
                avatar = avatar ?: profile.avatar,
                color = color ?: profile.color,
                hidden = hidden ?: profile.hidden,
                updatedAt = clock(),
            )
        )
    }

    /** "Borrar mi historial": keeps the profile, forgets what was read. */
    fun clearHistory(id: String) {
        val profile = byId(id) ?: return
        save(profile.copy(history = emptyList(), favorites = emptyList(), updatedAt = clock()))
        markHistoryCleared(id)
    }

    /** Removes the profile here and, after the next sync, on every tablet. */
    fun delete(id: String) {
        val profile = byId(id) ?: return
        save(profile.copy(deleted = true, history = emptyList(), favorites = emptyList(), updatedAt = clock()))
        if (_activeId.value == id) endSession()
    }

    // endregion

    // region Sync support

    /** Profiles changed here since they were last uploaded. */
    fun dirtyIds(): Set<String> = preferences.getStringSet(KEY_DIRTY, emptySet()).orEmpty()

    fun markUploaded(id: String, driveVersion: String) {
        preferences.edit()
            .putStringSet(KEY_DIRTY, dirtyIds() - id)
            .putString(KEY_VERSION_PREFIX + id, driveVersion)
            .apply()
    }

    /** The Drive version of [id] this tablet last read or wrote (to skip downloading unchanged files). */
    fun knownVersion(id: String): String? = preferences.getString(KEY_VERSION_PREFIX + id, null)

    /** A profile came from Drive: joined with the local copy without losing anything. */
    fun mergeRemote(remote: ReaderProfile, driveVersion: String) {
        val local = loadFile(fileFor(remote.id))
        val cleared = historyClearedAt(remote.id)
        val incoming = if (cleared != null) remote.copy(history = remote.history.filter { it.openedAt > cleared }) else remote
        val merged = if (local == null) incoming else ReaderRules.merge(local, incoming)
        writeFile(merged)
        preferences.edit().putString(KEY_VERSION_PREFIX + remote.id, driveVersion).apply()
        if (local != null && merged != incoming) markDirty(merged.id)
        _profiles.value = loadAll()
        if (merged.deleted && _activeId.value == merged.id) endSession()
    }

    /** The full local copy (also deleted ones), for uploading. */
    fun localCopy(id: String): ReaderProfile? = loadFile(fileFor(id))

    // endregion

    // region Storage

    private fun personalProfile(): ReaderProfile {
        val id = PERSONAL_PREFIX + (account()?.let(::shortHash) ?: "local")
        byId(id)?.let { return it }
        val now = clock()
        val nickname = account()?.substringBefore('@')?.replaceFirstChar { it.uppercase() } ?: "Yo"
        val profile = ReaderProfile(id = id, nickname = nickname, avatar = 0, color = 0, createdAt = now, updatedAt = now)
        save(profile)
        return profile
    }

    private fun guestOrNew(): ReaderProfile =
        _guest.value ?: clock().let { ReaderProfile(id = GUEST_ID, nickname = "", createdAt = it, updatedAt = it) }

    private fun save(profile: ReaderProfile) {
        writeFile(profile)
        markDirty(profile.id)
        _profiles.value = loadAll()
    }

    private fun markDirty(id: String) {
        preferences.edit().putStringSet(KEY_DIRTY, dirtyIds() + id).apply()
    }

    private fun markHistoryCleared(id: String) {
        preferences.edit().putLong(KEY_CLEARED_PREFIX + id, clock()).apply()
    }

    private fun historyClearedAt(id: String): Long? =
        preferences.getLong(KEY_CLEARED_PREFIX + id, 0).takeIf { it > 0 }

    private fun saveGuest(profile: ReaderProfile) {
        runCatching { guestFile.writeText(json.encodeToString(ReaderProfile.serializer(), profile)) }
        _guest.value = profile
    }

    private fun clearGuest() {
        guestFile.delete()
        _guest.value = null
    }

    private fun loadGuest(): ReaderProfile? = loadFile(guestFile)

    private fun fileFor(id: String) = File(dir, "$id.json")

    private fun writeFile(profile: ReaderProfile) {
        val file = fileFor(profile.id)
        val partial = File(dir, "${profile.id}.json.part")
        partial.writeText(json.encodeToString(ReaderProfile.serializer(), profile))
        if (!partial.renameTo(file)) {
            file.delete()
            partial.renameTo(file)
        }
    }

    private fun loadFile(file: File): ReaderProfile? =
        runCatching { json.decodeFromString(ReaderProfile.serializer(), file.readText()) }.getOrNull()

    private fun loadAll(): List<ReaderProfile> =
        dir.listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull(::loadFile)
            .filter { !it.deleted }

    private fun readOverride(): Boolean? =
        if (preferences.contains(KEY_MODE)) preferences.getBoolean(KEY_MODE, false) else null

    private fun shortHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    // endregion

    companion object {
        /** From this many tablets on one account, it is a group account. */
        const val GROUP_DEVICES = 3
        const val IDLE_MS = 30 * 60 * 1000L
        const val MAX_NICKNAME = 24
        const val GUEST_ID = "guest"
        const val PERSONAL_PREFIX = "cuenta-"
        private const val MAX_RECENT_HERE = 12

        private const val KEY_ACTIVE = "active"
        private const val KEY_LAST_USE = "last_use"
        private const val KEY_DAY = "day"
        private const val KEY_RECENT = "recent_here"
        private const val KEY_MODE = "group_mode"
        private const val KEY_DIRTY = "dirty"
        private const val KEY_VERSION_PREFIX = "version_"
        private const val KEY_CLEARED_PREFIX = "cleared_"
    }
}
