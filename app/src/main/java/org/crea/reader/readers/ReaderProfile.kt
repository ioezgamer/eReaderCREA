package org.crea.reader.readers

import kotlinx.serialization.Serializable

/**
 * A reader's profile: what they read and liked, independent of the books stored on any tablet.
 *
 * On group tablets (many tablets sharing one Google account, people taking whichever tablet they
 * are given) each person picks their nickname and their profile follows them to any tablet through
 * the shared Drive folder. On a personal account there is a single profile for the account.
 * Only a nickname and an avatar are kept: no names, e-mails or photos.
 */
@Serializable
data class ReaderProfile(
    val schema: Int = 1,
    val id: String,
    val nickname: String,
    /** Index in [ReaderAvatars.EMOJIS]. */
    val avatar: Int = 0,
    /** Index in [ReaderAvatars.COLORS]. */
    val color: Int = 0,
    val createdAt: Long,
    /** Last change of nickname, avatar, privacy or deletion (not of the reading itself). */
    val updatedAt: Long,
    /** The person asked not to appear in "what my group reads". */
    val hidden: Boolean = false,
    /** Reading group ("Club martes"), chosen by the reader; null when they have none. */
    val group: String? = null,
    /** Days with reading (ISO dates, most recent last), for streaks and achievements. */
    val days: List<String> = emptyList(),
    /** Removed: kept as a marker so other tablets drop it too. */
    val deleted: Boolean = false,
    val history: List<ReadEntry> = emptyList(),
    val favorites: List<FavoriteMark> = emptyList(),
)

/** One book this reader opened. [key] is "drive:<id>" for Drive books, "local:<hash>" otherwise. */
@Serializable
data class ReadEntry(
    val key: String,
    val title: String,
    val author: String? = null,
    val driveFileId: String? = null,
    val percent: Int = 0,
    /** Readium locator (JSON) where this reader stopped, to continue there on any tablet. */
    val progression: String? = null,
    val openedAt: Long,
    val progressAt: Long = 0,
    val finishedAt: Long? = null,
)

/** Favourite on/off with the time it changed, so the latest change wins on every tablet. */
@Serializable
data class FavoriteMark(
    val key: String,
    val on: Boolean,
    val at: Long,
    val title: String = "",
    val author: String? = null,
    val driveFileId: String? = null,
)

/** Data of a book needed to show it in a profile without opening it. */
data class BookRef(
    val key: String,
    val title: String,
    val author: String?,
    val driveFileId: String?,
)

object ReaderRules {
    /** Read up to here counts as finished. */
    const val FINISHED_PERCENT = 95

    /** Most books a profile remembers (the oldest are forgotten first). */
    const val MAX_HISTORY = 300

    /** Most reading days a profile remembers. */
    const val MAX_DAYS = 400

    fun keyFor(driveFileId: String?, contentHash: String?): String? = when {
        !driveFileId.isNullOrBlank() -> "drive:$driveFileId"
        !contentHash.isNullOrBlank() -> "local:$contentHash"
        else -> null
    }

    fun opened(profile: ReaderProfile, book: BookRef, now: Long): ReaderProfile {
        val current = profile.history.firstOrNull { it.key == book.key }
        val entry = current?.copy(
            title = book.title.ifBlank { current.title },
            author = book.author ?: current.author,
            driveFileId = book.driveFileId ?: current.driveFileId,
            openedAt = now,
        ) ?: ReadEntry(book.key, book.title, book.author, book.driveFileId, openedAt = now)
        return profile.copy(history = upsert(profile.history, entry))
    }

    fun progressed(profile: ReaderProfile, book: BookRef, progression: String, percent: Int, now: Long): ReaderProfile {
        val current = profile.history.firstOrNull { it.key == book.key }
            ?: ReadEntry(book.key, book.title, book.author, book.driveFileId, openedAt = now)
        val finished = current.finishedAt ?: now.takeIf { percent >= FINISHED_PERCENT }
        val entry = current.copy(
            percent = percent.coerceIn(0, 100),
            progression = progression,
            progressAt = now,
            openedAt = maxOf(current.openedAt, now),
            finishedAt = finished,
        )
        return profile.copy(history = upsert(profile.history, entry), days = withDay(profile.days, today(now)))
    }

    fun favorite(profile: ReaderProfile, book: BookRef, on: Boolean, now: Long): ReaderProfile {
        val mark = FavoriteMark(book.key, on, now, book.title, book.author, book.driveFileId)
        return profile.copy(favorites = profile.favorites.filter { it.key != book.key } + mark)
    }

    fun isFavorite(profile: ReaderProfile?, key: String): Boolean =
        profile?.favorites?.lastOrNull { it.key == key }?.on == true

    /**
     * Joins two copies of the same profile (this tablet's and Drive's). Nothing read on either is
     * lost: per book the latest position wins, finishing is kept, and per favourite the latest change.
     */
    fun merge(a: ReaderProfile, b: ReaderProfile): ReaderProfile {
        val newer = if (b.updatedAt > a.updatedAt) b else a
        val history = (a.history + b.history).groupBy { it.key }.map { (_, entries) ->
            entries.reduce { x, y -> mergeEntry(x, y) }
        }
        val favorites = (a.favorites + b.favorites).groupBy { it.key }.map { (_, marks) -> marks.maxBy { it.at } }
        return newer.copy(
            createdAt = minOf(a.createdAt, b.createdAt),
            deleted = a.deleted || b.deleted,
            history = trim(history),
            favorites = favorites.sortedBy { it.at },
            days = (a.days + b.days).distinct().sorted().takeLast(MAX_DAYS),
        )
    }

    /** Guest reading moves into a profile chosen afterwards. */
    fun adopt(profile: ReaderProfile, guest: ReaderProfile): ReaderProfile =
        merge(profile, guest.copy(id = profile.id, updatedAt = 0))

    private fun mergeEntry(x: ReadEntry, y: ReadEntry): ReadEntry {
        val position = if (y.progressAt > x.progressAt) y else x
        return position.copy(
            title = position.title.ifBlank { x.title.ifBlank { y.title } },
            author = position.author ?: x.author ?: y.author,
            driveFileId = position.driveFileId ?: x.driveFileId ?: y.driveFileId,
            openedAt = maxOf(x.openedAt, y.openedAt),
            finishedAt = listOfNotNull(x.finishedAt, y.finishedAt).minOrNull(),
        )
    }

    private fun withDay(days: List<String>, day: String): List<String> =
        if (days.lastOrNull() == day) days else (days + day).distinct().sorted().takeLast(MAX_DAYS)

    /** The local date of [millis] as "2026-10-22". */
    fun today(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    private fun upsert(history: List<ReadEntry>, entry: ReadEntry): List<ReadEntry> =
        trim(history.filter { it.key != entry.key } + entry)

    private fun trim(history: List<ReadEntry>): List<ReadEntry> =
        history.sortedByDescending { it.openedAt }.take(MAX_HISTORY)

    /** "Seguir leyendo": started, not finished, the last opened first. */
    fun continueReading(profile: ReaderProfile?): List<ReadEntry> =
        profile?.history.orEmpty().filter { it.finishedAt == null && it.percent < FINISHED_PERCENT }
            .sortedByDescending { it.openedAt }

    fun recent(profile: ReaderProfile?): List<ReadEntry> =
        profile?.history.orEmpty().sortedByDescending { it.openedAt }

    fun finished(profile: ReaderProfile?): List<ReadEntry> =
        profile?.history.orEmpty().filter { it.finishedAt != null }.sortedByDescending { it.finishedAt }

    fun favorites(profile: ReaderProfile?): List<FavoriteMark> =
        profile?.favorites.orEmpty().filter { it.on }.sortedByDescending { it.at }

    /** Groups are written by hand: "Club Martes" and "club martes " are the same group. */
    fun sameGroup(a: String?, b: String?): Boolean =
        !a.isNullOrBlank() && !b.isNullOrBlank() && normalize(a) == normalize(b)

    /** Nicknames are compared without case or accents, so "Ana" and "ana" are the same person. */
    fun normalize(nickname: String): String =
        java.text.Normalizer.normalize(nickname.trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
}

/** The avatars a reader can pick: drawings only, never photos. */
object ReaderAvatars {
    val EMOJIS = listOf(
        "🦊", "🐼", "🦉", "🐢", "🐬", "🦁", "🐸", "🐙", "🦋", "🐝", "🐨", "🦄",
        "🐧", "🐯", "🐰", "🐳", "🦜", "🐞", "🌻", "🌙", "⭐", "🚀", "⚽", "🎸",
    )

    /** Background colours of the avatar (ARGB), readable with the emoji on top. */
    val COLORS = listOf(
        0xFFFFD6A5, 0xFFCAFFBF, 0xFF9BF6FF, 0xFFBDB2FF, 0xFFFFC6FF, 0xFFFDFFB6,
        0xFFA0C4FF, 0xFFFFADAD, 0xFFB5EAD7, 0xFFE2F0CB,
    ).map { it.toInt() }

    fun emoji(index: Int): String = EMOJIS[index.mod(EMOJIS.size)]

    fun color(index: Int): Int = COLORS[index.mod(COLORS.size)]
}
