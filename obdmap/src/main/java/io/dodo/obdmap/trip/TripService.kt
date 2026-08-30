package io.dodo.obdmap.trip

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.dodo.obdmap.R
import io.dodo.obdmap.data.PointEntity
import io.dodo.obdmap.data.TripDatabase
import io.dodo.obdmap.data.TripEntity
import io.dodo.obdmap.obd.ElmBleClient
import io.dodo.obdmap.obd.ElmSession
import io.dodo.obdmap.obd.FuelMath
import io.dodo.obdmap.ui.MainActivity
import io.dodo.obdmap.util.Logger
import io.dodo.obdmap.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Запись поездки: держит связь с ELM327, опрашивает шину, складывает трек
 * в базу и отдаёт живые показания интерфейсу.
 */
class TripService : Service() {

    companion object {
        const val ACTION_START = "io.dodo.obdmap.START"
        const val ACTION_STOP = "io.dodo.obdmap.STOP"
        const val EXTRA_ADDRESS = "address"

        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 7

        /** Пауза между циклами опроса. Реальный темп упирается в скорость BLE. */
        private const val POLL_INTERVAL_MS = 250L

        /** Раз в столько циклов дочитываем медленные показатели (бак, температура). */
        private const val SLOW_EVERY = 20

        /** Точки копим и пишем пачкой: запись каждой по отдельности зря будит диск. */
        private const val POINT_BATCH = 10

        /** Сколько раз пробуем восстановить связь, прежде чем закончить поездку. */
        private const val MAX_RECONNECTS = 3

        fun start(context: Context, address: String) {
            val intent = Intent(context, TripService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ADDRESS, address)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Logger.error("не удалось запустить запись", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TripService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Logger.error("не удалось остановить запись", it) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    private lateinit var client: ElmBleClient
    private lateinit var locationSource: LocationSource
    private var wakeLock: PowerManager.WakeLock? = null

    private val dao by lazy { TripDatabase.get(applicationContext).tripDao() }

    override fun onCreate() {
        super.onCreate()
        Logger.init(applicationContext)
        client = ElmBleClient(applicationContext)
        locationSource = LocationSource(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording("Запись остановлена")
            stopSelf()
            return START_NOT_STICKY
        }

        val address = intent?.getStringExtra(EXTRA_ADDRESS)
            ?: Prefs.adapterAddress(applicationContext)
        if (address == null) {
            Logger.error("не задан адрес адаптера")
            stopSelf()
            return START_NOT_STICKY
        }

        val types = foregroundServiceTypes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && types == 0) {
            // На Android 14+ startForeground с неподкреплённым типом кидает SecurityException
            TripSession.setStatus(ConnectionState.ERROR, "Нет разрешений на Bluetooth")
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Подключаюсь к адаптеру…"),
                types,
            )
        }
        if (started.isFailure) {
            Logger.error("startForeground не прошёл", started.exceptionOrNull())
            TripSession.setStatus(ConnectionState.ERROR, "Не удалось запустить запись")
            stopSelf()
            return START_NOT_STICKY
        }

        if (worker?.isActive == true) return START_STICKY
        acquireWakeLock()
        worker = scope.launch { record(address) }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Запись -------------------------------------------------------------

    private suspend fun record(address: String) {
        val session = ElmSession(client)

        if (!connectAndInit(address, session)) {
            stopSelfSafely()
            return
        }

        val tripId = dao.insertTrip(
            TripEntity(startedAt = System.currentTimeMillis(), fuelSource = session.fuelSource.title),
        )
        TripSession.startNewTrip(tripId)
        TripSession.update { it.copy(fuelSource = session.fuelSource) }
        locationSource.start()
        Logger.log("поездка $tripId начата, расход по: ${session.fuelSource.title}")

        val accumulator = TripAccumulator()
        val acceleration = AccelerationTracker()
        val pending = mutableListOf<PointEntity>()
        var cycle = 0
        var reconnects = 0

        try {
            while (currentCoroutineContext().isActive) {
                if (!client.connected.value) {
                    if (reconnects >= MAX_RECONNECTS) {
                        TripSession.setStatus(ConnectionState.ERROR, "Связь с адаптером потеряна")
                        break
                    }
                    reconnects++
                    TripSession.setStatus(
                        ConnectionState.CONNECTING,
                        "Переподключение $reconnects из $MAX_RECONNECTS…",
                    )
                    delay(2_000)
                    if (connectAndInit(address, session)) {
                        TripSession.setStatus(ConnectionState.LIVE, "Идёт запись")
                    } else {
                        continue
                    }
                }

                val now = System.currentTimeMillis()
                val snapshot = session.readSnapshot(now, includeSlow = cycle % SLOW_EVERY == 0)
                val location = locationSource.current()
                val accelerationMs2 = acceleration.add(now, snapshot.speedKmh)

                val sample = TripSample(
                    timeMs = now,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    speedKmh = snapshot.speedKmh,
                    fuelRateLitersPerHour = snapshot.fuelRateLitersPerHour,
                )
                accumulator.add(sample)

                val instant = snapshot.fuelRateLitersPerHour?.let { rate ->
                    FuelMath.litersPer100Km(rate, snapshot.speedKmh ?: 0.0)
                }

                TripSession.update { state ->
                    state.copy(
                        connection = ConnectionState.LIVE,
                        status = "Идёт запись",
                        speedKmh = snapshot.speedKmh ?: state.speedKmh,
                        rpm = snapshot.rpm ?: state.rpm,
                        fuelRateLitersPerHour = snapshot.fuelRateLitersPerHour,
                        litersPer100Km = instant,
                        fuelLevelPercent = snapshot.fuelLevelPercent ?: state.fuelLevelPercent,
                        coolantTempC = snapshot.coolantTempC ?: state.coolantTempC,
                        accelerationMs2 = accelerationMs2 ?: state.accelerationMs2,
                        fuelSource = session.fuelSource,
                        diagnostics = session.diagnostics,
                        stats = accumulator.stats,
                        hasLocation = location != null,
                    )
                }

                if (location != null) {
                    TripSession.addTrackPoint(
                        TrackPoint(
                            timeMs = now,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            speedKmh = snapshot.speedKmh,
                            litersPer100Km = instant,
                            accelerationMs2 = accelerationMs2,
                        ),
                    )
                }

                // График текущей поездки рисуем и без GPS: данные с шины идут
                TripSession.addSample(
                    LiveSample(
                        timeMs = now,
                        speedKmh = snapshot.speedKmh,
                        litersPer100Km = instant,
                        accelerationMs2 = accelerationMs2,
                    ),
                )

                pending += PointEntity(
                    tripId = tripId,
                    timeMs = now,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    speedKmh = snapshot.speedKmh,
                    rpm = snapshot.rpm,
                    fuelRateLitersPerHour = snapshot.fuelRateLitersPerHour,
                    litersPer100Km = instant,
                    accelerationMs2 = accelerationMs2,
                )
                if (pending.size >= POINT_BATCH) {
                    dao.insertPoints(pending.toList())
                    pending.clear()
                }

                if (cycle % 8 == 0) {
                    updateNotification(notificationText(accumulator.stats, snapshot.speedKmh))
                }
                cycle++
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            // Остановка отменяет корутину, и обычный код после цикла до итогов
            // уже не доходит: дописываем хвост и закрываем поездку вне отмены.
            withContext(NonCancellable) {
                if (pending.isNotEmpty()) runCatching { dao.insertPoints(pending.toList()) }
                runCatching { finishTrip(tripId, accumulator.stats) }
            }
        }
        stopSelfSafely()
    }

    private suspend fun connectAndInit(address: String, session: ElmSession): Boolean {
        TripSession.setStatus(ConnectionState.CONNECTING, "Подключаюсь к адаптеру…")

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = manager?.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            TripSession.setStatus(ConnectionState.ERROR, "Bluetooth выключен")
            return false
        }
        val device = runCatching { bluetoothAdapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            TripSession.setStatus(ConnectionState.ERROR, "Неверный адрес адаптера")
            return false
        }

        client.connect(device)?.let { error ->
            TripSession.setStatus(ConnectionState.ERROR, "Адаптер: $error")
            Logger.error("подключение к адаптеру: $error")
            return false
        }

        TripSession.setStatus(ConnectionState.INITIALIZING, "Настраиваю ELM327…")
        session.initialize()?.let { error ->
            TripSession.setStatus(ConnectionState.ERROR, "ELM327: $error")
            Logger.error("инициализация: $error")
            client.close()
            return false
        }
        return true
    }

    private suspend fun finishTrip(tripId: Long, stats: TripStats) {
        val trip = dao.trip(tripId) ?: return
        dao.updateTrip(
            trip.copy(
                finishedAt = System.currentTimeMillis(),
                distanceMeters = stats.distanceMeters,
                fuelLiters = stats.fuelLiters,
                maxSpeedKmh = stats.maxSpeedKmh,
                movingMillis = stats.movingMillis,
                idleMillis = stats.idleMillis,
            ),
        )
        Logger.log(
            "поездка $tripId завершена: %.1f км, %.2f л".format(
                Locale.US,
                stats.distanceMeters / 1000,
                stats.fuelLiters,
            ),
        )
        withContext(Dispatchers.IO) { runCatching { dao.deleteEmptyTrips() } }
    }

    private fun stopRecording(status: String?) {
        worker?.cancel()
        worker = null
        locationSource.stop()
        runCatching { client.close() }
        releaseWakeLock()
        if (status != null) TripSession.setStatus(ConnectionState.IDLE, status)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun stopSelfSafely() {
        locationSource.stop()
        runCatching { client.close() }
        releaseWakeLock()
        stopSelf()
    }

    // --- Обвязка ------------------------------------------------------------

    private fun foregroundServiceTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = 0
        if (hasBluetoothPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (locationSource.hasPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun hasBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Запись поездки",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Показывает, что поездка пишется"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TripService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Запись поездки")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_route)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Завершить", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun notificationText(stats: TripStats, speedKmh: Double?): String {
        val average = stats.averageLitersPer100Km
        return buildString {
            append(String.format(Locale.US, "%.1f км", stats.distanceMeters / 1000))
            if (speedKmh != null) append(String.format(Locale.US, " · %.0f км/ч", speedKmh))
            if (average != null) append(String.format(Locale.US, " · %.1f л/100", average))
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ObdTripMap::record").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Logger.error("не удалось взять wake lock", it) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}
