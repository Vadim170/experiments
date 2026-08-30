package io.dodo.obdmap

import io.dodo.obdmap.analysis.TrackPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientTest {

    private fun red(color: Int) = (color shr 16) and 0xFF
    private fun green(color: Int) = (color shr 8) and 0xFF

    @Test
    fun `в опорной точке цвет ровно опорный`() {
        val stops = TrackPalette.stops(TrackPalette.Mode.SPEED)
        stops.forEach { stop ->
            assertEquals(
                stop.color,
                TrackPalette.gradientColor(TrackPalette.Mode.SPEED, stop.value),
            )
        }
    }

    @Test
    fun `между опорными точками цвет промежуточный`() {
        val a = TrackPalette.gradientColor(TrackPalette.Mode.SPEED, 20.0)
        val b = TrackPalette.gradientColor(TrackPalette.Mode.SPEED, 60.0)
        val middle = TrackPalette.gradientColor(TrackPalette.Mode.SPEED, 40.0)
        assertNotEquals(a, middle)
        assertNotEquals(b, middle)
    }

    @Test
    fun `за краями берётся крайний цвет`() {
        val stops = TrackPalette.stops(TrackPalette.Mode.SPEED)
        assertEquals(stops.first().color, TrackPalette.gradientColor(TrackPalette.Mode.SPEED, -50.0))
        assertEquals(stops.last().color, TrackPalette.gradientColor(TrackPalette.Mode.SPEED, 999.0))
    }

    @Test
    fun `переход плавный - соседние значения близки по цвету`() {
        // Именно этого не хватало полосам: на границе цвет менялся скачком
        var maxJump = 0
        var value = 0.0
        while (value < 150.0) {
            val current = TrackPalette.gradientColor(TrackPalette.Mode.SPEED, value)
            val next = TrackPalette.gradientColor(TrackPalette.Mode.SPEED, value + 1.0)
            maxJump = maxOf(maxJump, kotlin.math.abs(red(current) - red(next)))
            maxJump = maxOf(maxJump, kotlin.math.abs(green(current) - green(next)))
            value += 1.0
        }
        assertTrue("шаг цвета на 1 км/ч слишком велик: $maxJump", maxJump < 30)
    }

    @Test
    fun `без данных серый`() {
        assertEquals(
            TrackPalette.GREY,
            TrackPalette.gradientColor(TrackPalette.Mode.SPEED, null),
        )
    }

    @Test
    fun `квантование даёт ограниченное число цветов`() {
        val colors = mutableSetOf<Int>()
        var value = 0.0
        while (value <= 160.0) {
            colors += TrackPalette.quantizedGradientColor(TrackPalette.Mode.SPEED, value)
            value += 0.25
        }
        assertTrue("цветов должно быть немного: ${colors.size}", colors.size <= TrackPalette.GRADIENT_STEPS + 2)
        assertTrue("но и не один: ${colors.size}", colors.size > 8)
    }

    @Test
    fun `смешивание цветов идёт по краям`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(black, TrackPalette.blend(black, white, 0.0))
        assertEquals(white, TrackPalette.blend(black, white, 1.0))
        assertEquals(127, red(TrackPalette.blend(black, white, 0.5)))
    }

    @Test
    fun `ускорение красится симметрично относительно нуля`() {
        val braking = TrackPalette.gradientColor(TrackPalette.Mode.ACCELERATION, -3.0)
        val cruise = TrackPalette.gradientColor(TrackPalette.Mode.ACCELERATION, 0.0)
        val accel = TrackPalette.gradientColor(TrackPalette.Mode.ACCELERATION, 3.0)
        assertEquals(TrackPalette.PURPLE, braking)
        assertEquals(TrackPalette.GREY, cruise)
        assertEquals(TrackPalette.RED, accel)
    }
}
