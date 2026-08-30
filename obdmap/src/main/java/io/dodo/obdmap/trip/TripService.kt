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
import io.dodo.obdmap.data.HistoryStore
import io.dodo.obdmap.data.PointEntity
import io.dodo.obdmap.data.TripDatabase
import io.dodo.obdmap.data.TripEntity
import io.dodo.obdmap.obd.ElmBleClient
import io.dodo.obdmap.obd.ElmSession
import io.dodo.obdmap.obd.FuelMath
import io.dodo.obdmap.obd.ObdSnapshot
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
 *
 * Два режима:
 * * **ручной** — поездка начинается по кнопке и заканчивается по кнопке;
 * * **автоматический** — сервис ждёт появления адаптера (стек BLE делает это
 *   сам через autoConnect), открывает поездку, когда двигатель заработал, и
 *   закрывает её, когда данные с шины пропали надолго. Дальше снова ждёт.
 */
class TripService : Service() {

    companion object {
        const val ACTION_START = "io.dodo.obdmap.START"
        const val ACTION_STOP = "io.dodo.obdmap.STOP"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_AUTO = "auto"

        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 7

        /** Пауза между циклами опроса во время поездки. */
        private const val POLL_INTERVAL_MS = 250L

        /** Пауза между циклами, пока поездки нет: реже, чтобы не жечь батарею. */
        private const val IDLE_POLL_INTERVAL_MS = 2_000L

        /** Раз в столько циклов дочитываем медленные показатели (бак, температура). */
        private const val SLOW_EVERY = 20

        /** Точки копим и пишем пачкой: запись каждой по отдельности зря будит диск. */
        private const val POINT_BATCH = 10

        /** Двигатель считаем работающим выше этих оборотов. */
        private const val ENGINE_RUNNING_RPM = 300.0

        /** Нет данных с шины столько — поездка закончилась (зажигание выключили). */
        private const val IDLE_STOP_MS = 3 * 60 * 1000L

        /** Пауза перед первой повторной попыткой связи в автоматическом режиме. */
        private const val RECONNECT_DELAY_MS = 5_000L

        /**
         * Потолок паузы. Если шина молчит (зажигание выключено), повторять
         * инициализацию каждые пять секунд бессмысленно: каждая попытка — это
         * ATZ и перебор протоколов, то есть десятки секунд работы адаптера.
         */
        private const val MAX_RECONNECT_DELAY_MS = 5 * 60 * 1000L

        /**
         * Если связь вернулась в этот срок, продолжаем ту же поездку.
         * Иначе светофор с потерей связи резал бы поездку на куски.
         */
        private const val RESUME_WINDOW_MS = 3 * 60 * 1000L

        fun start(context: Context, address: String, auto: Boolean) {
            val intent = Intent(context, TripService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_AUTO, auto)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Logger.error("не удалось запустить запись", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TripService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Logger.error("не удалось остановить запись", it) }
        }
    }

    /** Поездка, которая пишется прямо сейчас. */
    private class ActiveTrip(val id: Long) {
        val accumulator = TripAccumulator()
        val acceleration = AccelerationTracker()
        val pending = mutableListOf<PointEntity>()
        var lastDataAt: Long = System.currentTimeMillis()

        /**
         * Последние известные показания. Отдельный PID может не ответить в
         * конкретном цикле, и раньше точка уезжала в базу с null — из-за этого
         * трек красился сплошным серым, хотя скорость менялась.
         */
        var speedKmh: Double? = null
        var rpm: Double? = null
        var fuelRateLitersPerHour: Double? = null
        var litersPer100Km: Double? = null
        var accelerationMs2: Double? = null
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
            stopEverything("Запись остановлена")
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
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false)
            ?: Prefs.autoMode(applicationContext)

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
                buildNotification(if (auto) "Жду адаптер…" else "Подключаюсь к адаптеру…"),
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
        locationSource.start()
        // История не должна расти бесконечно: старое уезжает в архив
        scope.launch { runCatching { HistoryStore.enforceLimit(applicationContext) } }
        worker = scope.launch { supervise(address, auto) }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Главный цикл -------------------------------------------------------

    /**
     * Держит связь и ведёт поездки. В ручном режиме выходит после первой же
     * потери связи, в автоматическом — ждёт адаптер снова.
     */
    private suspend fun supervise(address: String, auto: Boolean) {
        var trip: ActiveTrip? = null
        var retryDelay = RECONNECT_DELAY_MS
        try {
            while (currentCoroutineContext().isActive) {
                val session = ElmSession(client)

                TripSession.setStatus(
                    if (auto) ConnectionState.WAITING else ConnectionState.CONNECTING,
                    if (auto) "Жду адаптер — поездка начнётся сама" else "Подключаюсь к адаптеру…",
                )
                updateNotification(if (auto) "Жду адаптер…" else "Подключаюсь…")

                if (!connectAndInit(address, session, autoConnect = auto)) {
                    if (!auto) return
                    // Поездку, которую уже не продолжить, закрываем
                    trip = finalizeIfStale(trip)
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                    continue
                }
                retryDelay = RECONNECT_DELAY_MS

                trip = driveLoop(session, trip, auto)
                runCatching { client.close() }
                if (!auto) return
            }
        } finally {
            // Остановка отменяет корутину, и обычный код до итогов уже не доходит
            withContext(NonCancellable) { closeTrip(trip) }
        }
    }

    /**
     * Опрос шины, пока держится связь.
     *
     * @return поездку, которую можно продолжить при возврате связи, либо null
     */
    private suspend fun driveLoop(
        session: ElmSession,
        resumed: ActiveTrip?,
        auto: Boolean,
    ): ActiveTrip? {
        var trip = resumed
        var cycle = 0

        while (currentCoroutineContext().isActive && client.connected.value) {
            val now = System.currentTimeMillis()
            val snapshot = session.readSnapshot(now, includeSlow = cycle % SLOW_EVERY == 0)
            val hasData = snapshot.speedKmh != null || snapshot.rpm != null

            if (trip == null) {
                // В автоматическом режиме ждём признаков заведённого мотора,
                // в ручном пишем всё подряд с момента нажатия кнопки.
                if (!auto || engineRunning(snapshot)) {
                    trip = startTrip(session)
                }
            } else if (hasData) {
                trip.lastDataAt = now
            } else if (now - trip.lastDataAt > IDLE_STOP_MS) {
                Logger.log("данных нет ${IDLE_STOP_MS / 60_000} мин — закрываю поездку")
                closeTrip(trip)
                trip = null
                TripSession.setStatus(ConnectionState.WAITING, "Поездка завершена, жду следующую")
            }

            if (trip != null) {
                recordSample(session, trip, snapshot, now)
                if (cycle % 8 == 0) {
                    updateNotification(
                        notificationText(trip.accumulator.stats, snapshot.speedKmh),
                    )
                }
            } else {
                TripSession.update {
                    it.copy(
                        connection = ConnectionState.WAITING,
                        status = "Адаптер на связи, жду запуска двигателя",
                        diagnostics = session.diagnostics,
                        protocol = session.protocol,
                    )
                }
                updateNotification("Жду запуска двигателя")
            }

            cycle++
            delay(if (trip != null) POLL_INTERVAL_MS else IDLE_POLL_INTERVAL_MS)
        }

        // Связь пропала: сбрасываем окно ускорения, иначе после паузы получим
        // мнимый рывок из скорости «до» и «после».
        trip?.acceleration?.reset()
        return trip
    }

    /**
     * Признаки заведённого мотора. Обороты — самый надёжный, но PID 0C
     * поддерживают не все блоки, поэтому годятся и движение, и ненулевой расход.
     */
    private fun engineRunning(snapshot: ObdSnapshot): Boolean {
        val rpm = snapshot.rpm
        if (rpm != null && rpm >= ENGINE_RUNNING_RPM) return true
        val speed = snapshot.speedKmh
        if (speed != null && speed > 0) return true
        val fuelRate = snapshot.fuelRateLitersPerHour
        return fuelRate != null && fuelRate > 0
    }

    private suspend fun startTrip(session: ElmSession): ActiveTrip {
        val id = dao.insertTrip(
            TripEntity(startedAt = System.currentTimeMillis(), fuelSource = session.fuelSource.title),
        )
        TripSession.startNewTrip(id)
        TripSession.update { it.copy(fuelSource = session.fuelSource) }
        Logger.log("поездка $id начата, расход по: ${session.fuelSource.title}")
        return ActiveTrip(id)
    }

    private suspend fun recordSample(
        session: ElmSession,
        trip: ActiveTrip,
        snapshot: ObdSnapshot,
        now: Long,
    ) {
        // Показания запоминаем: молчание одного PID в конкретном цикле не должно
        // стирать уже известное значение.
        snapshot.speedKmh?.let { trip.speedKmh = it }
        snapshot.rpm?.let { trip.rpm = it }
        snapshot.fuelRateLitersPerHour?.let { trip.fuelRateLitersPerHour = it }
        trip.acceleration.add(now, snapshot.speedKmh)?.let { trip.accelerationMs2 = it }
        trip.litersPer100Km = trip.fuelRateLitersPerHour?.let { rate ->
            FuelMath.litersPer100Km(rate, trip.speedKmh ?: 0.0)
        }

        // Забираем все накопленные фиксы: при заминке BLE их набирается
        // несколько, и записать надо каждый, иначе маршрут станет прямой.
        val fixes = locationSource.drainFixes()

        TripSession.update { state ->
            state.copy(
                connection = ConnectionState.LIVE,
                status = "Идёт запись",
                speedKmh = trip.speedKmh,
                rpm = trip.rpm,
                fuelRateLitersPerHour = trip.fuelRateLitersPerHour,
                litersPer100Km = trip.litersPer100Km,
                fuelLevelPercent = snapshot.fuelLevelPercent ?: state.fuelLevelPercent,
                coolantTempC = snapshot.coolantTempC ?: state.coolantTempC,
                accelerationMs2 = trip.accelerationMs2,
                fuelSource = session.fuelSource,
                diagnostics = session.diagnostics,
                protocol = session.protocol,
                stats = trip.accumulator.stats,
                tripId = trip.id,
                hasLocation = fixes.isNotEmpty() || locationSource.current() != null,
            )
        }

        // График текущей поездки рисуем и без GPS: данные с шины идут
        TripSession.addSample(
            LiveSample(
                timeMs = now,
                speedKmh = trip.speedKmh,
                litersPer100Km = trip.litersPer100Km,
                accelerationMs2 = trip.accelerationMs2,
            ),
        )

        if (fixes.isEmpty()) {
            // Координат не подвезли — точка только с данными шины
            emitPoint(trip, now, latitude = null, longitude = null)
        } else {
            fixes.forEach { fix ->
                emitPoint(trip, fix.time.takeIf { it > 0 } ?: now, fix.latitude, fix.longitude)
            }
        }

        if (trip.pending.size >= POINT_BATCH) {
            dao.insertPoints(trip.pending.toList())
            trip.pending.clear()
        }
    }

    /** Одна точка трека: в статистику, в живую карту и в очередь на запись. */
    private fun emitPoint(
        trip: ActiveTrip,
        timeMs: Long,
        latitude: Double?,
        longitude: Double?,
    ) {
        trip.accumulator.add(
            TripSample(
                timeMs = timeMs,
                latitude = latitude,
                longitude = longitude,
                speedKmh = trip.speedKmh,
                fuelRateLitersPerHour = trip.fuelRateLitersPerHour,
            ),
        )

        if (latitude != null && longitude != null) {
            TripSession.addTrackPoint(
                TrackPoint(
                    timeMs = timeMs,
                    latitude = latitude,
                    longitude = longitude,
                    speedKmh = trip.speedKmh,
                    litersPer100Km = trip.litersPer100Km,
                    accelerationMs2 = trip.accelerationMs2,
                ),
            )
        }

        trip.pending += PointEntity(
            tripId = trip.id,
            timeMs = timeMs,
            latitude = latitude,
            longitude = longitude,
            speedKmh = trip.speedKmh,
            rpm = trip.rpm,
            fuelRateLitersPerHour = trip.fuelRateLitersPerHour,
            litersPer100Km = trip.litersPer100Km,
            accelerationMs2 = trip.accelerationMs2,
        )
    }

    private suspend fun connectAndInit(
        address: String,
        session: ElmSession,
        autoConnect: Boolean,
    ): Boolean {
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

        client.connect(device, autoConnect = autoConnect)?.let { error ->
            TripSession.setStatus(
                if (autoConnect) ConnectionState.WAITING else ConnectionState.ERROR,
                "Адаптер: $error",
            )
            Logger.error("подключение к адаптеру: $error")
            return false
        }

        TripSession.setStatus(ConnectionState.INITIALIZING, "Настраиваю ELM327…")
        session.initialize()?.let { error ->
            TripSession.setStatus(
                if (autoConnect) ConnectionState.WAITING else ConnectionState.ERROR,
                "ELM327: $error",
            )
            Logger.error("инициализация: $error")
            client.close()
            return false
        }
        return true
    }

    /** Закрывает поездку, если связь не вернулась в отведённое окно. */
    private suspend fun finalizeIfStale(trip: ActiveTrip?): ActiveTrip? {
        if (trip == null) return null
        if (System.currentTimeMillis() - trip.lastDataAt <= RESUME_WINDOW_MS) return trip
        closeTrip(trip)
        return null
    }

    /** Дописывает хвост точек и проставляет итоги. */
    private suspend fun closeTrip(trip: ActiveTrip?) {
        if (trip == null) return
        if (trip.pending.isNotEmpty()) {
            runCatching { dao.insertPoints(trip.pending.toList()) }
            trip.pending.clear()
        }
        val stats = trip.accumulator.stats
        val stored = dao.trip(trip.id) ?: return
        runCatching {
            dao.updateTrip(
                stored.copy(
                    finishedAt = System.currentTimeMillis(),
                    distanceMeters = stats.distanceMeters,
                    fuelLiters = stats.fuelLiters,
                    maxSpeedKmh = stats.maxSpeedKmh,
                    movingMillis = stats.movingMillis,
                    idleMillis = stats.idleMillis,
                ),
            )
        }
        Logger.log(
            "поездка ${trip.id} завершена: %.1f км, %.2f л".format(
                Locale.US,
                stats.distanceMeters / 1000,
                stats.fuelLiters,
            ),
        )
        runCatching { dao.deleteEmptyTrips() }
        runCatching { HistoryStore.enforceLimit(applicationContext) }
    }

    private fun stopEverything(status: String?) {
        worker?.cancel()
        worker = null
        locationSource.stop()
        runCatching { client.close() }
        releaseWakeLock()
        if (status != null) TripSession.setStatus(ConnectionState.IDLE, status)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
            .setContentTitle("OBD Trip Map")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_route)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
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
