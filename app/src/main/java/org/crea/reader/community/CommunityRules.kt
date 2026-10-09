package org.crea.reader.community

import org.crea.reader.online.CatalogRow
import org.crea.reader.sync.CommunityMember

/** A book other people read, with how many and how many finished it. */
data class BookInterest(
    val row: CatalogRow,
    val readers: Int,
    val finishers: Int,
    /** True when people whose reading overlaps with yours are among the readers. */
    val similarReaders: Boolean = false,
)

/** One person's current reading, for the "Leyendo ahora" list. */
data class ReadingNow(
    val nickname: String,
    val row: CatalogRow,
    val percent: Int,
    val updatedAt: Long,
)

data class CommunityFeed(
    val recommended: List<BookInterest>,
    val readingNow: List<ReadingNow>,
    val popular: List<BookInterest>,
) {
    val isEmpty: Boolean get() = recommended.isEmpty() && readingNow.isEmpty() && popular.isEmpty()
}

/**
 * Turns what the people who share their reading publish into three lists: what to read next for
 * you, what others are reading now, and what is read the most. Free of Android so it can be tested.
 */
object CommunityRules {
    const val FINISHED_AT = 98
    const val ACTIVE_DAYS = 30
    private const val MAX_RECOMMENDED = 15
    private const val MAX_POPULAR = 20
    private const val MAX_NOW = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun build(
        members: List<CommunityMember>,
        rows: Map<String, CatalogRow>,
        now: Long = System.currentTimeMillis(),
    ): CommunityFeed {
        if (members.isEmpty()) return CommunityFeed(emptyList(), emptyList(), emptyList())

        // Books I have started or downloaded; a person who shares some of them reads like me.
        val mine = rows.values.filter { it.ownPercent > 0 || it.downloaded }.mapTo(hashSetOf()) { it.book.driveFileId }
        val weights = members.associate { member ->
            val shared = member.readings.count { it.percent > 0 && it.driveFileId in mine }
            member.userId to 1 + shared
        }

        class Tally(var readers: Int = 0, var finishers: Int = 0, var score: Int = 0, var similar: Boolean = false)
        val tallies = HashMap<String, Tally>()
        for (member in members) {
            val weight = weights.getValue(member.userId)
            for (reading in member.readings) {
                if (reading.percent <= 0 || reading.driveFileId !in rows) continue
                val tally = tallies.getOrPut(reading.driveFileId) { Tally() }
                val finished = reading.percent >= FINISHED_AT
                tally.readers++
                if (finished) tally.finishers++
                tally.score += weight * (if (finished) 2 else 1)
                if (weight > 1) tally.similar = true
            }
        }

        fun interest(id: String, tally: Tally) =
            BookInterest(rows.getValue(id), tally.readers, tally.finishers, tally.similar)

        val titleOrder = compareBy<BookInterest> { it.row.book.title.lowercase() }
        val popular = tallies.map { (id, tally) -> interest(id, tally) }
            .sortedWith(compareByDescending<BookInterest> { it.readers }.thenByDescending { it.finishers }.then(titleOrder))
            .take(MAX_POPULAR)

        val recommended = tallies
            .filter { (id, _) -> id !in mine }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Tally>> { it.value.score }
                .thenByDescending { it.value.readers }
                .thenBy { rows.getValue(it.key).book.title.lowercase() })
            .take(MAX_RECOMMENDED)
            .map { (id, tally) -> interest(id, tally) }

        val since = now - ACTIVE_DAYS * DAY_MS
        val readingNow = members.flatMap { member ->
            member.readings
                .filter { it.percent in 1 until FINISHED_AT && it.updatedAt >= since && it.driveFileId in rows }
                .map { ReadingNow(member.nickname, rows.getValue(it.driveFileId), it.percent, it.updatedAt) }
        }.sortedByDescending { it.updatedAt }.take(MAX_NOW)

        return CommunityFeed(recommended, readingNow, popular)
    }
}
