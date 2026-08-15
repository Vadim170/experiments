package io.dodo.blescanner.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.dodo.blescanner.ble.BleLogger
import io.dodo.blescanner.ble.BleScanService
import io.dodo.blescanner.ble.Prefs

class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionsGranted = requiredPermissions().all { result[it] ?: isGranted(it) }
        if (!permissionsGranted) {
            BleLogger.log("часть разрешений не выдана: " +
                result.filterValues { !it }.keys.joinToString())
        }
    }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BleLogger.init(applicationContext)
        permissionsGranted = requiredPermissions().all { isGranted(it) }

        setContent {
            AppTheme {
                MainScreen(
                    permissionsGranted = permissionsGranted,
                    logDir = BleLogger.logDirPath(),
                    onRequestPermissions = ::requestPermissions,
                    onStart = ::startScanning,
                    onStop = ::stopScanning,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted = requiredPermissions().all { isGranted(it) }
    }

    private fun startScanning() {
        if (!permissionsGranted) {
            requestPermissions()
            return
        }
        if (!ensureBluetoothOn()) return
        Prefs.setAutoStart(this, true)
        BleScanService.start(this)
    }

    private fun stopScanning() {
        Prefs.setAutoStart(this, false)
        BleScanService.stop(this)
    }

    private fun ensureBluetoothOn(): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            BleLogger.logError("на устройстве нет Bluetooth")
            return false
        }
        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }
        return true
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions().toTypedArray())
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Набор разрешений зависит от версии: до Android 12 BLE-скан требовал геолокацию. */
    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
