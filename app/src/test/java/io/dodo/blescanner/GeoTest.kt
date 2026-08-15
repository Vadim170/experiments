package io.dodo.blescanner

import io.dodo.blescanner.ble.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun `одна и та же точка даёт ноль`() {
        assertEquals(0.0, Geo.distanceMeters(55.7558, 37.6173, 55.7558, 37.6173), 0.001)
    }

    @Test
    fun `градус широты это примерно 111 км`() {
        val distance = Geo.distanceMeters(55.0, 37.0, 56.0, 37.0)
        assertEquals(111_195.0, distance, 500.0)
    }

    @Test
    fun `Москва - Петербург примерно 634 км`() {
        val distance = Geo.distanceMeters(55.7558, 37.6173, 59.9311, 30.3609)
        assertEquals(634_000.0, distance, 5_000.0)
    }

    @Test
    fun `короткое смещение считается с метровой точностью`() {
        // ~0.0001 градуса широты = ~11.1 м
        val distance = Geo.distanceMeters(55.7558, 37.6173, 55.7559, 37.6173)
        assertTrue("ожидали ~11 м, получили $distance", distance in 10.0..12.5)
    }

    @Test
    fun `расстояние симметрично`() {
        val forward = Geo.distanceMeters(55.7558, 37.6173, 59.9311, 30.3609)
        val backward = Geo.distanceMeters(59.9311, 30.3609, 55.7558, 37.6173)
        assertEquals(forward, backward, 0.001)
    }
}
