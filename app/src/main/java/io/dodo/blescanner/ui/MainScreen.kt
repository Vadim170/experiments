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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    logDir: String,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val devicesMap by BleRepository.devices.collectAsStateWithLifecycle()
    val scanning by BleRepository.scanning.collectAsStateWithLifecycle()
    var tab by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }

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
                PermissionBanner(onRequestPermissions)
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
                0 -> DeviceList(devices)
                else -> LogList(logDir)
            }
        }
    }
}

@Composable
private fun PermissionBanner(onRequestPermissions: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Нужны разрешения на Bluetooth и уведомления",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequestPermissions) { Text("Выдать") }
        }
    }
}

@Composable
private fun DeviceList(devices: List<BleDevice>) {
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
        items(devices, key = { it.address }) { device -> DeviceCard(device) }
    }
}

@Composable
private fun DeviceCard(device: BleDevice) {
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

            if (device.values.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                if (!expanded) {
                    Text(
                        "Прочитано полей: ${device.values.size} — нажми, чтобы раскрыть",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
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
            }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(4.dp)),
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
