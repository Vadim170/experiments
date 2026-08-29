package io.dodo.obdmap

import io.dodo.obdmap.obd.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PidsTest {

    @Test
    fun `обороты считаются как A по 256 плюс B делить на 4`() {
        assertEquals(1726.0, Pids.rpm(intArrayOf(0x1A, 0xF8))!!, 0.001)
        assertEquals(0.0, Pids.rpm(intArrayOf(0x00, 0x00))!!, 0.001)
    }

    @Test
    fun `MAF в граммах в секунду`() {
        // 0x0F 0xA0 = 4000 -> 40.00 г/с
        assertEquals(40.0, Pids.mafGramsPerSecond(intArrayOf(0x0F, 0xA0))!!, 0.001)
    }

    @Test
    fun `расход от ЭБУ в литрах в час`() {
        // 0x00 0x64 = 100 -> 5.0 л/ч
        assertEquals(5.0, Pids.fuelRateLitersPerHour(intArrayOf(0x00, 0x64))!!, 0.001)
    }

    @Test
    fun `температуры со смещением 40`() {
        assertEquals(-40, Pids.coolantTempC(intArrayOf(0)))
        assertEquals(50, Pids.intakeTempC(intArrayOf(90)))
    }

    @Test
    fun `уровень бака в процентах`() {
        assertEquals(100.0, Pids.fuelLevelPercent(intArrayOf(255))!!, 0.001)
        assertEquals(50.0, Pids.fuelLevelPercent(intArrayOf(128))!!, 0.5)
    }

    @Test
    fun `нехватка байтов даёт null`() {
        assertNull(Pids.rpm(intArrayOf(0x1A)))
        assertNull(Pids.mafGramsPerSecond(IntArray(0)))
        assertNull(Pids.speedKmh(IntArray(0)))
    }

    @Test
    fun `команда режима 01 форматируется с ведущим нулём`() {
        assertEquals("010D", Pids.command(Pids.SPEED))
        assertEquals("0100", Pids.command(Pids.SUPPORTED_01_20))
        assertEquals("015E", Pids.command(Pids.ENGINE_FUEL_RATE))
    }
}
