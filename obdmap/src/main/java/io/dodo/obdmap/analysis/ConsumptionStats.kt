package io.dodo.obdmap.analysis

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Один замер для анализа: скорость, мгновенный расход и ускорение в этот момент. */
data class DriveSample(
    val speedKmh: Double,
    val litersPer100Km: Double,
    val accelerationMs2: Double?,
)

/** Столбик гистограммы: полуинтервал [from, to) и сколько замеров в него попало. */
data class HistogramBin(val from: Double, val to: Double, val count: Int)

/** Итоги по одному диапазону скорости. */
data class SpeedBinStats(
    val speedFrom: Double,
    val speedTo: Double,
    val count: Int,
    val median: Double,
    val p25: Double,
    val p75: Double,
)

/**
 * Разбор истории: какой расход получается на какой скорости.
 *
 * Главная мысль — отфильтровать разгоны и торможения. На разгоне мгновенный
 * расход в разы выше установившегося, и без фильтра медиана по «скорости 60»
 * смешивает равномерное движение с разгоном до сотни.
 */
object ConsumptionStats {

    /** Ускорение, ниже которого считаем движение установившимся, м/с². */
    const val STEADY_ACCELERATION_MS2 = 0.2

    /** Меньше этого числа замеров в корзине — статистика недостоверна. */
    const val MIN_BIN_COUNT = 5

    /**
     * Максимальный правдоподобный мгновенный расход, л/100 км.
     *
     * У самой границы отсечки по скорости величина улетает в сотни: это не
     * данные, а деление на почти ноль. Такие замеры портят и гистограмму,
     * и медианы, поэтому отсекаются на входе.
     */
    const val MAX_PLAUSIBLE_L100 = 60.0

    /**
     * Отбор замеров.
     *
     * @param maxAbsAcceleration порог |a|; null — не фильтровать по ускорению
     * @param requireAcceleration отбрасывать ли замеры, у которых ускорение
     *   неизвестно. При жёстком фильтре это важно: замер без ускорения мог быть
     *   и разгоном.
     */
    fun filter(
        samples: List<DriveSample>,
        minSpeedKmh: Double,
        maxSpeedKmh: Double,
        maxAbsAcceleration: Double? = null,
        requireAcceleration: Boolean = true,
    ): List<DriveSample> = samples.filter { sample ->
        if (sample.litersPer100Km > MAX_PLAUSIBLE_L100) return@filter false
        if (sample.speedKmh < minSpeedKmh || sample.speedKmh > maxSpeedKmh) return@filter false
        if (maxAbsAcceleration == null) return@filter true
        val acceleration = sample.accelerationMs2
            ?: return@filter !requireAcceleration
        abs(acceleration) <= maxAbsAcceleration
    }

    /**
     * Гистограмма по «рабочему» диапазону значений, с автоматическим шагом.
     *
     * Считать по сырому min..max нельзя: мгновенный расход у самой границы
     * отсечки (3 км/ч) улетает в сотни л/100 км, и гистограмма из тысячи
     * корзин превращается в пустой прямоугольник. Поэтому края обрезаются по
     * перцентилям, а число корзин фиксировано.
     *
     * @param targetBins сколько корзин хотим получить
     * @param trim какую долю отбрасывать с каждого края
     */
    fun trimmedHistogram(
        values: List<Double>,
        targetBins: Int = DEFAULT_BINS,
        trim: Double = DEFAULT_TRIM,
    ): List<HistogramBin> {
        if (values.size < 2 || targetBins < 1) return histogram(values, 1.0)
        val low = percentile(values, trim) ?: return emptyList()
        val high = percentile(values, 1 - trim) ?: return emptyList()
        if (high <= low) return histogram(values, 1.0)

        val inRange = values.filter { it in low..high }
        if (inRange.isEmpty()) return emptyList()

        val width = niceStep((high - low) / targetBins)
        return histogram(inRange, width)
    }

    /** Доля значений, отбрасываемая с каждого края перед построением гистограммы. */
    const val DEFAULT_TRIM = 0.02

    /** Целевое число корзин гистограммы. */
    const val DEFAULT_BINS = 30

    /**
     * Округляет шаг до «человеческого»: 0.1 / 0.2 / 0.5 / 1 / 2 / 5 …
     * Иначе подписи под гистограммой получаются вида 0.4713.
     */
    fun niceStep(raw: Double): Double {
        if (raw <= 0) return 1.0
        var magnitude = 1.0
        while (magnitude > raw) magnitude /= 10
        while (magnitude * 10 <= raw) magnitude *= 10
        val normalized = raw / magnitude
        val step = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return step * magnitude
    }

    /** Гистограмма значений с шагом [binWidth]; пустые корзины внутри диапазона сохраняются. */
    fun histogram(values: List<Double>, binWidth: Double): List<HistogramBin> {
        if (values.isEmpty() || binWidth <= 0) return emptyList()
        val min = floor(values.min() / binWidth) * binWidth
        val max = ceil(values.max() / binWidth) * binWidth
        val binCount = ((max - min) / binWidth).toInt().coerceAtLeast(1)

        val counts = IntArray(binCount)
        values.forEach { value ->
            val index = ((value - min) / binWidth).toInt().coerceIn(0, binCount - 1)
            counts[index]++
        }
        return counts.mapIndexed { index, count ->
            HistogramBin(min + index * binWidth, min + (index + 1) * binWidth, count)
        }
    }

    /** Медиана. Для чётной длины — среднее двух середин. */
    fun median(values: List<Double>): Double? = percentile(values, 0.5)

    /** Перцентиль [fraction] ∈ [0,1] линейной интерполяцией между соседями. */
    fun percentile(values: List<Double>, fraction: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val position = fraction.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val weight = position - lower
        return sorted[lower] * (1 - weight) + sorted[upper] * weight
    }

    /**
     * Расход по диапазонам скорости — та самая «зависимость расхода от
     * стабильной скорости», если подать сюда отфильтрованные по ускорению замеры.
     */
    fun bySpeedBin(
        samples: List<DriveSample>,
        binKmh: Double,
        minCount: Int = MIN_BIN_COUNT,
    ): List<SpeedBinStats> {
        if (samples.isEmpty() || binKmh <= 0) return emptyList()
        return samples
            .groupBy { floor(it.speedKmh / binKmh).toInt() }
            .toSortedMap()
            .mapNotNull { (index, group) ->
                if (group.size < minCount) return@mapNotNull null
                val consumption = group.map { it.litersPer100Km }
                SpeedBinStats(
                    speedFrom = index * binKmh,
                    speedTo = (index + 1) * binKmh,
                    count = group.size,
                    median = median(consumption) ?: return@mapNotNull null,
                    p25 = percentile(consumption, 0.25) ?: return@mapNotNull null,
                    p75 = percentile(consumption, 0.75) ?: return@mapNotNull null,
                )
            }
    }
}
