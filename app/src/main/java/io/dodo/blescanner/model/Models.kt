package io.dodo.blescanner.model

/** Состояние работы с конкретным устройством. */
enum class DeviceState {
    /** Устройство найдено сканером, в очередь ещё не поставлено. */
    SEEN,

    /** Стоит в очереди на подключение. */
    QUEUED,

    /** Идёт подключение / чтение характеристик. */
    READING,

    /** Характеристики прочитаны успешно. */
    DONE,

    /** Последняя попытка чтения завершилась ошибкой. */
    ERROR,
}

/** Значение одной прочитанной характеристики. */
data class CharacteristicValue(
    val serviceUuid: String,
    val serviceName: String,
    val charUuid: String,
    val charName: String,
    /** Значение в hex, либо текст ошибки, если чтение не удалось. */
    val hex: String,
    /** Печатное представление, если его удалось получить (строка, число и т.п.). */
    val decoded: String?,
    val ok: Boolean,
)

/** Найденное BLE-устройство и всё, что о нём известно. */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenCount: Int,
    val state: DeviceState,
    val values: List<CharacteristicValue> = emptyList(),
    val lastError: String? = null,
    val lastReadAt: Long? = null,
    val attempts: Int = 0,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "(без имени)"
}
