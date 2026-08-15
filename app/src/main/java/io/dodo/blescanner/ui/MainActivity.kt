package io.dodo.blescanner.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private var backgroundLocationGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        refreshPermissions()
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            BleLogger.log("не выданы разрешения: ${denied.joinToString()}")
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BleLogger.init(applicationContext)
        refreshPermissions()

        setContent {
            AppTheme {
                MainScreen(
                    permissionsGranted = permissionsGranted,
                    backgroundLocationGranted = backgroundLocationGranted,
                    logDir = BleLogger.logDirPath(),
                    onRequestPermissions = ::requestPermissions,
                    onRequestBackgroundLocation = ::requestBackgroundLocation,
                    onStart = ::startScanning,
                    onStop = ::stopScanning,
                    onOpenLocation = ::openInMaps,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        permissionsGranted = requiredPermissions().all { isGranted(it) }
        backgroundLocationGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                true
            }
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
            runCatching {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }.onFailure { BleLogger.logError("не смог попросить включить Bluetooth", it) }
            return false
        }
        return true
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions().toTypedArray())
    }

    /**
     * Фоновую локацию с Android 11 нельзя просить в одном диалоге с обычной —
     * система молча отклонит запрос. Просим отдельно и только после того,
     * как выдана обычная.
     */
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // На 11+ системный диалог ведёт в настройки, отдельного алерта нет
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
            }.onFailure { BleLogger.logError("не смог открыть настройки приложения", it) }
        } else {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun openInMaps(latitude: Double, longitude: Double, label: String) {
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { BleLogger.log("нет приложения, умеющего открыть geo:") }
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Набор разрешений зависит от версии: до Android 12 BLE-скан требовал геолокацию. */
    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // нужна на всех версиях: к обнаружениям привязываются координаты
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
