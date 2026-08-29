package io.dodo.obdmap.trip

import io.dodo.obdmap.obd.FuelMath
import io.dodo.obdmap.util.Geo

/** Один замер: время, координаты и мгновенные показатели. Любое поле может отсутствовать. */
data class TripSample(
    val timeMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmh: Double? = null,
    val fuelRateLitersPerHour: Double? = null,
)

/** Итоги поездки. */
data class TripStats(
    val distanceMeters: Double = 0.0,
    val fuelLiters: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val movingMillis: Long = 0,
    val idleMillis: Long = 0,
) {
    val durationMillis: Long get() = movingMillis + idleMillis

    /** Средний расход, л/100 км. Null на слишком коротком пробеге. */
    val averageLitersPer100Km: Double?
        get() = FuelMath.averageLitersPer100Km(fuelLiters, distanceMeters)

    /** Средняя скорость в движении, км/ч. */
    val averageSpeedKmh: Double
        get() = if (movingMillis <= 0) 0.0 else distanceMeters / 1000.0 / (movingMillis / 3_600_000.0)
}

/**
 * Накопитель статистики поездки. Чистая логика без Android — считается как в
 * сервисе на живых данных, так и в тестах на заранее собранной последовательности.
 *
 * Пробег берём по GPS: интеграл скорости с OBD копит ошибку, а на стоянке
 * ещё и «едет» из-за шума. Если координат нет, откатываемся на скорость.
 */
class TripAccumulator {

    private companion object {
        /** Разрыв больше этого не интегрируем: связь пропадала, что было — неизвестно. */
        const val MAX_GAP_MS = 30_000L

        /** Смещение, дающее скорость выше этой, считаем выбросом GPS. */
        const val MAX_PLAUSIBLE_SPEED_KMH = 300.0

        /** Ниже этой скорости считаем, что стоим. */
        const val IDLE_SPEED_KMH = 2.0

        /** Смещения мельче этого на стоянке — дрожание GPS, не пробег. */
        const val GPS_NOISE_M = 3.0
    }

    private var previous: TripSample? = null

    var stats: TripStats = TripStats()
        private set

    fun add(sample: TripSample) {
        val last = previous
        previous = sample

        sample.speedKmh?.let { speed ->
            if (speed > stats.maxSpeedKmh) stats = stats.copy(maxSpeedKmh = speed)
        }

        if (last == null) return
        val deltaMs = sample.timeMs - last.timeMs
        if (deltaMs <= 0 || deltaMs > MAX_GAP_MS) return

        stats = stats.copy(
            distanceMeters = stats.distanceMeters + segmentMeters(last, sample, deltaMs),
            fuelLiters = stats.fuelLiters + segmentLiters(last, sample, deltaMs),
        )

        val speed = sample.speedKmh ?: last.speedKmh ?: 0.0
        stats = if (speed >= IDLE_SPEED_KMH) {
            stats.copy(movingMillis = stats.movingMillis + deltaMs)
        } else {
            stats.copy(idleMillis = stats.idleMillis + deltaMs)
        }
    }

    private fun segmentMeters(from: TripSample, to: TripSample, deltaMs: Long): Double {
        val fromLat = from.latitude
        val fromLon = from.longitude
        val toLat = to.latitude
        val toLon = to.longitude

        if (fromLat != null && fromLon != null && toLat != null && toLon != null) {
            val meters = Geo.distanceMeters(fromLat, fromLon, toLat, toLon)
            val impliedSpeedKmh = meters / (deltaMs / 1000.0) * 3.6
            return when {
                impliedSpeedKmh > MAX_PLAUSIBLE_SPEED_KMH -> 0.0
                meters < GPS_NOISE_M -> 0.0
                else -> meters
            }
        }

        // Координат нет — интегрируем скорость с OBD по трапеции.
        val fromSpeed = from.speedKmh ?: return 0.0
        val toSpeed = to.speedKmh ?: return 0.0
        return (fromSpeed + toSpeed) / 2.0 / 3.6 * (deltaMs / 1000.0)
    }

    private fun segmentLiters(from: TripSample, to: TripSample, deltaMs: Long): Double {
        val fromRate = from.fuelRateLitersPerHour ?: return 0.0
        val toRate = to.fuelRateLitersPerHour ?: return 0.0
        return (fromRate + toRate) / 2.0 * (deltaMs / 3_600_000.0)
    }
}

/** Пересчёт итогов по готовой последовательности замеров. */
fun statsOf(samples: List<TripSample>): TripStats {
    val accumulator = TripAccumulator()
    samples.forEach(accumulator::add)
    return accumulator.stats
}
