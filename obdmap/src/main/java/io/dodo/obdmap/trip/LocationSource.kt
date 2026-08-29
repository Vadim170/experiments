package io.dodo.obdmap.trip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import io.dodo.obdmap.util.Logger

/**
 * Источник координат для трека. На голом [LocationManager]: приложению не нужны
 * Google Play Services, а частота обновлений тут и так упирается в GPS.
 */
@SuppressLint("MissingPermission")
class LocationSource(private val context: Context) {

    private companion object {
        /** Для трека нужен каждый фикс, поэтому без прореживания. */
        const val MIN_INTERVAL_MS = 1_000L
        const val MIN_DISTANCE_M = 0f

        /** Фикс старше этого в трек не пишем. */
        const val MAX_AGE_MS = 10_000L
    }

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    private var latest: Location? = null

    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val previous = latest
            // Сетевой фикс может прийти позже точного GPS — не откатываемся назад
            if (previous != null && location.time in 1 until previous.time) return
            latest = location
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) {
            Logger.log("гео: $provider включён")
        }

        override fun onProviderDisabled(provider: String) {
            Logger.log("гео: $provider выключен")
        }
    }

    fun start() {
        if (started) return
        if (!hasPermission()) {
            Logger.log("гео: нет разрешения, трек писаться не будет")
            return
        }
        val locationManager = manager ?: return

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) Logger.log("гео: провайдеры выключены")

        providers.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    MIN_INTERVAL_MS,
                    MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
            }.onFailure { Logger.error("гео: не подписался на $provider", it) }
        }
        started = true
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { manager?.removeUpdates(listener) }
        latest = null
    }

    /** Достаточно свежая позиция или null. */
    fun current(): Location? {
        val fix = latest ?: return null
        val age = System.currentTimeMillis() - fix.time
        return if (fix.time > 0 && age > MAX_AGE_MS) null else fix
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
