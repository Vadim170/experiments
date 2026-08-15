package io.dodo.blescanner.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.dodo.blescanner.model.CharacteristicValue

/**
 * Одна сессия работы с устройством: подключиться → discoverServices →
 * последовательно прочитать все характеристики с флагом READ → отключиться.
 *
 * Читаем строго по одной характеристике за раз: стек Android не умеет
 * параллельные GATT-операции и молча теряет запросы.
 *
 * Все колбэки прилетают с binder-потока, поэтому вся внутренняя работа
 * переносится на [handler] (main looper) — так состояние не нужно синхронизировать.
 */
@SuppressLint("MissingPermission")
class GattReader(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onFinished: (
        address: String,
        gattName: String?,
        values: List<CharacteristicValue>,
        error: String?,
    ) -> Unit,
) : BluetoothGattCallback() {

    private companion object {
        /** Общий таймаут на всю сессию — иначе висящее устройство блокирует очередь. */
        const val SESSION_TIMEOUT_MS = 25_000L

        /** Пауза перед discoverServices: сразу после connect стек бывает не готов. */
        const val DISCOVERY_DELAY_MS = 400L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<BluetoothGattCharacteristic>()
    private val values = mutableListOf<CharacteristicValue>()

    private var gatt: BluetoothGatt? = null
    private var gattName: String? = null
    private var finished = false

    private val timeoutTask = Runnable { finish("таймаут ${SESSION_TIMEOUT_MS / 1000} с") }

    fun start() {
        handler.post {
            BleLogger.log("→ подключаюсь к ${device.address}")
            handler.postDelayed(timeoutTask, SESSION_TIMEOUT_MS)
            val connected = runCatching {
                device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            }.getOrElse {
                BleLogger.logError("connectGatt упал для ${device.address}", it)
                null
            }
            gatt = connected
            if (connected == null) finish("не удалось открыть GATT")
        }
    }

    /** Досрочно оборвать сессию (например, при остановке сервиса). */
    fun cancel() {
        handler.post { finish("отменено") }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        handler.post {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    gattName = runCatching { gatt.device?.name }.getOrNull()
                    BleLogger.log("✓ подключено ${device.address}, ищу службы")
                    handler.postDelayed({
                        if (!finished && !gatt.discoverServices()) {
                            finish("discoverServices вернул false")
                        }
                    }, DISCOVERY_DELAY_MS)
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    // Разрыв до окончания чтения — это ошибка; после finish() он ожидаем.
                    finish(if (status == BluetoothGatt.GATT_SUCCESS) "устройство разорвало связь" else "ошибка связи, status=$status")
                }
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        handler.post {
            if (finished) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("discoverServices status=$status")
                return@post
            }
            gatt.services.forEach { service ->
                service.characteristics.forEach { characteristic ->
                    if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        queue.addLast(characteristic)
                    }
                }
            }
            BleLogger.log("  ${device.address}: служб ${gatt.services.size}, читаемых характеристик ${queue.size}")
            readNext()
        }
    }

    // API 33+
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        handler.post { handleRead(characteristic, value, status) }
    }

    // до API 33; на 33+ система вызывает версию выше, поэтому здесь не дублируем
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val value = characteristic.value ?: ByteArray(0)
        handler.post { handleRead(characteristic, value, status) }
    }

    private fun handleRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (finished) return
        val serviceUuid = characteristic.service.uuid
        val charUuid = characteristic.uuid
        val ok = status == BluetoothGatt.GATT_SUCCESS

        val entry = CharacteristicValue(
            serviceUuid = serviceUuid.toString(),
            serviceName = Uuids.serviceName(serviceUuid),
            charUuid = charUuid.toString(),
            charName = Uuids.characteristicName(charUuid),
            hex = if (ok) Uuids.toHex(value) else "чтение не удалось (status=$status)",
            decoded = if (ok) Uuids.decode(charUuid, value) else null,
            ok = ok,
        )
        values += entry

        if (ok) {
            val shown = entry.decoded?.let { "$it  [${entry.hex}]" } ?: entry.hex
            BleLogger.log("  ${device.address} ${entry.serviceName} / ${entry.charName} = $shown")
        } else {
            BleLogger.log("  ${device.address} ${entry.serviceName} / ${entry.charName}: status=$status")
        }
        readNext()
    }

    private fun readNext() {
        if (finished) return
        val current = gatt
        if (current == null) {
            finish("GATT закрыт")
            return
        }
        val next = queue.removeFirstOrNull()
        if (next == null) {
            finish(null)
            return
        }
        if (!current.readCharacteristic(next)) {
            // Запрос даже не встал в очередь — фиксируем и идём дальше.
            values += CharacteristicValue(
                serviceUuid = next.service.uuid.toString(),
                serviceName = Uuids.serviceName(next.service.uuid),
                charUuid = next.uuid.toString(),
                charName = Uuids.characteristicName(next.uuid),
                hex = "readCharacteristic отклонён",
                decoded = null,
                ok = false,
            )
            readNext()
        }
    }

    private fun finish(error: String?) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeoutTask)

        val current = gatt
        gatt = null
        runCatching { current?.disconnect() }
        // close() сразу после disconnect() иногда «съедает» разрыв — даём стеку выдохнуть
        handler.postDelayed({ runCatching { current?.close() } }, 300)

        val successful = values.count { it.ok }
        if (error == null) {
            BleLogger.log("✓ ${device.address}: прочитано $successful из ${values.size}")
        } else {
            BleLogger.log("✗ ${device.address}: $error (успело прочитаться $successful)")
        }
        onFinished(device.address, gattName, values.toList(), error)
    }
}
