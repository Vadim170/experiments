package io.dodo.obdmap.util

import android.content.Context
import io.dodo.obdmap.analysis.TrackPalette

/** Настройки: какой адаптер использовать и параметры двигателя для оценки расхода. */
object Prefs {

    private const val FILE = "obdmap"
    private const val KEY_ADAPTER_ADDRESS = "adapter_address"
    private const val KEY_ADAPTER_NAME = "adapter_name"
    private const val KEY_DISPLACEMENT = "displacement_l"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_AUTO_MODE = "auto_mode"
    private const val KEY_SPEED_THRESHOLDS = "speed_thresholds"

    fun adapterAddress(context: Context): String? =
        prefs(context).getString(KEY_ADAPTER_ADDRESS, null)

    fun adapterName(context: Context): String? =
        prefs(context).getString(KEY_ADAPTER_NAME, null)

    fun setAdapter(context: Context, address: String, name: String?) {
        prefs(context).edit()
            .putString(KEY_ADAPTER_ADDRESS, address)
            .putString(KEY_ADAPTER_NAME, name)
            .apply()
    }

    /** Объём двигателя в литрах — нужен только для оценки speed-density. */
    fun displacementLiters(context: Context): Float =
        prefs(context).getFloat(KEY_DISPLACEMENT, 1.598f)

    fun setDisplacementLiters(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_DISPLACEMENT, value).apply()
    }

    /** Автоматический старт и стоп поездок. */
    fun autoMode(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_MODE, false)

    fun setAutoMode(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_MODE, value).apply()
    }

    /** Чем красить траекторию: имя элемента TrackPalette.Mode. */
    fun colorMode(context: Context): String? = prefs(context).getString(KEY_COLOR_MODE, null)

    fun setColorMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_COLOR_MODE, mode).apply()
    }

    /** Четыре порога скорости для раскраски, км/ч. */
    fun speedThresholds(context: Context): List<Double> {
        val stored = prefs(context).getString(KEY_SPEED_THRESHOLDS, null)
            ?: return TrackPalette.DEFAULT_SPEED_THRESHOLDS
        val parsed = stored.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        return if (parsed.size == 4) parsed else TrackPalette.DEFAULT_SPEED_THRESHOLDS
    }

    fun setSpeedThresholds(context: Context, thresholds: List<Double>) {
        prefs(context).edit()
            .putString(KEY_SPEED_THRESHOLDS, thresholds.joinToString(","))
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
