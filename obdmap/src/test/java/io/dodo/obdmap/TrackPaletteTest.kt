package io.dodo.obdmap

import io.dodo.obdmap.analysis.TrackPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackPaletteTest {

    @Test
    fun `выше восьмидесяти синий`() {
        assertEquals(TrackPalette.BLUE, TrackPalette.colorOf(TrackPalette.Mode.SPEED, 95.0))
        assertEquals(TrackPalette.BLUE, TrackPalette.colorOf(TrackPalette.Mode.SPEED, 80.0))
        assertNotEquals(TrackPalette.BLUE, TrackPalette.colorOf(TrackPalette.Mode.SPEED, 79.9))
    }

    @Test
    fun `границы полос попадают в верхнюю полосу`() {
        // 20 — это уже не «медленно», а следующая полоса
        assertEquals(TrackPalette.GREY, TrackPalette.colorOf(TrackPalette.Mode.SPEED, 19.9))
        assertEquals(TrackPalette.GREEN, TrackPalette.colorOf(TrackPalette.Mode.SPEED, 20.0))
    }

    @Test
    fun `выше последнего порога красный`() {
        assertEquals(TrackPalette.RED, TrackPalette.colorOf(TrackPalette.Mode.SPEED, 200.0))
    }

    @Test
    fun `свои пороги применяются`() {
        val thresholds = listOf(10.0, 30.0, 50.0, 70.0)
        assertEquals(
            TrackPalette.BLUE,
            TrackPalette.colorOf(TrackPalette.Mode.SPEED, 55.0, thresholds),
        )
        assertEquals(
            TrackPalette.YELLOW,
            TrackPalette.colorOf(TrackPalette.Mode.SPEED, 35.0, thresholds),
        )
    }

    @Test
    fun `кривые пороги заменяются значениями по умолчанию`() {
        val broken = listOf(50.0, 10.0, 90.0, 20.0)
        assertEquals(
            TrackPalette.colorOf(TrackPalette.Mode.SPEED, 95.0),
            TrackPalette.colorOf(TrackPalette.Mode.SPEED, 95.0, broken),
        )
        assertEquals(
            TrackPalette.colorOf(TrackPalette.Mode.SPEED, 95.0),
            TrackPalette.colorOf(TrackPalette.Mode.SPEED, 95.0, listOf(1.0, 2.0)),
        )
    }

    @Test
    fun `без данных серый`() {
        assertEquals(TrackPalette.GREY, TrackPalette.colorOf(TrackPalette.Mode.SPEED, null))
        assertEquals(TrackPalette.GREY, TrackPalette.colorOf(TrackPalette.Mode.CONSUMPTION, null))
    }

    @Test
    fun `экономичный расход зелёный а высокий красный`() {
        assertEquals(TrackPalette.GREEN, TrackPalette.colorOf(TrackPalette.Mode.CONSUMPTION, 4.0))
        assertEquals(TrackPalette.RED, TrackPalette.colorOf(TrackPalette.Mode.CONSUMPTION, 25.0))
    }

    @Test
    fun `торможение и разгон красятся по-разному`() {
        val braking = TrackPalette.colorOf(TrackPalette.Mode.ACCELERATION, -3.0)
        val cruising = TrackPalette.colorOf(TrackPalette.Mode.ACCELERATION, 0.0)
        val accelerating = TrackPalette.colorOf(TrackPalette.Mode.ACCELERATION, 3.0)
        assertEquals(TrackPalette.PURPLE, braking)
        assertEquals(TrackPalette.GREY, cruising)
        assertEquals(TrackPalette.RED, accelerating)
    }

    @Test
    fun `у каждой полосы есть подпись и последняя без верхней границы`() {
        val bands = TrackPalette.speedBands()
        assertEquals(5, bands.size)
        assertEquals(null, bands.last().upTo)
        bands.forEach { assert(it.label.isNotBlank()) }
    }
}
