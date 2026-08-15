package io.dodo.blescanner.ble

import java.util.Locale
import java.util.UUID

/**
 * Человекочитаемые имена для стандартных SIG-овских UUID и разбор их значений.
 * Список неполный — только то, что реально встречается у большинства устройств.
 */
object Uuids {

    private val SERVICES = mapOf(
        0x1800 to "Generic Access",
        0x1801 to "Generic Attribute",
        0x1802 to "Immediate Alert",
        0x1803 to "Link Loss",
        0x1804 to "Tx Power",
        0x180A to "Device Information",
        0x180D to "Heart Rate",
        0x180F to "Battery Service",
        0x1810 to "Blood Pressure",
        0x1812 to "HID",
        0x181A to "Environmental Sensing",
        0x181B to "Body Composition",
        0x181C to "User Data",
        0x1826 to "Fitness Machine",
    )

    private val CHARACTERISTICS = mapOf(
        0x2A00 to "Device Name",
        0x2A01 to "Appearance",
        0x2A04 to "Preferred Connection Parameters",
        0x2A05 to "Service Changed",
        0x2A07 to "Tx Power Level",
        0x2A19 to "Battery Level",
        0x2A23 to "System ID",
        0x2A24 to "Model Number",
        0x2A25 to "Serial Number",
        0x2A26 to "Firmware Revision",
        0x2A27 to "Hardware Revision",
        0x2A28 to "Software Revision",
        0x2A29 to "Manufacturer Name",
        0x2A2A to "IEEE 11073-20601 Reg. Cert.",
        0x2A50 to "PnP ID",
        0x2A6E to "Temperature",
        0x2A6F to "Humidity",
        0x2A6D to "Pressure",
        0x2A37 to "Heart Rate Measurement",
        0x2A38 to "Body Sensor Location",
    )

    /** Характеристики, значение которых по спецификации — обычная UTF-8 строка. */
    private val STRING_CHARACTERISTICS = setOf(
        0x2A00, 0x2A24, 0x2A25, 0x2A26, 0x2A27, 0x2A28, 0x2A29,
    )

    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** Возвращает 16-битный short-UUID, если это стандартный SIG-овский UUID. */
    private fun shortId(uuid: UUID): Int? {
        val text = uuid.toString().lowercase()
        if (!text.endsWith(BASE_SUFFIX)) return null
        if (!text.startsWith("0000")) return null
        return text.substring(4, 8).toIntOrNull(16)
    }

    // Везде Locale.US: иначе на русской локали дробные значения уезжают на запятую,
    // а лог и geo:-ссылки перестают разбираться.
    private fun fmt(format: String, vararg args: Any?) = String.format(Locale.US, format, *args)

    fun serviceName(uuid: UUID): String {
        val id = shortId(uuid) ?: return "Служба ${uuid.toString().take(8)}…"
        return SERVICES[id] ?: fmt("Служба 0x%04X", id)
    }

    fun characteristicName(uuid: UUID): String {
        val id = shortId(uuid) ?: return uuid.toString()
        return CHARACTERISTICS[id] ?: fmt("0x%04X", id)
    }

    fun toHex(bytes: ByteArray): String =
        if (bytes.isEmpty()) "(пусто)" else bytes.joinToString(" ") { fmt("%02X", it) }

    /**
     * Пытается получить осмысленное представление значения: сначала по известному
     * UUID, потом — эвристикой (печатная строка / короткое целое).
     */
    fun decode(uuid: UUID, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val id = shortId(uuid)
        if (id != null) {
            if (id in STRING_CHARACTERISTICS) return asText(bytes)
            when (id) {
                0x2A19 -> return "${bytes[0].toInt() and 0xFF} %"
                0x2A07 -> return "${bytes[0].toInt()} dBm"
                0x2A01 -> if (bytes.size >= 2) return fmt("0x%04X", le16(bytes, 0))
                0x2A6E -> if (bytes.size >= 2) return fmt("%.2f °C", le16Signed(bytes, 0) / 100.0)
                0x2A6F -> if (bytes.size >= 2) return fmt("%.2f %%", le16(bytes, 0) / 100.0)
            }
        }

        asText(bytes)?.let { return it }
        return when (bytes.size) {
            1 -> "${bytes[0].toInt() and 0xFF}"
            2 -> "${le16(bytes, 0)}"
            4 -> "${le32(bytes, 0)}"
            else -> null
        }
    }

    /** Строка, если все байты — печатный ASCII (нулевой терминатор отбрасываем). */
    private fun asText(bytes: ByteArray): String? {
        val trimmed = bytes.takeWhile { it != 0.toByte() }.toByteArray()
        if (trimmed.isEmpty()) return null
        val printable = trimmed.all { (it.toInt() and 0xFF) in 0x20..0x7E }
        if (!printable) return null
        val text = String(trimmed, Charsets.US_ASCII)
        return if (text.any { it.isLetterOrDigit() }) text else null
    }

    private fun le16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun le16Signed(b: ByteArray, offset: Int): Int = le16(b, offset).toShort().toInt()

    private fun le32(b: ByteArray, offset: Int): Long =
        (b[offset].toLong() and 0xFF) or
            ((b[offset + 1].toLong() and 0xFF) shl 8) or
            ((b[offset + 2].toLong() and 0xFF) shl 16) or
            ((b[offset + 3].toLong() and 0xFF) shl 24)
}
