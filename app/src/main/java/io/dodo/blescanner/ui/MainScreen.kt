package io.dodo.blescanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dodo.blescanner.ble.BleLogger
import io.dodo.blescanner.ble.BleRepository
import io.dodo.blescanner.ble.Geo
import io.dodo.blescanner.model.BleDevice
import io.dodo.blescanner.model.DeviceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    permissionsGranted: Boolean,
    backgroundLocationGranted: Boolean,
    logDir: String,
    onRequestPermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLocation: (latitude: Double, longitude: Double, label: String) -> Unit,
) {
    val devicesMap by BleRepository.devices.collectAsStateWithLifecycle()
    val scanning by BleRepository.scanning.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // самые свежие — сверху, прочитанные приоритетнее просто увиденных
    val devices = remember(devicesMap) {
        devicesMap.values.sortedWith(
            compareByDescending<BleDevice> { it.state == DeviceState.DONE }
                .thenByDescending { it.lastSeen },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("BLE Scanner")
                    Text(
                        text = if (scanning) "сканирую · устройств: ${devices.size}"
                        else "скан остановлен · устройств: ${devices.size}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            if (!permissionsGranted) {
                Banner(
                    title = "Нужны разрешения: Bluetooth, геолокация, уведомления",
                    button = "Выдать",
                    onClick = onRequestPermissions,
                )
            } else if (!backgroundLocationGranted) {
                Banner(
                    title = "Для координат при выключенном экране нужен доступ " +
                        "к геолокации «всегда»",
                    button = "Настроить",
                    onClick = onRequestBackgroundLocation,
                    warning = false,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Старт") }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Стоп") }
                OutlinedButton(onClick = { BleRepository.clear() }) { Text("Очистить") }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Устройства") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Лог") })
            }

            when (tab) {
                0 -> DeviceList(devices, onOpenLocation)
                else -> LogList(logDir)
            }
        }
    }
}

@Composable
private fun Banner(
    title: String,
    button: String,
    onClick: () -> Unit,
    warning: Boolean = true,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<BleDevice>,
    onOpenLocation: (Double, Double, String) -> Unit,
) {
    if (devices.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Устройств пока нет.\nНажми «Старт» — сканирование идёт в фоне.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(devices, key = { it.address }) { device -> DeviceCard(device, onOpenLocation) }
    }
}

@Composable
private fun DeviceCard(
    device: BleDevice,
    onOpenLocation: (Double, Double, String) -> Unit,
) {
    var expanded by rememberSaveable(device.address) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(device.state)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = timeFormat.format(Date(device.lastSeen)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = statusLine(device),
                style = MaterialTheme.typography.bodySmall,
                color = if (device.state == DeviceState.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            LocationLine(device, onOpenLocation)

            if (expanded) {
                if (device.detections.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(
                        "Точки обнаружения (${device.detections.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    device.detections.asReversed().forEach { detection ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = timeFormat.format(Date(detection.timeMs)),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${detection.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.width(8.dp))
                            val location = detection.location
                            if (location == null) {
                                Text(
                                    "без координат",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = location.formatWithAccuracy(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.clickable {
                                        onOpenLocation(
                                            location.latitude,
                                            location.longitude,
                                            device.displayName,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                if (device.values.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    device.values.groupBy { it.serviceName }.forEach { (serviceName, values) ->
                        Text(
                            text = serviceName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        values.forEach { value ->
                            Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                                Text(value.charName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = value.decoded ?: value.hex,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (value.ok) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                                if (value.decoded != null && value.ok) {
                                    Text(
                                        text = value.hex,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            } else if (device.values.isNotEmpty() || device.detections.size > 1) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Полей: ${device.values.size} · точек: ${device.detections.size} — " +
                        "нажми, чтобы раскрыть",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Где устройство видели последний раз и насколько оно «переехало» с первой встречи. */
@Composable
private fun LocationLine(
    device: BleDevice,
    onOpenLocation: (Double, Double, String) -> Unit,
) {
    val last = device.lastLocation
    Spacer(Modifier.height(2.dp))
    if (last == null) {
        Text(
            text = "📍 координат нет",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val first = device.firstLocation
    val spread = if (first != null && first != last) {
        " · разброс ${Geo.distanceMeters(first, last).toInt()} м"
    } else {
        ""
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "📍 ${last.formatWithAccuracy()}$spread",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        TextButton(
            onClick = { onOpenLocation(last.latitude, last.longitude, device.displayName) },
        ) {
            Text("на карте", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StateDot(state: DeviceState) {
    val color = when (state) {
        DeviceState.SEEN -> Color(0xFF9E9E9E)
        DeviceState.QUEUED -> Color(0xFFFFC107)
        DeviceState.READING -> Color(0xFF2196F3)
        DeviceState.DONE -> Color(0xFF4CAF50)
        DeviceState.ERROR -> Color(0xFFF44336)
    }
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

private fun statusLine(device: BleDevice): String {
    val base = when (device.state) {
        DeviceState.SEEN -> "замечено"
        DeviceState.QUEUED -> "в очереди на подключение"
        DeviceState.READING -> "подключаюсь и читаю…"
        DeviceState.DONE -> "прочитано в ${device.lastReadAt?.let { timeFormat.format(Date(it)) }}"
        DeviceState.ERROR -> "ошибка: ${device.lastError ?: "неизвестная"}"
    }
    return "$base · пакетов: ${device.seenCount} · попыток: ${device.attempts}"
}

@Composable
private fun LogList(logDir: String) {
    val lines by BleLogger.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Файлы лога: $logDir",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
