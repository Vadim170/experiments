package io.dodo.obdmap.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import io.dodo.obdmap.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Транспорт до BLE-адаптера ELM327.
 *
 * Клоны используют разные наборы UUID, единого стандарта нет. Поэтому сначала
 * пробуем известные профили, а если ни один не подошёл — ищем в любой службе
 * пару «характеристика с NOTIFY + характеристика с WRITE».
 *
 * Команды строго последовательны: ELM327 однопоточный, он отвечает на команду
 * приглашением '>' и до этого момента новую не примет.
 */
@SuppressLint("MissingPermission")
class ElmBleClient(private val context: Context) : ElmIo {

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Известные профили клонов: служба, характеристика уведомлений, характеристика записи. */
        val KNOWN_PROFILES = listOf(
            // Самый частый вариант (HM-10 и совместимые): одна характеристика на всё
            Profile("0000ffe0", "0000ffe1", "0000ffe1"),
            // Vgate iCar, часть Konnwei
            Profile("0000fff0", "0000fff1", "0000fff2"),
            // Nordic UART Service
            Profile("6e400001-b5a3-f393-e0a9-e50e24dcca9e", "6e400003", "6e400002"),
            // Часть VLink / OBDLink
            Profile("000018f0", "00002af0", "00002af1"),
        )

        const val CONNECT_TIMEOUT_MS = 15_000L
        const val STEP_TIMEOUT_MS = 8_000L
        const val WRITE_TIMEOUT_MS = 5_000L

        /** ELM327 по BLE редко переваривает больше 20 байт за запись. */
        const val DEFAULT_PAYLOAD = 20
    }

    private data class Profile(val service: String, val notify: String, val write: String)

    private val commandMutex = Mutex()
    private val buffer = StringBuilder()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var payloadSize = DEFAULT_PAYLOAD

    @Volatile
    private var pending: CompletableDeferred<String>? = null

    @Volatile
    private var connectionStep: CompletableDeferred<Boolean>? = null

    /**
     * Ждём ли первого подключения в режиме autoConnect. Пока ждём, разрывы
     * игнорируем: стек сам повторяет попытки, пока адаптер не появится.
     */
    @Volatile
    private var awaitingAutoConnect = false

    @Volatile
    private var writeStep: CompletableDeferred<Boolean>? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    // --- Подключение --------------------------------------------------------

    /**
     * @param autoConnect ждать появления адаптера сколько потребуется. Так
     *   работает автоматический старт поездки: стек сам подключится, когда
     *   адаптер запитается от разъёма OBD-II. Ожидание прерывается только
     *   отменой корутины.
     * @return null при успехе, иначе текст ошибки.
     */
    suspend fun connect(device: BluetoothDevice, autoConnect: Boolean = false): String? {
        close()

        val connectDeferred = CompletableDeferred<Boolean>()
        connectionStep = connectDeferred
        awaitingAutoConnect = autoConnect

        val newGatt = runCatching {
            device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull() ?: run {
            awaitingAutoConnect = false
            return "не удалось открыть GATT"
        }
        gatt = newGatt

        val connected = if (autoConnect) {
            connectDeferred.await()
        } else {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectDeferred.await() } == true
        }
        awaitingAutoConnect = false
        if (!connected) {
            close()
            return "адаптер не подключился"
        }

        // MTU больше стандартных 23 байт ускоряет обмен, но не обязателен.
        // Поле присваиваем ДО вызова: колбэк прилетает с чужого потока и должен
        // завершить именно этот шаг.
        val mtuStep = CompletableDeferred<Boolean>()
        connectionStep = mtuStep
        if (newGatt.requestMtu(247)) {
            withTimeoutOrNull(STEP_TIMEOUT_MS) { mtuStep.await() }
        }

        val servicesStep = CompletableDeferred<Boolean>()
        connectionStep = servicesStep
        if (!newGatt.discoverServices()) {
            close()
            return "discoverServices вернул false"
        }
        if (withTimeoutOrNull(STEP_TIMEOUT_MS) { servicesStep.await() } != true) {
            close()
            return "не нашёл службы адаптера"
        }

        val pair = pickCharacteristics(newGatt) ?: run {
            close()
            return "в адаптере нет пары характеристик notify + write"
        }
        writeCharacteristic = pair.second

        if (!enableNotifications(newGatt, pair.first)) {
            close()
            return "не удалось включить уведомления"
        }

        _connected.value = true
        Logger.log("ELM327 подключён: ${device.address}, payload $payloadSize Б")
        return null
    }

    /** Ищет характеристики: сначала по известным профилям, потом по свойствам. */
    private fun pickCharacteristics(
        gatt: BluetoothGatt,
    ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        KNOWN_PROFILES.forEach { profile ->
            val service = gatt.services.firstOrNull {
                it.uuid.toString().startsWith(profile.service, ignoreCase = true)
            } ?: return@forEach
            val notify = service.characteristics.firstOrNull {
                it.uuid.toString().startsWith(profile.notify, ignoreCase = true) && it.canNotify()
            }
            val write = service.characteristics.firstOrNull {
                it.uuid.toString().startsWith(profile.write, ignoreCase = true) && it.canWrite()
            }
            if (notify != null && write != null) {
                Logger.log("профиль адаптера: служба ${service.uuid}")
                return notify to write
            }
        }

        // Ни один известный профиль не подошёл — берём первую подходящую пару.
        gatt.services.forEach { service ->
            val notify = service.characteristics.firstOrNull { it.canNotify() }
            val write = service.characteristics.firstOrNull { it.canWrite() }
            if (notify != null && write != null) {
                Logger.log("профиль не опознан, беру службу ${service.uuid} по свойствам")
                return notify to write
            }
        }
        return null
    }

    private suspend fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD) ?: return true

        val step = CompletableDeferred<Boolean>()
        connectionStep = step
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
        if (!issued) return false
        return withTimeoutOrNull(STEP_TIMEOUT_MS) { step.await() } == true
    }

    fun close() {
        _connected.value = false
        awaitingAutoConnect = false
        pending?.completeExceptionally(IOException("соединение закрыто"))
        pending = null
        writeCharacteristic = null
        synchronized(buffer) { buffer.setLength(0) }
        val current = gatt
        gatt = null
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
    }

    // --- Обмен командами ----------------------------------------------------

    override suspend fun send(command: String, timeoutMs: Long): String = commandMutex.withLock {
        val currentGatt = gatt ?: throw IOException("нет подключения к адаптеру")
        val characteristic = writeCharacteristic ?: throw IOException("нет характеристики записи")

        synchronized(buffer) { buffer.setLength(0) }
        val deferred = CompletableDeferred<String>()
        pending = deferred

        // ELM327 ждёт возврат каретки как конец команды
        val payload = (command + "\r").toByteArray(Charsets.US_ASCII)
        payload.toList().chunked(payloadSize).forEach { chunk ->
            if (!writeChunk(currentGatt, characteristic, chunk.toByteArray())) {
                pending = null
                throw IOException("запись команды $command не прошла")
            }
        }

        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (response == null) {
            pending = null
            // Буфер мог наполниться наполовину — иначе хвост уйдёт следующей команде
            synchronized(buffer) { buffer.setLength(0) }
            throw IOException("таймаут ответа на $command")
        }
        // Хвост предыдущей команды мог приехать вместе с нашим ответом
        return ObdParser.lastResponse(response)
    }

    override suspend fun flush() {
        synchronized(buffer) { buffer.setLength(0) }
        pending?.completeExceptionally(IOException("буфер сброшен"))
        pending = null
    }

    private suspend fun writeChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Boolean {
        val step = CompletableDeferred<Boolean>()
        writeStep = step

        val writeType = if (characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, chunk, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = chunk
                gatt.writeCharacteristic(characteristic)
            }
        }
        if (!issued) return false
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) { step.await() } == true
    }

    /** Копит ответ, пока не придёт приглашение '>' — только тогда команда завершена. */
    private fun onIncoming(bytes: ByteArray) {
        val text = String(bytes, Charsets.US_ASCII)
        val complete: String?
        synchronized(buffer) {
            buffer.append(text)
            complete = if (buffer.indexOf(">") >= 0) buffer.toString() else null
            if (complete != null) buffer.setLength(0)
        }
        if (complete != null) {
            pending?.complete(complete)
            pending = null
        }
    }

    private fun BluetoothGattCharacteristic.canNotify(): Boolean =
        properties and (
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            ) != 0

    private fun BluetoothGattCharacteristic.canWrite(): Boolean =
        properties and (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            ) != 0

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionStep?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connected.value = false
                    // В режиме ожидания разрыв — норма: адаптер ещё не запитан,
                    // стек продолжит попытки сам.
                    if (!awaitingAutoConnect) connectionStep?.complete(false)
                    pending?.completeExceptionally(IOException("адаптер отключился, status=$status"))
                    pending = null
                    Logger.log("ELM327 отключился, status=$status")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 3 байта уходят на заголовок ATT
                payloadSize = (mtu - 3).coerceIn(DEFAULT_PAYLOAD, 512)
            }
            connectionStep?.complete(true)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            connectionStep?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            connectionStep?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeStep?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onIncoming(value)
        }

        // до API 33; на 33+ система вызывает версию выше
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            onIncoming(characteristic.value ?: return)
        }
    }
}
