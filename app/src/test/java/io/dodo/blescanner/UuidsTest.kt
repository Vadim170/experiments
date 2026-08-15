package io.dodo.blescanner

import io.dodo.blescanner.ble.Uuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UuidsTest {

    private fun sig(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    @Test
    fun `известные службы называются по-человечески`() {
        assertEquals("Device Information", Uuids.serviceName(sig("180a")))
        assertEquals("Battery Service", Uuids.serviceName(sig("180f")))
    }

    @Test
    fun `неизвестная SIG-служба показывается коротким id`() {
        assertEquals("Служба 0x1899", Uuids.serviceName(sig("1899")))
    }

    @Test
    fun `кастомный UUID не притворяется стандартным`() {
        val custom = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        assertEquals(custom.toString(), Uuids.characteristicName(custom))
        assertTrue(Uuids.serviceName(custom).startsWith("Служба "))
    }

    @Test
    fun `уровень заряда разбирается в проценты`() {
        assertEquals("87 %", Uuids.decode(sig("2a19"), byteArrayOf(87)))
    }

    @Test
    fun `строковые характеристики читаются как текст`() {
        val bytes = "ACME-42".toByteArray(Charsets.US_ASCII)
        assertEquals("ACME-42", Uuids.decode(sig("2a29"), bytes))
    }

    @Test
    fun `нулевой терминатор отбрасывается`() {
        val bytes = "Sensor".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)
        assertEquals("Sensor", Uuids.decode(sig("2a00"), bytes))
    }

    @Test
    fun `температура считается в сотых долях градуса`() {
        // 2350 = 23.50 °C, little-endian
        assertEquals("23.50 °C", Uuids.decode(sig("2a6e"), byteArrayOf(0x2E, 0x09)))
    }

    @Test
    fun `отрицательная температура не переполняется`() {
        // -500 = -5.00 °C
        assertEquals("-5.00 °C", Uuids.decode(sig("2a6e"), byteArrayOf(0x0C.toByte(), 0xFE.toByte())))
    }

    @Test
    fun `бинарный мусор в текст не превращается`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte(), 0x10, 0x20, 0x30)
        assertNull(Uuids.decode(UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"), bytes))
    }

    @Test
    fun `hex печатается по байтам`() {
        assertEquals("00 0F A5 FF", Uuids.toHex(byteArrayOf(0, 0x0F, 0xA5.toByte(), 0xFF.toByte())))
        assertEquals("(пусто)", Uuids.toHex(ByteArray(0)))
    }

    @Test
    fun `пустое значение не разбирается`() {
        assertNull(Uuids.decode(sig("2a19"), ByteArray(0)))
    }
}
