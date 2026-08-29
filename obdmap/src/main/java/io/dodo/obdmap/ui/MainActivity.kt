package io.dodo.obdmap.ui

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
import io.dodo.obdmap.obd.AdapterPicker
import io.dodo.obdmap.trip.TripService
import io.dodo.obdmap.util.Logger
import io.dodo.obdmap.util.Prefs

class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        refreshPermissions()
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) Logger.log("не выданы разрешения: ${denied.joinToString()}")
    }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private lateinit var picker: AdapterPicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(applicationContext)
        picker = AdapterPicker(applicationContext)
        refreshPermissions()

        setContent {
            AppTheme {
                AppScreen(
                    picker = picker,
                    permissionsGranted = permissionsGranted,
                    savedAdapterName = Prefs.adapterName(this),
                    savedAdapterAddress = Prefs.adapterAddress(this),
                    onRequestPermissions = ::requestPermissions,
                    onPickAdapter = { address, name ->
                        Prefs.setAdapter(this, address, name)
                        picker.stopScan()
                    },
                    onStart = ::startRecording,
                    onStop = { TripService.stop(this) },
                    onStartScan = {
                        if (permissionsGranted) {
                            picker.loadBonded()
                            picker.startScan()
                        } else {
                            requestPermissions()
                        }
                    },
                    onStopScan = { picker.stopScan() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onPause() {
        super.onPause()
        picker.stopScan()
    }

    private fun startRecording() {
        if (!permissionsGranted) {
            requestPermissions()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Logger.error("на устройстве нет Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            runCatching {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            return
        }
        val address = Prefs.adapterAddress(this)
        if (address == null) {
            Logger.error("адаптер не выбран")
            return
        }
        TripService.start(this, address)
    }

    private fun refreshPermissions() {
        permissionsGranted = requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions().toTypedArray())
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
