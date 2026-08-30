package io.dodo.obdmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.dodo.obdmap.obd.AdapterPicker

private enum class Tab(val title: String) {
    LIVE("Поездка"),
    HISTORY("История"),
    ANALYSIS("Анализ"),
    SETTINGS("Настройки"),
}

/**
 * Каркас приложения. Нижняя панель своя, а не Material NavigationBar: нужна
 * плоская полоса под тёмную тему, без ряби подложек и волн нажатия.
 */
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
    var pickerFromSettings by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                    openPicker = pickerFromSettings,
                    onPickerHandled = { pickerFromSettings = false },
                    onRequestPermissions = onRequestPermissions,
                    onPickAdapter = onPickAdapter,
                    onStart = onStart,
                    onStop = onStop,
                    onStartScan = onStartScan,
                    onStopScan = onStopScan,
                )

                tab == Tab.HISTORY -> HistoryScreen(onOpenTrip = { openedTripId = it })

                tab == Tab.ANALYSIS -> AnalysisScreen()

                else -> SettingsScreen(
                    autoMode = autoMode,
                    adapterName = savedAdapterName,
                    adapterAddress = savedAdapterAddress,
                    onSetAutoMode = onSetAutoMode,
                    onPickAdapter = {
                        // Выбор адаптера живёт на экране поездки — уходим туда
                        pickerFromSettings = true
                        tab = Tab.LIVE
                        onStartScan()
                    },
                )
            }
        }

        BottomBar(
            selected = tab,
            onSelect = {
                tab = it
                openedTripId = null
            },
        )
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column {
        Divider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { item ->
                val active = item == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(item) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Активную вкладку помечаем чертой сверху, а не заливкой
                    Box(
                        Modifier
                            .width(22.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) Palette.Accent else Palette.Surface),
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) Palette.TextPrimary else Palette.TextMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
