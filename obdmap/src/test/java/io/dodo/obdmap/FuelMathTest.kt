package io.dodo.obdmap

import io.dodo.obdmap.obd.FuelMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelMathTest {

    @Test
    fun `расход из MAF по стехиометрии`() {
        // 10 г/с воздуха / 14.7 = 0.680 г/с топлива -> 2.449 г/с*3600/745 = 3.29 л/ч
        assertEquals(3.286, FuelMath.fuelRateFromMaf(10.0), 0.01)
    }

    @Test
    fun `нулевой и отрицательный MAF дают ноль`() {
        assertEquals(0.0, FuelMath.fuelRateFromMaf(0.0), 0.0001)
        assertEquals(0.0, FuelMath.fuelRateFromMaf(-5.0), 0.0001)
    }

    @Test
    fun `холостой ход это примерно литр в час`() {
        // холостые ~2.5 г/с воздуха на 1.6 MPI
        val idle = FuelMath.fuelRateFromMaf(2.5)
        assertTrue("ожидали 0.5-1.5 л/ч, получили $idle", idle in 0.5..1.5)
    }

    @Test
    fun `литры на 100 км из литров в час`() {
        // 6 л/ч на 90 км/ч = 6.67 л/100км
        assertEquals(6.667, FuelMath.litersPer100Km(6.0, 90.0)!!, 0.01)
    }

    @Test
    fun `на стоянке мгновенный расход не определён`() {
        assertNull(FuelMath.litersPer100Km(1.0, 0.0))
        assertNull(FuelMath.litersPer100Km(1.0, 2.9))
    }

    @Test
    fun `средний расход за поездку`() {
        // 5 литров на 100 км = 5 л/100км
        assertEquals(5.0, FuelMath.averageLitersPer100Km(5.0, 100_000.0)!!, 0.001)
        // 3.5 литра на 50 км = 7 л/100км
        assertEquals(7.0, FuelMath.averageLitersPer100Km(3.5, 50_000.0)!!, 0.001)
    }

    @Test
    fun `на коротком пробеге средний расход не считаем`() {
        assertNull(FuelMath.averageLitersPer100Km(0.01, 10.0))
    }

    @Test
    fun `speed-density даёт правдоподобный MAF на холостых`() {
        // 800 об/мин, 30 кПа во впуске, 25 °C, 1.6 л
        val maf = FuelMath.estimateMafSpeedDensity(800.0, 30.0, 25.0)
        assertTrue("ожидали 1-6 г/с, получили $maf", maf in 1.0..6.0)
    }

    @Test
    fun `speed-density растёт с оборотами и давлением`() {
        val low = FuelMath.estimateMafSpeedDensity(800.0, 30.0, 25.0)
        val high = FuelMath.estimateMafSpeedDensity(3000.0, 90.0, 25.0)
        assertTrue("под нагрузкой должно быть больше: $low -> $high", high > low * 5)
    }

    @Test
    fun `нулевые обороты дают нулевой расход воздуха`() {
        assertEquals(0.0, FuelMath.estimateMafSpeedDensity(0.0, 30.0, 25.0), 0.0001)
    }
}
