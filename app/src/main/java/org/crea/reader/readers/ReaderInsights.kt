package org.crea.reader.readers

import java.time.LocalDate

/** A book of the Drive catalog, as the recommendations need it. */
data class CandidateBook(
    val key: String,
    val title: String,
    val author: String?,
    val categories: List<String>,
    val driveFileId: String,
    val addedAt: Long,
)

/** Why a book is suggested; the screen turns it into a short sentence. */
sealed class Reason {
    data class SameAuthor(val author: String) : Reason()
    data class SameCategory(val category: String) : Reason()
    data class GroupFinished(val readers: Int) : Reason()
    data class Popular(val readers: Int) : Reason()
    data object New : Reason()
}

data class Recommendation(val book: CandidateBook, val reason: Reason)

/** Someone of my group and the book they are reading now. */
data class GroupReading(val profile: ReaderProfile, val entry: ReadEntry)

enum class AchievementId { FIRST_BOOK, FIVE_BOOKS, TEN_BOOKS, STREAK_3, STREAK_7, THREE_AUTHORS, FIRST_FAVORITE, EXPLORER }

/** [progress] of [goal]; unlocked when they meet. */
data class Achievement(val id: AchievementId, val progress: Int, val goal: Int) {
    val unlocked: Boolean get() = progress >= goal
}

object ReaderInsights {

    /** Readings of the group older than this are not "now" anymore. */
    private const val GROUP_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /** What the other people of [me]'s group are reading now (one book each, the latest first). */
    fun groupReading(me: ReaderProfile?, everyone: List<ReaderProfile>, now: Long): List<GroupReading> {
        val group = me?.group ?: return emptyList()
        return everyone
            .filter { it.id != me.id && !it.hidden && !it.deleted && ReaderRules.sameGroup(it.group, group) }
            .mapNotNull { profile ->
                ReaderRules.recent(profile).firstOrNull { now - it.openedAt <= GROUP_WINDOW_MS }?.let { GroupReading(profile, it) }
            }
            .sortedByDescending { it.entry.openedAt }
    }

    /**
     * Up to [limit] books to read next, each with a reason, from the Drive catalog. Without AI and
     * without a connection: same author as a book [me] finished or liked, same category, what the
     * group finished, what many readers finished, what is new. Books already started are left out.
     */
    fun recommend(
        me: ReaderProfile?,
        everyone: List<ReaderProfile>,
        catalog: List<CandidateBook>,
        now: Long,
        limit: Int = 8,
    ): List<Recommendation> {
        if (catalog.isEmpty()) return emptyList()
        val read = me?.history.orEmpty().map { it.key }.toSet()
        val available = catalog.filter { it.key !in read }
        if (available.isEmpty()) return emptyList()
        val byKey = catalog.associateBy { it.key }

        // What [me] enjoyed: finished books and favourites weigh most, then what they opened.
        val liked = buildList {
            ReaderRules.finished(me).forEach { add(it.key) }
            ReaderRules.favorites(me).forEach { add(it.key) }
            ReaderRules.recent(me).forEach { add(it.key) }
        }.distinct().mapNotNull { byKey[it] }
        val authors = liked.mapNotNull { book -> book.author?.trim()?.takeIf { it.isNotBlank() } }.distinctBy { it.lowercase() }
        val categories = liked.flatMap { it.categories }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }

        val others = everyone.filter { it.id != me?.id && !it.deleted }
        fun finishedBy(profiles: List<ReaderProfile>, key: String) = profiles.count { p -> p.history.any { it.key == key && it.finishedAt != null } }
        val group = others.filter { me != null && ReaderRules.sameGroup(it.group, me.group) }

        val picks = LinkedHashMap<String, Recommendation>()
        fun add(book: CandidateBook, reason: Reason) {
            if (picks.size < limit && book.key !in picks) picks[book.key] = Recommendation(book, reason)
        }

        for (author in authors) {
            available.filter { it.author?.trim().equals(author, ignoreCase = true) }.take(2).forEach { add(it, Reason.SameAuthor(author)) }
        }
        for (category in categories.take(3)) {
            available.filter { category in it.categories }.take(2).forEach { add(it, Reason.SameCategory(category)) }
        }
        available.map { it to finishedBy(group, it.key) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(3).forEach { (book, count) -> add(book, Reason.GroupFinished(count)) }
        available.map { it to finishedBy(others, it.key) }.filter { it.second >= 2 }
            .sortedByDescending { it.second }.take(3).forEach { (book, count) -> add(book, Reason.Popular(count)) }
        available.sortedByDescending { it.addedAt }.take(limit).forEach { add(it, Reason.New) }
        return picks.values.toList()
    }

    /** Days in a row with reading, ending today or yesterday (a streak is not lost before bedtime). */
    fun streak(days: List<String>, today: LocalDate): Int {
        val set = days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
        var day = if (today in set) today else today.minusDays(1)
        var count = 0
        while (day in set) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    /** The longest streak ever, for achievements that stay unlocked. */
    fun bestStreak(days: List<String>): Int {
        val sorted = days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.distinct().sorted()
        var best = 0
        var current = 0
        var previous: LocalDate? = null
        for (day in sorted) {
            current = if (previous != null && previous.plusDays(1) == day) current + 1 else 1
            best = maxOf(best, current)
            previous = day
        }
        return best
    }

    /** Small achievements to encourage reading (no points, no ranking). */
    fun achievements(profile: ReaderProfile?): List<Achievement> {
        val finished = ReaderRules.finished(profile).size
        val best = bestStreak(profile?.days.orEmpty())
        val authors = profile?.history.orEmpty().filter { it.finishedAt != null }
            .mapNotNull { it.author?.trim()?.lowercase()?.takeIf { a -> a.isNotBlank() } }.distinct().size
        val favorites = ReaderRules.favorites(profile).size
        val opened = profile?.history.orEmpty().size
        return listOf(
            Achievement(AchievementId.FIRST_BOOK, finished, 1),
            Achievement(AchievementId.FIVE_BOOKS, finished, 5),
            Achievement(AchievementId.TEN_BOOKS, finished, 10),
            Achievement(AchievementId.STREAK_3, best, 3),
            Achievement(AchievementId.STREAK_7, best, 7),
            Achievement(AchievementId.THREE_AUTHORS, authors, 3),
            Achievement(AchievementId.FIRST_FAVORITE, favorites, 1),
            Achievement(AchievementId.EXPLORER, opened, 10),
        ).map { it.copy(progress = minOf(it.progress, it.goal)) }
    }
}
