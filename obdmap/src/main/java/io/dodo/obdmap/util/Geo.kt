package io.dodo.obdmap.util

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Гео-математика без android.location — чтобы расчёт трека покрывался
 * обычными JVM-тестами.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Расстояние по большому кругу в метрах (формула гаверсинуса). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Курс из первой точки во вторую: 0° — север, 90° — восток, по часовой.
     * Нужен, чтобы развернуть машинку на карте по направлению движения.
     */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
