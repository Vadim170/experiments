package io.dodo.obdmap.trip

/**
 * Ускорение по ряду замеров скорости.
 *
 * Скорость с шины приходит целыми км/ч, то есть шагом 0.278 м/с. При опросе
 * раз в 250 мс разность соседних значений даёт ±1.1 м/с² шума на ровном ходу —
 * поэтому считаем наклон методом наименьших квадратов по окну в полторы
 * секунды, а не разностью двух соседних замеров.
 */
class AccelerationTracker(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val minSpanMs: Long = DEFAULT_MIN_SPAN_MS,
) {

    companion object {
        const val DEFAULT_WINDOW_MS = 1_500L

        /** Короче этого промежутка наклон считать бессмысленно — один шум. */
        const val DEFAULT_MIN_SPAN_MS = 700L
    }

    private val times = ArrayDeque<Long>()
    private val speedsMs = ArrayDeque<Double>()

    /**
     * @param speedKmh скорость замера; null — данных нет, окно не обновляем
     * @return ускорение в м/с² или null, пока окно не набралось
     */
    fun add(timeMs: Long, speedKmh: Double?): Double? {
        if (speedKmh == null) return null

        // Время назад — значит, счётчик сбросился: начинаем окно заново
        if (times.isNotEmpty() && timeMs < times.last()) reset()

        times.addLast(timeMs)
        speedsMs.addLast(speedKmh / 3.6)

        while (times.size > 2 && timeMs - times.first() > windowMs) {
            times.removeFirst()
            speedsMs.removeFirst()
        }

        val span = times.last() - times.first()
        if (times.size < 3 || span < minSpanMs) return null
        return slope()
    }

    fun reset() {
        times.clear()
        speedsMs.clear()
    }

    /** Наклон прямой v(t) методом наименьших квадратов, м/с². */
    private fun slope(): Double? {
        val n = times.size
        val t0 = times.first()
        var sumT = 0.0
        var sumV = 0.0
        var sumTT = 0.0
        var sumTV = 0.0
        for (i in 0 until n) {
            val t = (times.elementAt(i) - t0) / 1000.0
            val v = speedsMs.elementAt(i)
            sumT += t
            sumV += v
            sumTT += t * t
            sumTV += t * v
        }
        val denominator = n * sumTT - sumT * sumT
        if (denominator == 0.0) return null
        return (n * sumTV - sumT * sumV) / denominator
    }
}
