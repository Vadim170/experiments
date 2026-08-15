package io.dodo.blescanner.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Поднимает сканирование после перезагрузки телефона — иначе «постоянно в фоне»
 * заканчивается на первом ребуте. BOOT_COMPLETED входит в список исключений,
 * при которых foreground-сервис можно стартовать из фона.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.autoStart(context)) return
        BleLogger.init(context.applicationContext)
        BleLogger.log("перезагрузка — запускаю сервис")
        runCatching { BleScanService.start(context.applicationContext) }
            .onFailure { BleLogger.logError("не удалось стартовать после ребута", it) }
    }
}
