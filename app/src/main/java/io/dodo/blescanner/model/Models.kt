package io.dodo.blescanner.model

import java.util.Locale

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

/** Координаты на момент обнаружения. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Заявленная точность в метрах; null, если провайдер её не дал. */
    val accuracyMeters: Float?,
    /** gps / network / fused — чем получено. */
    val provider: String,
    /** Время самого фикса, а не момента использования. */
    val timeMs: Long,
) {
    // Locale.US принципиально: на русской локали "%.6f" даёт запятую,
    // и такие координаты ломают geo:-ссылку и разбор лога.
    fun format(): String = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    fun formatWithAccuracy(): String =
        accuracyMeters?.let { "${format()} ±${it.toInt()} м" } ?: format()
}

/** Одно зафиксированное обнаружение устройства с привязкой к месту. */
data class Detection(
    val timeMs: Long,
    val rssi: Int,
    /** null, если фикса на этот момент ещё не было (гео выключено, не успели поймать). */
    val location: LocationFix?,
)

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
    /** Точки обнаружения, самая свежая — последняя. Список ограничен сверху. */
    val detections: List<Detection> = emptyList(),
    val values: List<CharacteristicValue> = emptyList(),
    val lastError: String? = null,
    val lastReadAt: Long? = null,
    val attempts: Int = 0,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "(без имени)"

    /** Последние известные координаты устройства. */
    val lastLocation: LocationFix? get() = detections.lastOrNull { it.location != null }?.location

    val firstLocation: LocationFix? get() = detections.firstOrNull { it.location != null }?.location
}
