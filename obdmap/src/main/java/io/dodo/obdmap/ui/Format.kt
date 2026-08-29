package io.dodo.obdmap.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Форматирование чисел для интерфейса.
 *
 * Locale.US везде намеренно: иначе разделитель дробной части пляшет вместе с
 * языком системы, и одни и те же цифры в логе, на экране и в базе выглядят
 * по-разному.
 */
internal object Fmt {

    private val dateTime = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun km(meters: Double): String = String.format(Locale.US, "%.1f км", meters / 1000)

    fun speed(kmh: Double?): String =
        kmh?.let { String.format(Locale.US, "%.0f", it) } ?: "—"

    fun litersPer100(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

    fun litersPerHour(value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f л/ч", it) } ?: "—"

    fun liters(value: Double): String = String.format(Locale.US, "%.2f л", value)

    fun percent(value: Double?): String =
        value?.let { "${it.roundToInt()} %" } ?: "—"

    fun rpm(value: Double?): String = value?.let { "${it.roundToInt()}" } ?: "—"

    fun temp(value: Int?): String = value?.let { "$it °C" } ?: "—"

    fun duration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun dateTime(millis: Long): String = dateTime.format(Date(millis))

    fun time(millis: Long): String = time.format(Date(millis))
}
