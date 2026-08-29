package io.dodo.obdmap.util

import android.content.Context

/** Настройки: какой адаптер использовать и параметры двигателя для оценки расхода. */
object Prefs {

    private const val FILE = "obdmap"
    private const val KEY_ADAPTER_ADDRESS = "adapter_address"
    private const val KEY_ADAPTER_NAME = "adapter_name"
    private const val KEY_DISPLACEMENT = "displacement_l"

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

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
