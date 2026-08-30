package io.dodo.obdmap.trip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.dodo.obdmap.util.Logger
import io.dodo.obdmap.util.Prefs

/**
 * Поднимает ожидание поездки после перезагрузки телефона: иначе
 * «автоматически» заканчивается на первом ребуте. BOOT_COMPLETED входит в
 * список исключений, при которых foreground-сервис можно стартовать из фона.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.autoMode(context)) return
        val address = Prefs.adapterAddress(context) ?: return
        Logger.init(context.applicationContext)
        Logger.log("перезагрузка — снова жду адаптер")
        TripService.start(context.applicationContext, address, auto = true)
    }
}
