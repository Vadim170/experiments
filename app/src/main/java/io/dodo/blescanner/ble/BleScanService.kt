package io.dodo.blescanner.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.dodo.blescanner.model.DeviceState
import io.dodo.blescanner.ui.MainActivity

/**
 * Foreground-сервис: непрерывно сканирует BLE, ставит в очередь подключаемые
 * устройства, по очереди подключается к ним и читает все доступные характеристики.
 */
class BleScanService : Service() {

    companion object {
        const val ACTION_START = "io.dodo.blescanner.START"
        const val ACTION_STOP = "io.dodo.blescanner.STOP"

        private const val CHANNEL_ID = "ble_scan"
        private const val NOTIFICATION_ID = 42

        /** Перезапуск сканирования: система перестаёт слать результаты у долгих сканов. */
        private const val SCAN_RESTART_MS = 15 * 60 * 1000L

        /** Как часто перечитывать уже опрошенное устройство. */
        private const val REREAD_INTERVAL_MS = 15 * 60 * 1000L

        /** Пауза между сессиями подключения — стеку нужно время на очистку. */
        private const val BETWEEN_CONNECTIONS_MS = 1_500L

        /** Сколько раз пробуем достучаться до устройства, прежде чем отложить его надолго. */
        private const val MAX_ATTEMPTS = 3

        /** Насколько откладываем устройство после исчерпания попыток. */
        private const val FAILED_BACKOFF_MS = 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, BleScanService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleScanService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    /** Очередь адресов на подключение (без дублей) и текущая сессия. */
    private val queue = ArrayDeque<String>()
    private val queued = mutableSetOf<String>()
    private var activeReader: GattReader? = null

    /** Когда устройство можно опрашивать снова. */
    private val nextReadAt = mutableMapOf<String, Long>()

    private val restartScanTask = object : Runnable {
        override fun run() {
            if (!running) return
            BleLogger.log("перезапускаю скан (плановый)")
            stopScan()
            startScan()
            handler.postDelayed(this, SCAN_RESTART_MS)
        }
    }

    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    BleLogger.log("Bluetooth включён — возобновляю скан")
                    startScan()
                }

                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    BleLogger.log("Bluetooth выключен — скан остановлен")
                    scanning = false
                    BleRepository.setScanning(false)
                    activeReader?.cancel()
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            BleRepository.setScanning(false)
            BleLogger.logError("сканирование не стартовало, код $errorCode")
            // SCAN_FAILED_APPLICATION_REGISTRATION_FAILED и родственные лечатся повтором
            handler.postDelayed({ if (running) startScan() }, 10_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        BleLogger.init(applicationContext)
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = manager?.adapter
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> startEverything()
        }
        // START_STICKY: если система убьёт сервис под нехваткой памяти — поднимет заново
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startEverything() {
        if (running) return
        running = true

        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Запускаюсь…"), type)

        acquireWakeLock()
        BleLogger.log("=== сервис сканирования запущен ===")
        startScan()
        handler.postDelayed(restartScanTask, SCAN_RESTART_MS)
    }

    private fun stopEverything() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        activeReader?.cancel()
        activeReader = null
        queue.clear()
        queued.clear()
        releaseWakeLock()
        BleRepository.setScanning(false)
        BleLogger.log("=== сервис сканирования остановлен ===")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // --- Сканирование -------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!running || scanning) return
        if (!hasScanPermission()) {
            BleLogger.logError("нет разрешения на сканирование — открой приложение и выдай его")
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            BleLogger.logError("Bluetooth выключен, жду включения")
            updateNotification("Bluetooth выключен")
            return
        }
        val leScanner = bluetoothAdapter.bluetoothLeScanner
        if (leScanner == null) {
            BleLogger.logError("BluetoothLeScanner недоступен")
            return
        }

        val settings = ScanSettings.Builder()
            // BALANCED — компромисс между скоростью находок и батареей на долгом скане
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        runCatching {
            // без фильтров: фильтруем сами по признаку «подключаемое»
            leScanner.startScan(emptyList(), settings, scanCallback)
        }.onSuccess {
            scanner = leScanner
            scanning = true
            BleRepository.setScanning(true)
            BleLogger.log("скан запущен")
            updateNotification(notificationText())
        }.onFailure {
            BleLogger.logError("не удалось запустить скан", it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val leScanner = scanner
        if (leScanner != null && scanning && hasScanPermission()) {
            runCatching { leScanner.stopScan(scanCallback) }
        }
        scanning = false
        BleRepository.setScanning(false)
    }

    private fun handleResult(result: ScanResult) {
        // Главный фильтр задачи: интересуют только подключаемые устройства.
        if (!result.isConnectable) return

        val address = result.device.address ?: return
        val name = result.scanRecord?.deviceName
            ?: runCatching { result.device.name }.getOrNull()

        val isNew = BleRepository.onSeen(address, name, result.rssi)
        if (isNew) {
            BleLogger.log("найдено $address ${name ?: "(без имени)"} rssi=${result.rssi}")
        }
        enqueue(address)
    }

    // --- Очередь подключений ------------------------------------------------

    private fun enqueue(address: String) {
        if (address in queued) return
        if (BleRepository.devices.value[address]?.state == DeviceState.READING) return

        val allowedAt = nextReadAt[address]
        if (allowedAt != null && System.currentTimeMillis() < allowedAt) return

        queue.addLast(address)
        queued += address
        BleRepository.setState(address, DeviceState.QUEUED)
        processQueue()
    }

    @SuppressLint("MissingPermission")
    private fun processQueue() {
        if (!running || activeReader != null) return
        val address = queue.removeFirstOrNull() ?: return
        queued -= address

        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled || !hasConnectPermission()) {
            // Вернём в очередь и подождём: без разрешения/адаптера смысла нет
            queue.addLast(address)
            queued += address
            handler.postDelayed({ processQueue() }, 30_000)
            return
        }

        val device = runCatching { bluetoothAdapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            BleLogger.logError("не смог получить устройство $address")
            return
        }

        // Скан на время сессии останавливаем: одновременный скан заметно
        // снижает шанс успешного подключения на многих чипах.
        stopScan()
        BleRepository.onReadStarted(address)
        updateNotification("Читаю $address")

        val reader = GattReader(applicationContext, device) { addr, gattName, values, error ->
            handler.post { onSessionFinished(addr, gattName, values, error) }
        }
        activeReader = reader
        reader.start()
    }

    private fun onSessionFinished(
        address: String,
        gattName: String?,
        values: List<io.dodo.blescanner.model.CharacteristicValue>,
        error: String?,
    ) {
        activeReader = null
        BleRepository.onReadFinished(address, gattName, values, error)

        val attempts = BleRepository.devices.value[address]?.attempts ?: 1
        nextReadAt[address] = System.currentTimeMillis() + when {
            error == null -> REREAD_INTERVAL_MS
            attempts >= MAX_ATTEMPTS -> FAILED_BACKOFF_MS
            // растущая пауза перед повтором: 1, 2, 3 минуты
            else -> attempts * 60_000L
        }

        if (running) {
            handler.postDelayed({
                startScan()
                processQueue()
                updateNotification(notificationText())
            }, BETWEEN_CONNECTIONS_MS)
        }
    }

    // --- Уведомление и прочая обвязка --------------------------------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Сканирование BLE",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Постоянное фоновое сканирование BLE-устройств"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BleScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE Scanner")
            .setContentText(text)
            .setSmallIcon(io.dodo.blescanner.R.drawable.ic_bluetooth)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Стоп", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!running) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun notificationText(): String {
        val devices = BleRepository.devices.value
        val read = devices.values.count { it.state == DeviceState.DONE }
        return "Найдено ${devices.size}, прочитано $read"
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BleScanner::scan").apply {
            setReferenceCounted(false)
            // без таймаута: сервис сам отпустит лок при остановке
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}
