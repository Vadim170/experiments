package io.dodo.obdmap.obd

/**
 * Разбор ответов ELM327.
 *
 * Адаптер отвечает свободным текстом, и на разных клонах формат гуляет:
 * с пробелами и без, с эхом команды и без, с заголовками и без, иногда с
 * префиксом SEARCHING... Поэтому парсер намеренно терпимый: чистит строки,
 * склеивает hex и ищет маркер ответа (0x40+режим, PID) по чётному смещению.
 */
object ObdParser {

    /** Служебные ответы адаптера, которые не являются данными. */
    private val STATUS_WORDS = listOf(
        "SEARCHING",
        "NO DATA",
        "UNABLE TO CONNECT",
        "STOPPED",
        "CAN ERROR",
        "BUS INIT",
        "BUS ERROR",
        "BUFFER FULL",
        "DATA ERROR",
        "ERROR",
        "?",
    )

    /** Ответ означает ошибку — вернёт человекочитаемую причину, иначе null. */
    fun errorOf(raw: String): String? {
        val text = raw.uppercase()
        // Приглашение '>' приходит в той же строке, что и ответ
        val bare = text.replace(">", "").trim()
        return when {
            text.contains("UNABLE TO CONNECT") -> "адаптер не видит шину авто"
            text.contains("NO DATA") -> "блок не отдал данные (PID не поддерживается)"
            text.contains("CAN ERROR") -> "ошибка шины CAN"
            text.contains("BUS INIT") && text.contains("ERROR") -> "не удалось инициализировать шину"
            text.contains("BUS ERROR") -> "ошибка шины"
            text.contains("BUFFER FULL") -> "переполнен буфер адаптера"
            text.contains("DATA ERROR") -> "ошибка данных"
            text.contains("STOPPED") -> "запрос прерван"
            bare == "?" -> "адаптер не понял команду"
            else -> null
        }
    }

    /** Разбивает сырой ответ на значимые строки: без эха, приглашения и мусора. */
    fun meaningfulLines(raw: String, echoOf: String? = null): List<String> {
        val echo = echoOf?.replace(" ", "")?.uppercase()
        return raw.split('\r', '\n')
            .map { it.trim().removePrefix(">").trim() }
            .filter { it.isNotEmpty() }
            .map { it.uppercase() }
            .filter { line ->
                val compact = line.replace(" ", "")
                compact != echo &&
                    compact != "OK" &&
                    STATUS_WORDS.none { word -> line.startsWith(word) }
            }
    }

    /**
     * Достаёт полезную нагрузку ответа на запрос [mode]/[pid].
     *
     * @return байты после маркера ответа либо null, если ответа нет.
     */
    fun dataBytes(mode: Int, pid: Int, raw: String, echoOf: String? = null): IntArray? {
        val marker = "%02X%02X".format(mode + 0x40, pid)

        meaningfulLines(raw, echoOf).forEach { line ->
            val hex = line.filter { it.isDigit() || it in 'A'..'F' }
            var index = hex.indexOf(marker)
            while (index >= 0) {
                // Маркер обязан лежать на границе байта, но отсчитывать её надо
                // с конца: заголовок кадра 11-битного CAN (7E8) занимает три
                // hex-символа и сдвигает всю строку на нечётную позицию.
                if ((hex.length - index) % 2 == 0) {
                    val payload = hex.substring(index + marker.length)
                    val bytes = hexToBytes(payload)
                    if (bytes != null && bytes.isNotEmpty()) return bytes
                }
                index = hex.indexOf(marker, index + 1)
            }
        }
        return null
    }

    /** Пары hex-символов в байты; null, если длина нечётная. */
    fun hexToBytes(hex: String): IntArray? {
        if (hex.length % 2 != 0) return null
        return IntArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
        }
    }

    /**
     * Разбирает битовую карту поддерживаемых PID.
     *
     * [base] — начало диапазона (0x00, 0x20, 0x40). Старший бит первого байта
     * соответствует PID base+1.
     */
    fun supportedPids(base: Int, data: IntArray): Set<Int> {
        val result = mutableSetOf<Int>()
        data.take(4).forEachIndexed { byteIndex, value ->
            for (bit in 0 until 8) {
                // бит 7 первого байта = base+1, бит 0 = base+8
                if (value and (1 shl (7 - bit)) != 0) {
                    result += base + byteIndex * 8 + bit + 1
                }
            }
        }
        return result
    }
}
