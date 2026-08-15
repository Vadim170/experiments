package io.dodo.blescanner.ble

import android.content.Context

/** Запоминаем, что пользователь включил сканирование, чтобы поднять его после ребута. */
object Prefs {

    private const val FILE = "ble_scanner"
    private const val KEY_AUTO_START = "auto_start"

    fun autoStart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, false)

    fun setAutoStart(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
