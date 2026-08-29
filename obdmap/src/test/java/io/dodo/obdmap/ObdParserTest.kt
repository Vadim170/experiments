package io.dodo.obdmap

import io.dodo.obdmap.obd.ObdParser
import io.dodo.obdmap.obd.Pids
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {

    @Test
    fun `обычный ответ на скорость`() {
        val data = ObdParser.dataBytes(0x01, Pids.SPEED, "410D3C\r\r>")
        assertArrayEquals(intArrayOf(0x3C), data)
        assertEquals(60, Pids.speedKmh(data!!))
    }

    @Test
    fun `ответ с пробелами разбирается`() {
        val data = ObdParser.dataBytes(0x01, Pids.SPEED, "41 0D 3C\r\r>")
        assertEquals(60, Pids.speedKmh(data!!))
    }

    @Test
    fun `эхо команды не мешает`() {
        val raw = "010D\r410D50\r\r>"
        val data = ObdParser.dataBytes(0x01, Pids.SPEED, raw, echoOf = "010D")
        assertEquals(80, Pids.speedKmh(data!!))
    }

    @Test
    fun `префикс SEARCHING отбрасывается`() {
        val raw = "SEARCHING...\r410C1AF8\r\r>"
        val data = ObdParser.dataBytes(0x01, Pids.RPM, raw)
        assertEquals(1726.0, Pids.rpm(data!!)!!, 0.001)
    }

    @Test
    fun `заголовки кадра не сбивают разбор`() {
        // ATH1: 7E8 03 41 0D 3C
        val data = ObdParser.dataBytes(0x01, Pids.SPEED, "7E80341 0D 3C\r>")
        assertEquals(60, Pids.speedKmh(data!!))
    }

    @Test
    fun `NO DATA данными не считается`() {
        assertNull(ObdParser.dataBytes(0x01, Pids.ENGINE_FUEL_RATE, "NO DATA\r\r>"))
        assertEquals(
            "блок не отдал данные (PID не поддерживается)",
            ObdParser.errorOf("NO DATA\r\r>"),
        )
    }

    @Test
    fun `ошибки шины распознаются`() {
        assertEquals("адаптер не видит шину авто", ObdParser.errorOf("UNABLE TO CONNECT\r>"))
        assertEquals("ошибка шины CAN", ObdParser.errorOf("CAN ERROR\r>"))
        assertEquals("адаптер не понял команду", ObdParser.errorOf("?\r>"))
        assertNull(ObdParser.errorOf("410D3C\r>"))
    }

    @Test
    fun `ответ на чужой PID не подходит`() {
        assertNull(ObdParser.dataBytes(0x01, Pids.SPEED, "410C1AF8\r>"))
    }

    @Test
    fun `маркер внутри данных не ловится по нечётному смещению`() {
        // 41 0C 41 0D — тут "410D" встречается со смещения 4, но это данные оборотов
        val data = ObdParser.dataBytes(0x01, Pids.SPEED, "410C410D\r>")
        // на чётном смещении 4 маркер действительно есть, но байтов после него нет
        assertNull(data)
    }

    @Test
    fun `нечётный hex не разбирается`() {
        assertNull(ObdParser.hexToBytes("41F"))
        assertArrayEquals(intArrayOf(0x41, 0x0F), ObdParser.hexToBytes("410F"))
    }

    @Test
    fun `битовая карта поддержки разбирается`() {
        // BE1FA813: старший бит первого байта = PID 01
        val data = ObdParser.hexToBytes("BE1FA813")!!
        val supported = ObdParser.supportedPids(0x00, data)
        assertTrue(Pids.SPEED in supported)
        assertTrue(Pids.RPM in supported)
        assertTrue(Pids.COOLANT_TEMP in supported)
        assertTrue(Pids.MAF in supported)
        // бит для PID 02 в BE сброшен
        assertTrue(0x02 !in supported)
    }

    @Test
    fun `битовая карта второго диапазона смещается на 0x20`() {
        val data = ObdParser.hexToBytes("80000000")!!
        assertEquals(setOf(0x21), ObdParser.supportedPids(0x20, data))
    }

    @Test
    fun `многострочный ответ от нескольких блоков`() {
        val raw = "410D3C\r410D3C\r\r>"
        assertEquals(60, Pids.speedKmh(ObdParser.dataBytes(0x01, Pids.SPEED, raw)!!))
    }
}
