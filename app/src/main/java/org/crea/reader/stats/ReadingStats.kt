package org.crea.reader.stats

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Reading done in one calendar day. */
data class DayStat(val seconds: Long = 0L, val pages: Long = 0L)

/** Where the per-day numbers live; the stats never leave the device. */
interface StatsStore {
    fun read(): String?
    fun write(raw: String)
}

class PrefsStatsStore(context: Context) : StatsStore {
    private val prefs = context.applicationContext.getSharedPreferences("read_stats", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_DAYS, null)

    override fun write(raw: String) {
        prefs.edit().putString(KEY_DAYS, raw).apply()
    }

    private companion object {
        const val KEY_DAYS = "days"
    }
}

/**
 * Time and pages read per day, stored as `{"2026-10-14":{"s":seconds,"p":pages}}`.
 *
 * A day counts for the streak when it has at least [STREAK_MIN_SECONDS] of reading. Today does not
 * break the streak while it is still short: counting then starts from yesterday.
 */
class ReadingStats(
    private val store: StatsStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun addSeconds(seconds: Long) = add(SECONDS, seconds)

    @Synchronized
    fun addPages(pages: Long) = add(PAGES, pages)

    /** The last [days] days oldest first; the final entry is today. */
    @Synchronized
    fun last(days: Int): List<DayStat> {
        val all = load()
        val today = today()
        return (days - 1 downTo 0).map { all[today.minusDays(it.toLong()).toString()] ?: DayStat() }
    }

    @Synchronized
    fun totals(): DayStat {
        val all = load().values
        return DayStat(all.sumOf { it.seconds }, all.sumOf { it.pages })
    }

    @Synchronized
    fun streak(): Int {
        val all = load()
        var day = today()
        if ((all[day.toString()]?.seconds ?: 0L) < STREAK_MIN_SECONDS) day = day.minusDays(1)
        var count = 0
        while ((all[day.toString()]?.seconds ?: 0L) >= STREAK_MIN_SECONDS) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    private fun today(): LocalDate = LocalDate.ofInstant(Instant.ofEpochMilli(now()), zone)

    private fun add(field: String, amount: Long) {
        if (amount <= 0) return
        val all = load().toMutableMap()
        val key = today().toString()
        val day = all[key] ?: DayStat()
        all[key] = if (field == SECONDS) day.copy(seconds = day.seconds + amount) else day.copy(pages = day.pages + amount)
        store.write(encode(all))
    }

    private fun load(): Map<String, DayStat> = runCatching {
        Json.parseToJsonElement(store.read() ?: "{}").jsonObject.mapValues { (_, value) ->
            val day = value.jsonObject
            DayStat(
                seconds = day[SECONDS]?.jsonPrimitive?.longOrNull ?: 0L,
                pages = day[PAGES]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }.getOrDefault(emptyMap())

    private fun encode(all: Map<String, DayStat>): String = buildJsonObject {
        all.forEach { (key, day) ->
            put(
                key,
                JsonObject(mapOf(SECONDS to JsonPrimitive(day.seconds), PAGES to JsonPrimitive(day.pages))),
            )
        }
    }.toString()

    companion object {
        const val STREAK_MIN_SECONDS = 60L
        private const val SECONDS = "s"
        private const val PAGES = "p"
    }
}

/**
 * Counts the time the reader is on screen. Very short visits and visits longer than four hours
 * (a forgotten open screen) are dropped.
 */
class ReadingSession(
    private val stats: ReadingStats,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var startedAt = 0L

    fun start() {
        startedAt = now()
    }

    fun stop() {
        if (startedAt <= 0L) return
        val seconds = (now() - startedAt) / 1000
        if (seconds > MIN_SECONDS && seconds < MAX_SECONDS) stats.addSeconds(seconds)
        startedAt = 0L
    }

    private companion object {
        const val MIN_SECONDS = 3L
        const val MAX_SECONDS = 4L * 60 * 60
    }
}

/** One page counts per turn forward; jumps (index, slider) and going back do not. */
class PageTurnCounter(private val stats: ReadingStats) {
    private var lastPosition = -1

    fun onPosition(position: Int) {
        if (lastPosition >= 0 && position == lastPosition + 1) stats.addPages(1)
        lastPosition = position
    }
}

/** "45 min" or "2 h 5 min". */
fun formatReadingTime(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
