package io.dodo.blescanner.ble

import io.dodo.blescanner.model.BleDevice
import io.dodo.blescanner.model.CharacteristicValue
import io.dodo.blescanner.model.DeviceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Единое хранилище найденных устройств: пишет в него сервис, читает UI.
 * Живёт в процессе, поэтому пережить убийство процесса не может — это
 * осознанное упрощение, «долгая» история лежит в файлах лога.
 */
object BleRepository {

    private val _devices = MutableStateFlow<Map<String, BleDevice>>(emptyMap())
    val devices: StateFlow<Map<String, BleDevice>> = _devices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    fun setScanning(value: Boolean) {
        _scanning.value = value
    }

    /** Устройство замечено сканером. Возвращает true, если увидели его впервые. */
    fun onSeen(address: String, name: String?, rssi: Int): Boolean {
        var isNew = false
        _devices.update { map ->
            val now = System.currentTimeMillis()
            val existing = map[address]
            val updated = if (existing == null) {
                isNew = true
                BleDevice(
                    address = address,
                    name = name,
                    rssi = rssi,
                    firstSeen = now,
                    lastSeen = now,
                    seenCount = 1,
                    state = DeviceState.SEEN,
                )
            } else {
                existing.copy(
                    // имя в рекламном пакете появляется не всегда — не затираем известное
                    name = name?.takeIf { it.isNotBlank() } ?: existing.name,
                    rssi = rssi,
                    lastSeen = now,
                    seenCount = existing.seenCount + 1,
                )
            }
            map + (address to updated)
        }
        return isNew
    }

    fun setState(address: String, state: DeviceState) {
        _devices.update { map ->
            val device = map[address] ?: return@update map
            map + (address to device.copy(state = state))
        }
    }

    fun onReadStarted(address: String) {
        _devices.update { map ->
            val device = map[address] ?: return@update map
            map + (address to device.copy(state = DeviceState.READING, attempts = device.attempts + 1))
        }
    }

    fun onReadFinished(
        address: String,
        gattName: String?,
        values: List<CharacteristicValue>,
        error: String?,
    ) {
        _devices.update { map ->
            val device = map[address] ?: return@update map
            map + (address to device.copy(
                name = device.name ?: gattName?.takeIf { it.isNotBlank() },
                state = if (error == null) DeviceState.DONE else DeviceState.ERROR,
                // при ошибке уже прочитанное сохраняем, если новое пусто
                values = values.ifEmpty { device.values },
                lastError = error,
                lastReadAt = System.currentTimeMillis(),
            ))
        }
    }

    fun clear() {
        _devices.value = emptyMap()
    }
}
