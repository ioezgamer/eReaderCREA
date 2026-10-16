package org.crea.reader.stats

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingStatsTest {
    private val dayMs = 24L * 60 * 60 * 1000
    private var clock = 100 * dayMs + 12 * 60 * 60 * 1000

    private class MemoryStore : StatsStore {
        var raw: String? = null
        override fun read() = raw
        override fun write(raw: String) { this.raw = raw }
    }

    private val store = MemoryStore()
    private val stats = ReadingStats(store, ZoneOffset.UTC) { clock }

    private fun nextDay() { clock += dayMs }

    @Test
    fun `adds time and pages to today and totals them`() {
        stats.addSeconds(90)
        stats.addPages(3)
        stats.addSeconds(30)
        assertEquals(DayStat(120, 3), stats.last(1).single())
        assertEquals(DayStat(120, 3), stats.totals())
    }

    @Test
    fun `last returns oldest first with empty days filled in`() {
        stats.addSeconds(100)
        nextDay()
        nextDay()
        stats.addPages(2)
        assertEquals(listOf(DayStat(100, 0), DayStat(), DayStat(0, 2)), stats.last(3))
    }

    @Test
    fun `streak counts consecutive days with at least a minute`() {
        repeat(3) { stats.addSeconds(60); nextDay() }
        stats.addSeconds(60)
        assertEquals(4, stats.streak())
    }

    @Test
    fun `a short today does not break the streak yet`() {
        repeat(3) { stats.addSeconds(600); nextDay() }
        stats.addSeconds(10)
        assertEquals(3, stats.streak())
    }

    @Test
    fun `a missed day resets the streak`() {
        stats.addSeconds(600)
        nextDay()
        nextDay()
        assertEquals(0, stats.streak())
    }

    @Test
    fun `days under a minute do not count for the streak`() {
        stats.addSeconds(59)
        assertEquals(0, stats.streak())
    }

    @Test
    fun `corrupt stored data reads as empty and is replaced on the next write`() {
        store.raw = "not json"
        assertEquals(DayStat(), stats.totals())
        stats.addPages(1)
        assertEquals(DayStat(0, 1), stats.totals())
    }

    @Test
    fun `session drops visits that are too short or too long`() {
        val session = ReadingSession(stats) { clock }
        session.start(); clock += 3_000; session.stop()
        session.start(); clock += 5 * 60 * 60 * 1000L; session.stop()
        assertEquals(0L, stats.totals().seconds)
        session.start(); clock += 125_000; session.stop()
        assertEquals(125L, stats.totals().seconds)
    }

    @Test
    fun `stop without start counts nothing`() {
        ReadingSession(stats) { clock }.stop()
        assertEquals(0L, stats.totals().seconds)
    }

    @Test
    fun `only forward single turns count as pages`() {
        val turns = PageTurnCounter(stats)
        turns.onPosition(10)
        turns.onPosition(11)
        turns.onPosition(12)
        turns.onPosition(40)
        turns.onPosition(39)
        turns.onPosition(40)
        assertEquals(3L, stats.totals().pages)
    }

    @Test
    fun `formats reading time`() {
        assertEquals("0 min", formatReadingTime(59))
        assertEquals("45 min", formatReadingTime(45 * 60L))
        assertEquals("2 h 5 min", formatReadingTime(125 * 60L))
    }
}
