package io.dodo.obdmap.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.dodo.obdmap.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Найденный адаптер в списке выбора. */
data class FoundAdapter(
    val address: String,
    val name: String?,
    val rssi: Int,
    val bonded: Boolean,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "(без имени)"

    /** Похоже ли на ELM327 по имени — такие показываем первыми. */
    val looksLikeObd: Boolean
        get() = name?.uppercase()?.let { upper ->
            LIKELY_NAMES.any { upper.contains(it) }
        } ?: false

    private companion object {
        val LIKELY_NAMES = listOf("OBD", "ELM", "VLINK", "VGATE", "ICAR", "KONNWEI", "VEEPEAK")
    }
}

/**
 * Поиск BLE-адаптера. Показываем и сопряжённые устройства, и результаты скана:
 * часть клонов видна только через скан, часть — уже в списке сопряжённых.
 */
@SuppressLint("MissingPermission")
class AdapterPicker(private val context: Context) {

    private val _adapters = MutableStateFlow<Map<String, FoundAdapter>>(emptyMap())
    val adapters: StateFlow<Map<String, FoundAdapter>> = _adapters

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device?.address ?: return
            val name = result.scanRecord?.deviceName
                ?: if (hasConnectPermission()) {
                    runCatching { result.device.name }.getOrNull()
                } else {
                    null
                }
            put(FoundAdapter(address, name, result.rssi, bonded = false))
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            Logger.error("поиск адаптера не стартовал, код $errorCode")
        }
    }

    /** Сопряжённые устройства попадают в список сразу, без ожидания скана. */
    fun loadBonded() {
        if (!hasConnectPermission()) return
        val bonded = runCatching { bluetoothManager?.adapter?.bondedDevices }.getOrNull().orEmpty()
        bonded.forEach { device ->
            put(
                FoundAdapter(
                    address = device.address,
                    name = runCatching { device.name }.getOrNull(),
                    rssi = 0,
                    bonded = true,
                ),
            )
        }
    }

    fun startScan() {
        if (_scanning.value) return
        if (!hasScanPermission()) {
            Logger.error("нет разрешения на поиск устройств")
            return
        }
        val scanner = bluetoothManager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            Logger.error("Bluetooth выключен")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(emptyList(), settings, callback) }
            .onSuccess { _scanning.value = true }
            .onFailure { Logger.error("не удалось начать поиск", it) }
    }

    fun stopScan() {
        if (!_scanning.value) return
        _scanning.value = false
        if (!hasScanPermission()) return
        runCatching {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        }
    }

    private fun put(adapter: FoundAdapter) {
        val existing = _adapters.value[adapter.address]
        _adapters.value = _adapters.value + (
            adapter.address to adapter.copy(
                // имя и признак сопряжения не теряем между источниками
                name = adapter.name ?: existing?.name,
                bonded = adapter.bonded || existing?.bonded == true,
            )
            )
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
