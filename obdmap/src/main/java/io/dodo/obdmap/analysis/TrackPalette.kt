package io.dodo.obdmap.analysis

/**
 * Раскраска трека. Цвета — обычные ARGB-числа, без зависимости от Compose:
 * их одинаково понимают и Canvas, и osmdroid, и юнит-тесты.
 */
object TrackPalette {

    /** Чем красим траекторию. */
    enum class Mode(val title: String) {
        SPEED("Скорость"),
        CONSUMPTION("Расход"),
        ACCELERATION("Ускорение"),
    }

    /** Полоса значений: всё, что меньше [upTo], красится в [color]. */
    data class Band(val upTo: Double?, val color: Int, val label: String)

    const val GREY = 0xFF9E9E9E.toInt()
    const val GREEN = 0xFF43A047.toInt()
    const val LIME = 0xFF9CCC65.toInt()
    const val YELLOW = 0xFFFDD835.toInt()
    const val ORANGE = 0xFFFB8C00.toInt()
    const val RED = 0xFFE53935.toInt()
    const val BLUE = 0xFF1E88E5.toInt()
    const val CYAN = 0xFF26C6DA.toInt()
    const val PURPLE = 0xFF8E24AA.toInt()

    /** Пороги скорости по умолчанию, км/ч. Выше 80 — синий, как и просили. */
    val DEFAULT_SPEED_THRESHOLDS = listOf(20.0, 60.0, 80.0, 110.0)

    /**
     * Полосы по скорости. [thresholds] — четыре возрастающих порога;
     * некорректный список молча заменяется значениями по умолчанию.
     */
    fun speedBands(thresholds: List<Double> = DEFAULT_SPEED_THRESHOLDS): List<Band> {
        val t = validThresholds(thresholds)
        return listOf(
            Band(t[0], GREY, "< ${fmt(t[0])}"),
            Band(t[1], GREEN, "${fmt(t[0])}–${fmt(t[1])}"),
            Band(t[2], YELLOW, "${fmt(t[1])}–${fmt(t[2])}"),
            Band(t[3], BLUE, "${fmt(t[2])}–${fmt(t[3])}"),
            Band(null, RED, "> ${fmt(t[3])}"),
        )
    }

    /** Полосы по расходу, л/100 км. */
    val CONSUMPTION_BANDS = listOf(
        Band(5.0, GREEN, "< 5"),
        Band(8.0, LIME, "5–8"),
        Band(12.0, YELLOW, "8–12"),
        Band(20.0, ORANGE, "12–20"),
        Band(null, RED, "> 20"),
    )

    /** Полосы по ускорению, м/с²: торможение — холодные цвета, разгон — тёплые. */
    val ACCELERATION_BANDS = listOf(
        Band(-2.0, PURPLE, "< -2"),
        Band(-0.5, CYAN, "-2…-0.5"),
        Band(0.5, GREY, "-0.5…0.5"),
        Band(2.0, ORANGE, "0.5…2"),
        Band(null, RED, "> 2"),
    )

    fun bands(mode: Mode, speedThresholds: List<Double> = DEFAULT_SPEED_THRESHOLDS): List<Band> =
        when (mode) {
            Mode.SPEED -> speedBands(speedThresholds)
            Mode.CONSUMPTION -> CONSUMPTION_BANDS
            Mode.ACCELERATION -> ACCELERATION_BANDS
        }

    /** Цвет для значения; null-значение (данных нет) красим серым. */
    fun colorOf(
        mode: Mode,
        value: Double?,
        speedThresholds: List<Double> = DEFAULT_SPEED_THRESHOLDS,
    ): Int {
        if (value == null) return GREY
        val list = bands(mode, speedThresholds)
        return list.firstOrNull { band -> band.upTo != null && value < band.upTo }?.color
            ?: list.last().color
    }

    /**
     * Опорные точки градиента: значение и цвет в нём. Между ними цвет
     * интерполируется покомпонентно, за краями — берётся крайний.
     */
    data class Stop(val value: Double, val color: Int)

    fun stops(mode: Mode, speedThresholds: List<Double> = DEFAULT_SPEED_THRESHOLDS): List<Stop> =
        when (mode) {
            Mode.SPEED -> {
                val t = validThresholds(speedThresholds)
                listOf(
                    Stop(0.0, GREY),
                    Stop(t[0], CYAN),
                    Stop(t[1], GREEN),
                    Stop(t[2], YELLOW),
                    Stop(t[3], ORANGE),
                    Stop(t[3] * 1.4, RED),
                )
            }

            Mode.CONSUMPTION -> listOf(
                Stop(3.0, GREEN),
                Stop(6.0, LIME),
                Stop(9.0, YELLOW),
                Stop(14.0, ORANGE),
                Stop(22.0, RED),
            )

            Mode.ACCELERATION -> listOf(
                Stop(-3.0, PURPLE),
                Stop(-1.0, CYAN),
                Stop(0.0, GREY),
                Stop(1.0, ORANGE),
                Stop(3.0, RED),
            )
        }

    /**
     * Цвет по градиенту. Плавный переход честнее полос: на границе полосы
     * трек менял цвет скачком там, где скорость изменилась на километр в час.
     */
    fun gradientColor(
        mode: Mode,
        value: Double?,
        speedThresholds: List<Double> = DEFAULT_SPEED_THRESHOLDS,
    ): Int {
        if (value == null) return GREY
        val list = stops(mode, speedThresholds)
        if (value <= list.first().value) return list.first().color
        if (value >= list.last().value) return list.last().color

        val upperIndex = list.indexOfFirst { it.value >= value }
        val upper = list[upperIndex]
        val lower = list[upperIndex - 1]
        val span = upper.value - lower.value
        val fraction = if (span <= 0) 0.0 else (value - lower.value) / span
        return blend(lower.color, upper.color, fraction)
    }

    /**
     * Квантование градиента для карты.
     *
     * Рисовать по отрезку на точку нельзя — это тысячи оверлеев. Поэтому цвет
     * округляется до [GRADIENT_STEPS] ступеней: соседние точки чаще всего
     * попадают в одну и склеиваются в общую ломаную, а глазу переход всё равно
     * читается как плавный.
     */
    fun quantizedGradientColor(
        mode: Mode,
        value: Double?,
        speedThresholds: List<Double> = DEFAULT_SPEED_THRESHOLDS,
        steps: Int = GRADIENT_STEPS,
    ): Int {
        if (value == null) return GREY
        val list = stops(mode, speedThresholds)
        val min = list.first().value
        val max = list.last().value
        val clamped = value.coerceIn(min, max)
        val bucket = if (max <= min) {
            0
        } else {
            ((clamped - min) / (max - min) * steps).toInt().coerceIn(0, steps)
        }
        val quantized = min + (max - min) * bucket / steps
        return gradientColor(mode, quantized, speedThresholds)
    }

    /** Сколько ступеней в квантованном градиенте. */
    const val GRADIENT_STEPS = 24

    /** Линейная интерполяция двух ARGB-цветов. */
    fun blend(from: Int, to: Int, fraction: Double): Int {
        val f = fraction.coerceIn(0.0, 1.0)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * f).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun validThresholds(thresholds: List<Double>): List<Double> =
        if (thresholds.size == 4 && thresholds.zipWithNext().all { it.first < it.second }) {
            thresholds
        } else {
            DEFAULT_SPEED_THRESHOLDS
        }

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
