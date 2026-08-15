package io.dodo.blescanner.ble

import io.dodo.blescanner.model.Detection
import io.dodo.blescanner.model.LocationFix
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Гео-математика без зависимости от android.location — чтобы логику можно было
 * покрыть обычными JVM-тестами.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Расстояние по большому кругу в метрах (формула гаверсинуса). */
    fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        // min(1.0, ...) страхует от выхода за область определения asin из-за округлений
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun distanceMeters(from: LocationFix, to: LocationFix): Double =
        distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
}

/**
 * Решает, записывать ли новое обнаружение как отдельную точку.
 *
 * Устройство «светится» несколько раз в секунду, и складывать каждый пакет
 * бессмысленно — точка добавляется, если прошло достаточно времени, если мы
 * заметно сдвинулись, либо если координаты появились впервые.
 */
object DetectionPolicy {

    /** Минимальный интервал между точками одного устройства. */
    const val MIN_TIME_GAP_MS = 60_000L

    /** Насколько нужно сместиться, чтобы точка считалась новой. */
    const val MIN_DISTANCE_M = 25.0

    /** Сколько точек храним на устройство. */
    const val MAX_DETECTIONS = 50

    fun shouldRecord(last: Detection?, now: Long, location: LocationFix?): Boolean {
        if (last == null) return true
        if (now - last.timeMs >= MIN_TIME_GAP_MS) return true

        val previous = last.location
        // координаты появились впервые — фиксируем, не дожидаясь таймаута
        if (previous == null) return location != null
        if (location == null) return false

        return Geo.distanceMeters(previous, location) >= MIN_DISTANCE_M
    }

    /** Добавляет точку, удерживая размер списка в пределах [MAX_DETECTIONS]. */
    fun append(detections: List<Detection>, detection: Detection): List<Detection> {
        val next = detections + detection
        return if (next.size > MAX_DETECTIONS) next.takeLast(MAX_DETECTIONS) else next
    }
}
