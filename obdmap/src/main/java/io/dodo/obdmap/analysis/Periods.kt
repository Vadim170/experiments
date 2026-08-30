package io.dodo.obdmap.analysis

import java.util.Calendar
import java.util.TimeZone

/**
 * Разбиение замеров на периоды для сравнения кривых расхода.
 *
 * Смысл — увидеть, что кривая поднялась или изогнулась: зимой, на другом
 * бензине, после смены резины. Одна кривая этого не показывает, нужны две
 * рядом.
 */
object Periods {

    enum class Mode(val title: String) {
        MONTH("По месяцам"),
        WEEKDAY("По дням недели"),
        HALVES("Раньше и позже"),
    }

    private val MONTHS = arrayOf(
        "янв", "фев", "мар", "апр", "май", "июн",
        "июл", "авг", "сен", "окт", "ноя", "дек",
    )

    private val WEEKDAYS = arrayOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    /** Подпись периода для замера. */
    fun label(timeMs: Long, mode: Mode): String {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = timeMs
        return when (mode) {
            Mode.MONTH -> "${MONTHS[calendar.get(Calendar.MONTH)]} ${calendar.get(Calendar.YEAR)}"
            // Calendar считает воскресенье первым днём, приводим к пн..вс
            Mode.WEEKDAY -> WEEKDAYS[(calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7]
            Mode.HALVES -> ""
        }
    }

    /** Ключ для сортировки: месяцы по времени, дни недели с понедельника. */
    private fun order(timeMs: Long, mode: Mode): Long {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.timeInMillis = timeMs
        return when (mode) {
            Mode.MONTH -> calendar.get(Calendar.YEAR) * 12L + calendar.get(Calendar.MONTH)
            Mode.WEEKDAY -> ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7).toLong()
            Mode.HALVES -> 0
        }
    }

    /**
     * Группирует замеры по периодам в осмысленном порядке.
     *
     * @param maxGroups сколько групп показываем; лишние (самые старые) отбрасываем,
     *   иначе на графике получится каша из десятка линий
     */
    fun groups(
        samples: List<DriveSample>,
        mode: Mode,
        maxGroups: Int = DEFAULT_MAX_GROUPS,
    ): List<Pair<String, List<DriveSample>>> {
        if (samples.isEmpty()) return emptyList()

        if (mode == Mode.HALVES) {
            // Делим пополам по времени: половина замеров «раньше», половина «позже»
            val sorted = samples.sortedBy { it.timeMs }
            val middle = sorted.size / 2
            if (middle == 0) return emptyList()
            return listOf(
                "раньше" to sorted.take(middle),
                "позже" to sorted.drop(middle),
            )
        }

        val grouped = samples.groupBy { label(it.timeMs, mode) }
        val ordered = grouped.entries.sortedBy { entry ->
            entry.value.minOf { order(it.timeMs, mode) }
        }
        // Для месяцев интереснее свежие, поэтому лишние режем с начала
        val trimmed = if (mode == Mode.MONTH && ordered.size > maxGroups) {
            ordered.takeLast(maxGroups)
        } else {
            ordered.take(maxGroups)
        }
        return trimmed.map { it.key to it.value }
    }

    /** Больше этого числа кривых на одном поле уже не читается. */
    const val DEFAULT_MAX_GROUPS = 4
}
