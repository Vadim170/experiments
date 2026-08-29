package io.dodo.obdmap

import io.dodo.obdmap.trip.TripAccumulator
import io.dodo.obdmap.trip.TripSample
import io.dodo.obdmap.trip.statsOf
import io.dodo.obdmap.util.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMathTest {

    private val start = 1_700_000_000_000L

    @Test
    fun `пробег считается по GPS`() {
        // 0.01 градуса широты ≈ 1111 м
        val stats = statsOf(
            listOf(
                TripSample(start, 55.0, 37.0, speedKmh = 60.0, fuelRateLitersPerHour = 6.0),
                TripSample(start + 20_000, 55.01, 37.0, speedKmh = 60.0, fuelRateLitersPerHour = 6.0),
            ),
        )
        val expected = Geo.distanceMeters(55.0, 37.0, 55.01, 37.0)
        assertEquals(expected, stats.distanceMeters, 1.0)
    }

    @Test
    fun `без координат пробег берётся из скорости`() {
        // 60 км/ч в течение 20 с = 333 м
        val stats = statsOf(
            listOf(
                TripSample(start, speedKmh = 60.0),
                TripSample(start + 20_000, speedKmh = 60.0),
            ),
        )
        assertEquals(333.3, stats.distanceMeters, 1.0)
    }

    @Test
    fun `топливо интегрируется по трапеции`() {
        // 6 л/ч в течение часа = 6 литров
        val stats = statsOf(
            listOf(
                TripSample(start, speedKmh = 60.0, fuelRateLitersPerHour = 6.0),
                TripSample(start + 20_000, speedKmh = 60.0, fuelRateLitersPerHour = 6.0),
            ),
        )
        assertEquals(6.0 * 20.0 / 3600.0, stats.fuelLiters, 0.0001)
    }

    @Test
    fun `выброс GPS не попадает в пробег`() {
        // прыжок на 100 км за секунду — заведомо мусор
        val stats = statsOf(
            listOf(
                TripSample(start, 55.0, 37.0, speedKmh = 60.0),
                TripSample(start + 1_000, 56.0, 37.0, speedKmh = 60.0),
            ),
        )
        assertEquals(0.0, stats.distanceMeters, 0.001)
    }

    @Test
    fun `дрожание GPS на стоянке не накручивает пробег`() {
        var time = start
        val samples = mutableListOf<TripSample>()
        repeat(20) { index ->
            // смещения около метра туда-сюда
            val jitter = if (index % 2 == 0) 0.000005 else -0.000005
            samples += TripSample(time, 55.0 + jitter, 37.0, speedKmh = 0.0)
            time += 1_000
        }
        assertEquals(0.0, statsOf(samples).distanceMeters, 0.001)
    }

    @Test
    fun `длинный разрыв связи не интегрируется`() {
        val stats = statsOf(
            listOf(
                TripSample(start, speedKmh = 60.0, fuelRateLitersPerHour = 6.0),
                // связь пропала на пять минут
                TripSample(start + 300_000, speedKmh = 60.0, fuelRateLitersPerHour = 6.0),
            ),
        )
        assertEquals(0.0, stats.distanceMeters, 0.001)
        assertEquals(0.0, stats.fuelLiters, 0.001)
    }

    @Test
    fun `стоянка и движение считаются раздельно`() {
        val stats = statsOf(
            listOf(
                TripSample(start, speedKmh = 0.0),
                TripSample(start + 10_000, speedKmh = 0.0),
                TripSample(start + 20_000, speedKmh = 50.0),
                TripSample(start + 30_000, speedKmh = 50.0),
            ),
        )
        assertEquals(10_000, stats.idleMillis)
        assertEquals(20_000, stats.movingMillis)
        assertEquals(30_000, stats.durationMillis)
    }

    @Test
    fun `максимальная скорость запоминается`() {
        val stats = statsOf(
            listOf(
                TripSample(start, speedKmh = 40.0),
                TripSample(start + 1_000, speedKmh = 130.0),
                TripSample(start + 2_000, speedKmh = 60.0),
            ),
        )
        assertEquals(130.0, stats.maxSpeedKmh, 0.001)
    }

    @Test
    fun `средний расход по итогам поездки`() {
        // 100 км при 7 л/ч и 70 км/ч -> 10 л/100км
        val accumulator = TripAccumulator()
        var time = start
        repeat(3601) {
            accumulator.add(TripSample(time, speedKmh = 70.0, fuelRateLitersPerHour = 7.0))
            time += 1_000
        }
        val stats = accumulator.stats
        assertEquals(70_000.0, stats.distanceMeters, 100.0)
        assertEquals(7.0, stats.fuelLiters, 0.01)
        assertEquals(10.0, stats.averageLitersPer100Km!!, 0.05)
        assertEquals(70.0, stats.averageSpeedKmh, 0.2)
    }

    @Test
    fun `пустая поездка не даёт среднего расхода`() {
        val stats = statsOf(emptyList())
        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertNull(stats.averageLitersPer100Km)
        assertEquals(0.0, stats.averageSpeedKmh, 0.0)
    }

    @Test
    fun `замеры без топлива не ломают пробег`() {
        val stats = statsOf(
            listOf(
                TripSample(start, 55.0, 37.0, speedKmh = 60.0),
                TripSample(start + 10_000, 55.001, 37.0, speedKmh = 60.0),
            ),
        )
        assertTrue(stats.distanceMeters > 100)
        assertEquals(0.0, stats.fuelLiters, 0.0001)
    }
}
