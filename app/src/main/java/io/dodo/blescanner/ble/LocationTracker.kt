package io.dodo.blescanner.ble

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
import io.dodo.blescanner.model.LocationFix

/**
 * Держит свежую позицию, чтобы каждому обнаружению можно было проставить координаты.
 *
 * Намеренно на голом [LocationManager], без Google Play Services: приложение
 * должно работать и там, где сервисов Google нет.
 */
class LocationTracker(private val context: Context) {

    private companion object {
        /** Как часто просим обновления. Чаще смысла нет — точки и так режутся политикой. */
        const val MIN_INTERVAL_MS = 30_000L
        const val MIN_DISTANCE_M = 10f

        /** Фикс старше этого считаем непригодным — лучше «нет координат», чем вчерашние. */
        const val MAX_AGE_MS = 15 * 60 * 1000L
    }

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    private var latest: LocationFix? = null

    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(location)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) {
            BleLogger.log("гео: провайдер $provider включён")
        }

        override fun onProviderDisabled(provider: String) {
            BleLogger.log("гео: провайдер $provider выключен")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        if (!hasPermission()) {
            BleLogger.log("гео: нет разрешения на локацию, обнаружения будут без координат")
            return
        }
        val locationManager = manager
        if (locationManager == null) {
            BleLogger.logError("гео: LocationManager недоступен")
            return
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            BleLogger.log("гео: ни один провайдер не включён — координат не будет")
        }

        providers.forEach { provider ->
            // Последняя известная точка как затравка: первый фикс GPS может идти минуту.
            runCatching { locationManager.getLastKnownLocation(provider) }
                .getOrNull()
                ?.let { onFix(it, quiet = true) }

            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    MIN_INTERVAL_MS,
                    MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
            }.onFailure { BleLogger.logError("гео: не подписался на $provider", it) }
        }

        started = true
        BleLogger.log("гео: слежу за позицией (${providers.joinToString().ifEmpty { "нет провайдеров" }})")
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { manager?.removeUpdates(listener) }
        BleLogger.log("гео: слежение остановлено")
    }

    /** Текущая позиция, если она достаточно свежая. */
    fun current(): LocationFix? {
        val fix = latest ?: return null
        return if (System.currentTimeMillis() - fix.timeMs > MAX_AGE_MS) null else fix
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun onFix(location: Location, quiet: Boolean = false) {
        val fix = LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            provider = location.provider ?: "unknown",
            timeMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )

        val previous = latest
        // Свежий фикс не всегда лучший: сетевой может прийти после точного GPS.
        if (previous != null && fix.timeMs < previous.timeMs) return
        latest = fix

        if (!quiet) {
            BleLogger.log("гео: ${fix.formatWithAccuracy()} (${fix.provider})")
        }
    }
}
