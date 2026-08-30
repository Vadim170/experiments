package io.dodo.obdmap

import io.dodo.obdmap.trip.AccelerationTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AccelerationTest {

    private val start = 1_700_000_000_000L

    @Test
    fun `пока окно не набралось ускорение неизвестно`() {
        val tracker = AccelerationTracker()
        assertNull(tracker.add(start, 0.0))
        assertNull(tracker.add(start + 250, 5.0))
    }

    @Test
    fun `равномерный разгон до сотни за десять секунд`() {
        val tracker = AccelerationTracker()
        var result: Double? = null
        // 100 км/ч = 27.78 м/с за 10 с -> 2.78 м/с²
        for (step in 0..40) {
            val timeMs = start + step * 250L
            val speed = 100.0 * step / 40.0
            result = tracker.add(timeMs, speed) ?: result
        }
        assertNotNull(result)
        assertEquals(2.778, result!!, 0.05)
    }

    @Test
    fun `равномерное движение даёт около нуля`() {
        val tracker = AccelerationTracker()
        var result: Double? = null
        for (step in 0..40) {
            result = tracker.add(start + step * 250L, 90.0) ?: result
        }
        assertEquals(0.0, result!!, 0.001)
    }

    @Test
    fun `квантование скорости в целые км-ч не выдаётся за ускорение`() {
        // Едем ровно 60, но шина отдаёт то 60, то 61 — типичное дрожание
        val tracker = AccelerationTracker()
        var worst = 0.0
        for (step in 0..80) {
            val speed = if (step % 2 == 0) 60.0 else 61.0
            tracker.add(start + step * 250L, speed)?.let { worst = maxOf(worst, abs(it)) }
        }
        assertTrue("дрожание дало $worst м/с², ожидали меньше 0.5", worst < 0.5)
    }

    @Test
    fun `торможение даёт отрицательное ускорение`() {
        val tracker = AccelerationTracker()
        var result: Double? = null
        for (step in 0..40) {
            val speed = 80.0 - 80.0 * step / 40.0
            result = tracker.add(start + step * 250L, speed) ?: result
        }
        assertTrue("ожидали торможение, получили $result", result!! < -1.5)
    }

    @Test
    fun `пропуски данных не ломают окно`() {
        val tracker = AccelerationTracker()
        var result: Double? = null
        for (step in 0..40) {
            val speed = if (step % 5 == 0) null else 100.0 * step / 40.0
            result = tracker.add(start + step * 250L, speed) ?: result
        }
        assertEquals(2.778, result!!, 0.15)
    }

    @Test
    fun `окно сбрасывается если время пошло назад`() {
        val tracker = AccelerationTracker()
        for (step in 0..40) tracker.add(start + step * 250L, 100.0 * step / 40.0)
        // новая поездка, время меньше предыдущего
        assertNull(tracker.add(start - 100_000, 0.0))
    }
}
