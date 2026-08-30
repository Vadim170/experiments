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
        val t = if (thresholds.size == 4 && thresholds.zipWithNext().all { it.first < it.second }) {
            thresholds
        } else {
            DEFAULT_SPEED_THRESHOLDS
        }
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

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
