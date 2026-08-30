package io.dodo.obdmap

import io.dodo.obdmap.util.Geo
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoBearingTest {

    @Test
    fun `на север ноль градусов`() {
        assertEquals(0.0, Geo.bearingDegrees(55.0, 37.0, 55.01, 37.0), 0.1)
    }

    @Test
    fun `на восток девяносто`() {
        assertEquals(90.0, Geo.bearingDegrees(55.0, 37.0, 55.0, 37.01), 0.1)
    }

    @Test
    fun `на юг сто восемьдесят`() {
        assertEquals(180.0, Geo.bearingDegrees(55.0, 37.0, 54.99, 37.0), 0.1)
    }

    @Test
    fun `на запад двести семьдесят`() {
        assertEquals(270.0, Geo.bearingDegrees(55.0, 37.0, 55.0, 36.99), 0.1)
    }

    @Test
    fun `северо-восток около сорока пяти`() {
        // на широте 55° градус долготы почти вдвое короче градуса широты,
        // поэтому берём поправку на косинус, иначе будет не 45
        val bearing = Geo.bearingDegrees(55.0, 37.0, 55.01, 37.0 + 0.01 / 0.5736)
        assertEquals(45.0, bearing, 1.0)
    }

    @Test
    fun `курс всегда в диапазоне от нуля до 360`() {
        val values = listOf(
            Geo.bearingDegrees(55.0, 37.0, 54.99, 36.99),
            Geo.bearingDegrees(55.0, 37.0, 55.01, 36.99),
            Geo.bearingDegrees(-33.0, 151.0, -34.0, 150.0),
        )
        values.forEach { assert(it in 0.0..360.0) { "курс вне диапазона: $it" } }
    }
}
