package io.dodo.obdmap

import io.dodo.obdmap.analysis.DriveSample
import io.dodo.obdmap.analysis.Periods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PeriodsTest {

    private fun at(year: Int, month: Int, day: Int): Long {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.set(year, month, day, 12, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun sample(timeMs: Long, consumption: Double = 7.0) =
        DriveSample(speedKmh = 90.0, litersPer100Km = consumption, accelerationMs2 = 0.0, timeMs = timeMs)

    @Test
    fun `месяц подписывается коротко с годом`() {
        assertEquals("мар 2026", Periods.label(at(2026, Calendar.MARCH, 15), Periods.Mode.MONTH))
        assertEquals("дек 2025", Periods.label(at(2025, Calendar.DECEMBER, 1), Periods.Mode.MONTH))
    }

    @Test
    fun `неделя начинается с понедельника`() {
        // 2 марта 2026 — понедельник
        assertEquals("пн", Periods.label(at(2026, Calendar.MARCH, 2), Periods.Mode.WEEKDAY))
        assertEquals("вс", Periods.label(at(2026, Calendar.MARCH, 8), Periods.Mode.WEEKDAY))
        assertEquals("сб", Periods.label(at(2026, Calendar.MARCH, 7), Periods.Mode.WEEKDAY))
    }

    @Test
    fun `месяцы идут по возрастанию времени`() {
        val samples = listOf(
            sample(at(2026, Calendar.MARCH, 1)),
            sample(at(2026, Calendar.JANUARY, 1)),
            sample(at(2026, Calendar.FEBRUARY, 1)),
        )
        val groups = Periods.groups(samples, Periods.Mode.MONTH)
        assertEquals(listOf("янв 2026", "фев 2026", "мар 2026"), groups.map { it.first })
    }

    @Test
    fun `дни недели идут с понедельника`() {
        val samples = listOf(
            sample(at(2026, Calendar.MARCH, 8)),
            sample(at(2026, Calendar.MARCH, 2)),
            sample(at(2026, Calendar.MARCH, 4)),
        )
        val groups = Periods.groups(samples, Periods.Mode.WEEKDAY)
        assertEquals(listOf("пн", "ср", "вс"), groups.map { it.first })
    }

    @Test
    fun `лишние месяцы режутся с самых старых`() {
        val samples = (0..7).map { sample(at(2025, it, 1)) }
        val groups = Periods.groups(samples, Periods.Mode.MONTH, maxGroups = 3)
        assertEquals(3, groups.size)
        assertEquals(listOf("июн 2025", "июл 2025", "авг 2025"), groups.map { it.first })
    }

    @Test
    fun `половины делят выборку пополам по времени`() {
        val samples = (1..10).map { sample(at(2026, Calendar.MARCH, it)) }
        val groups = Periods.groups(samples, Periods.Mode.HALVES)
        assertEquals(listOf("раньше", "позже"), groups.map { it.first })
        assertEquals(5, groups[0].second.size)
        assertEquals(5, groups[1].second.size)
        assertTrue(groups[0].second.maxOf { it.timeMs } <= groups[1].second.minOf { it.timeMs })
    }

    @Test
    fun `пустая выборка даёт пустой список групп`() {
        assertTrue(Periods.groups(emptyList(), Periods.Mode.MONTH).isEmpty())
        assertTrue(Periods.groups(emptyList(), Periods.Mode.HALVES).isEmpty())
    }

    @Test
    fun `один замер не делится на половины`() {
        assertTrue(Periods.groups(listOf(sample(at(2026, 0, 1))), Periods.Mode.HALVES).isEmpty())
    }
}
