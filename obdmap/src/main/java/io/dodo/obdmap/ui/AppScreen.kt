package io.dodo.obdmap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.dodo.obdmap.obd.AdapterPicker

private enum class Tab(val title: String) {
    LIVE("Поездка"),
    HISTORY("История"),
    ANALYSIS("Анализ"),
    LOG("Лог"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    picker: AdapterPicker,
    permissionsGranted: Boolean,
    savedAdapterName: String?,
    savedAdapterAddress: String?,
    autoMode: Boolean,
    onRequestPermissions: () -> Unit,
    onPickAdapter: (address: String, name: String?) -> Unit,
    onSetAutoMode: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    var openedTripId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item && openedTripId == null,
                        onClick = {
                            tab = item
                            openedTripId = null
                        },
                        icon = {},
                        label = { Text(item.title, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val tripId = openedTripId
            when {
                tripId != null -> TripDetailScreen(
                    tripId = tripId,
                    onBack = { openedTripId = null },
                )

                tab == Tab.LIVE -> LiveScreen(
                    picker = picker,
                    permissionsGranted = permissionsGranted,
                    savedAdapterName = savedAdapterName,
                    savedAdapterAddress = savedAdapterAddress,
                    autoMode = autoMode,
                    onRequestPermissions = onRequestPermissions,
                    onPickAdapter = onPickAdapter,
                    onSetAutoMode = onSetAutoMode,
                    onStart = onStart,
                    onStop = onStop,
                    onStartScan = onStartScan,
                    onStopScan = onStopScan,
                )

                tab == Tab.HISTORY -> HistoryScreen(onOpenTrip = { openedTripId = it })

                tab == Tab.ANALYSIS -> AnalysisScreen()

                else -> LogScreen()
            }
        }
    }
}
