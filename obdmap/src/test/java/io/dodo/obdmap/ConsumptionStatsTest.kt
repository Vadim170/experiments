package io.dodo.obdmap

import io.dodo.obdmap.analysis.ConsumptionStats
import io.dodo.obdmap.analysis.DriveSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionStatsTest {

    private fun sample(speed: Double, consumption: Double, acceleration: Double? = 0.0) =
        DriveSample(speed, consumption, acceleration)

    @Test
    fun `перцентили и медиана`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(2.5, ConsumptionStats.median(values)!!, 0.0001)
        assertEquals(1.0, ConsumptionStats.percentile(values, 0.0)!!, 0.0001)
        assertEquals(4.0, ConsumptionStats.percentile(values, 1.0)!!, 0.0001)
        assertEquals(1.75, ConsumptionStats.percentile(values, 0.25)!!, 0.0001)
    }

    @Test
    fun `медиана нечётного ряда это середина`() {
        assertEquals(7.0, ConsumptionStats.median(listOf(1.0, 7.0, 100.0))!!, 0.0001)
    }

    @Test
    fun `пустой ряд не даёт медианы`() {
        assertNull(ConsumptionStats.median(emptyList()))
        assertNull(ConsumptionStats.percentile(emptyList(), 0.5))
    }

    @Test
    fun `фильтр по скорости берёт границы включительно`() {
        val samples = listOf(sample(59.0, 6.0), sample(60.0, 6.0), sample(80.0, 7.0), sample(81.0, 7.0))
        val filtered = ConsumptionStats.filter(samples, minSpeedKmh = 60.0, maxSpeedKmh = 80.0)
        assertEquals(listOf(60.0, 80.0), filtered.map { it.speedKmh })
    }

    @Test
    fun `фильтр по ускорению отсекает разгоны`() {
        val samples = listOf(
            sample(60.0, 6.0, acceleration = 0.1),
            sample(60.0, 14.0, acceleration = 2.5),
            sample(60.0, 2.0, acceleration = -2.5),
        )
        val steady = ConsumptionStats.filter(samples, 0.0, 200.0, maxAbsAcceleration = 0.2)
        assertEquals(1, steady.size)
        assertEquals(6.0, steady.single().litersPer100Km, 0.0001)
    }

    @Test
    fun `замеры без ускорения отбрасываются при жёстком фильтре`() {
        val samples = listOf(sample(60.0, 6.0, acceleration = null))
        assertTrue(ConsumptionStats.filter(samples, 0.0, 200.0, 0.2).isEmpty())
        assertEquals(
            1,
            ConsumptionStats.filter(samples, 0.0, 200.0, 0.2, requireAcceleration = false).size,
        )
    }

    @Test
    fun `без фильтра по ускорению неизвестное ускорение не мешает`() {
        val samples = listOf(sample(60.0, 6.0, acceleration = null))
        assertEquals(1, ConsumptionStats.filter(samples, 0.0, 200.0, null).size)
    }

    @Test
    fun `гистограмма раскладывает значения по корзинам`() {
        val values = listOf(4.5, 5.5, 5.7, 6.2, 6.4, 6.9)
        val bins = ConsumptionStats.histogram(values, binWidth = 1.0)
        assertEquals(4.0, bins.first().from, 0.0001)
        assertEquals(1, bins.first().count)
        assertEquals(listOf(1, 2, 3), bins.map { it.count })
    }

    @Test
    fun `пустые корзины внутри диапазона сохраняются`() {
        val bins = ConsumptionStats.histogram(listOf(1.0, 5.0), binWidth = 1.0)
        assertEquals(4, bins.size)
        assertEquals(listOf(1, 0, 0, 1), bins.map { it.count })
    }

    @Test
    fun `гистограмма пустого ряда пуста`() {
        assertTrue(ConsumptionStats.histogram(emptyList(), 1.0).isEmpty())
        assertTrue(ConsumptionStats.histogram(listOf(1.0), 0.0).isEmpty())
    }

    @Test
    fun `расход по диапазонам скорости`() {
        val samples = buildList {
            // 20 замеров на 60 км/ч с медианой 6.0
            repeat(20) { add(sample(60.0 + it % 5, 5.0 + it * 0.1)) }
            // 20 замеров на 100 км/ч, расход выше
            repeat(20) { add(sample(100.0 + it % 5, 8.0 + it * 0.1)) }
        }
        val bins = ConsumptionStats.bySpeedBin(samples, binKmh = 20.0)
        assertEquals(2, bins.size)
        assertEquals(60.0, bins[0].speedFrom, 0.0001)
        assertEquals(100.0, bins[1].speedFrom, 0.0001)
        assertTrue(bins[1].median > bins[0].median)
        assertTrue(bins[0].p25 < bins[0].median && bins[0].median < bins[0].p75)
    }

    @Test
    fun `малые корзины отбрасываются`() {
        val samples = listOf(sample(60.0, 6.0), sample(61.0, 6.5))
        assertTrue(ConsumptionStats.bySpeedBin(samples, 10.0).isEmpty())
        assertEquals(1, ConsumptionStats.bySpeedBin(samples, 10.0, minCount = 2).size)
    }

    @Test
    fun `фильтр стабильной скорости даёт более узкий разброс`() {
        val samples = buildList {
            repeat(30) { add(sample(90.0, 6.0 + it * 0.01, acceleration = 0.05)) }
            repeat(30) { add(sample(90.0, 15.0 + it * 0.5, acceleration = 2.0)) }
        }
        val all = ConsumptionStats.bySpeedBin(samples, 10.0)
        val steady = ConsumptionStats.bySpeedBin(
            ConsumptionStats.filter(
                samples,
                0.0,
                200.0,
                ConsumptionStats.STEADY_ACCELERATION_MS2,
            ),
            10.0,
        )
        val allSpread = all.single().p75 - all.single().p25
        val steadySpread = steady.single().p75 - steady.single().p25
        assertTrue("разброс должен упасть: $allSpread -> $steadySpread", steadySpread < allSpread)
        assertEquals(6.15, steady.single().median, 0.1)
    }
}

/** Отдельно — то, из-за чего экран анализа показывал пустоту без фильтра. */
class TrimmedHistogramTest {

    private fun sample(speed: Double, consumption: Double, acceleration: Double? = 0.0) =
        DriveSample(speed, consumption, acceleration)

    @Test
    fun `выбросы у отсечки скорости не попадают в выборку`() {
        val samples = listOf(
            sample(4.0, 400.0),
            sample(60.0, 6.0),
            sample(60.0, 7.0),
        )
        val filtered = ConsumptionStats.filter(samples, 0.0, 200.0)
        assertEquals(2, filtered.size)
        assertTrue(filtered.none { it.litersPer100Km > ConsumptionStats.MAX_PLAUSIBLE_L100 })
    }

    @Test
    fun `гистограмма не разваливается на тысячу корзин из-за одного выброса`() {
        val values = List(200) { 6.0 + it % 10 * 0.1 } + listOf(500.0)
        val naive = ConsumptionStats.histogram(values, 0.5)
        val trimmed = ConsumptionStats.trimmedHistogram(values)
        assertTrue("наивная гистограмма и должна быть огромной: ${naive.size}", naive.size > 500)
        assertTrue("после обрезки корзин должно быть немного: ${trimmed.size}", trimmed.size <= 60)
        assertTrue(trimmed.sumOf { it.count } > 150)
    }

    @Test
    fun `шаг гистограммы человеческий`() {
        assertEquals(1.0, ConsumptionStats.niceStep(0.9), 0.0001)
        assertEquals(2.0, ConsumptionStats.niceStep(1.4), 0.0001)
        assertEquals(5.0, ConsumptionStats.niceStep(4.2), 0.0001)
        assertEquals(0.5, ConsumptionStats.niceStep(0.31), 0.0001)
        // 0.05 — это 5·10⁻², такой же «круглый» шаг, как 5 или 50
        assertEquals(0.05, ConsumptionStats.niceStep(0.05), 0.0001)
        assertEquals(0.05, ConsumptionStats.niceStep(0.042), 0.0001)
    }

    @Test
    fun `без фильтра по ускорению данных больше а не меньше`() {
        // Ровно та жалоба: анализ работал только со «стабильной скоростью»
        val samples = buildList {
            repeat(50) { add(sample(60.0, 6.0 + it * 0.02, acceleration = 0.05)) }
            repeat(50) { add(sample(60.0, 12.0 + it * 0.05, acceleration = 1.5)) }
        }
        val steady = ConsumptionStats.filter(samples, 0.0, 200.0, 0.2)
        val everything = ConsumptionStats.filter(samples, 0.0, 200.0, null)
        assertTrue(everything.size > steady.size)

        val bins = ConsumptionStats.trimmedHistogram(everything.map { it.litersPer100Km })
        assertTrue("гистограмма без фильтра не должна быть пустой", bins.isNotEmpty())
        assertTrue(bins.sumOf { it.count } > 80)
    }

    @Test
    fun `одинаковые значения не ломают обрезку`() {
        val bins = ConsumptionStats.trimmedHistogram(List(50) { 7.0 })
        assertTrue(bins.isNotEmpty())
        assertEquals(50, bins.sumOf { it.count })
    }
}
