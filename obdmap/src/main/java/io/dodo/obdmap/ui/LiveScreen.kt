package io.dodo.obdmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dodo.obdmap.obd.AdapterPicker
import io.dodo.obdmap.obd.FoundAdapter
import io.dodo.obdmap.trip.ConnectionState
import io.dodo.obdmap.trip.TripSession

@Composable
fun LiveScreen(
    picker: AdapterPicker,
    permissionsGranted: Boolean,
    savedAdapterName: String?,
    savedAdapterAddress: String?,
    onRequestPermissions: () -> Unit,
    onPickAdapter: (address: String, name: String?) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    val live by TripSession.live.collectAsStateWithLifecycle()
    val track by TripSession.track.collectAsStateWithLifecycle()
    var pickerOpen by remember { mutableStateOf(false) }

    val recording = live.connection == ConnectionState.LIVE ||
        live.connection == ConnectionState.CONNECTING ||
        live.connection == ConnectionState.INITIALIZING

    Column(Modifier.fillMaxSize()) {

        if (!permissionsGranted) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Нужны разрешения: Bluetooth, геолокация, уведомления",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestPermissions) { Text("Выдать") }
                }
            }
        }

        StatusRow(live.status, live.connection)

        // Крупные цифры: скорость и мгновенный расход — то, на что смотрят за рулём
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BigValue(
                modifier = Modifier.weight(1f),
                value = Fmt.speed(live.speedKmh),
                unit = "км/ч",
            )
            BigValue(
                modifier = Modifier.weight(1f),
                value = live.litersPer100Km?.let { Fmt.litersPer100(it) }
                    ?: Fmt.litersPer100(null),
                // На стоянке л/100 км не определён, показываем мгновенный л/ч
                unit = if (live.litersPer100Km == null && live.fuelRateLitersPerHour != null) {
                    Fmt.litersPerHour(live.fuelRateLitersPerHour)
                } else {
                    "л/100 км"
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        TripSummary(live)
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (track.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (recording) {
                            "Жду координаты — трек появится, как только поймается GPS"
                        } else {
                            "Выбери адаптер и нажми «Начать поездку»"
                        },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                TrackMap(
                    points = track.map { MapPoint(it.latitude, it.longitude) },
                    modifier = Modifier.fillMaxSize(),
                    followLast = true,
                )
            }
        }

        Column(Modifier.padding(12.dp)) {
            OutlinedButton(
                onClick = { pickerOpen = true; onStartScan() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !recording,
            ) {
                Text(
                    savedAdapterName?.let { "Адаптер: $it" }
                        ?: savedAdapterAddress?.let { "Адаптер: $it" }
                        ?: "Выбрать адаптер",
                )
            }
            Spacer(Modifier.height(8.dp))
            if (recording) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("Завершить поездку")
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = savedAdapterAddress != null,
                ) {
                    Text("Начать поездку")
                }
            }
        }
    }

    if (pickerOpen) {
        AdapterPickerDialog(
            picker = picker,
            onPick = { adapter ->
                onPickAdapter(adapter.address, adapter.name)
                pickerOpen = false
            },
            onDismiss = {
                pickerOpen = false
                onStopScan()
            },
        )
    }
}

@Composable
private fun StatusRow(status: String, state: ConnectionState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == ConnectionState.CONNECTING || state == ConnectionState.INITIALIZING) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state == ConnectionState.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun BigValue(value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text(unit, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TripSummary(live: io.dodo.obdmap.trip.LiveState) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Cell("Пробег", Fmt.km(live.stats.distanceMeters))
                Cell("Топливо", Fmt.liters(live.stats.fuelLiters))
                Cell("Средний", Fmt.litersPer100(live.stats.averageLitersPer100Km))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Cell("В пути", Fmt.duration(live.stats.durationMillis))
                Cell("Обороты", Fmt.rpm(live.rpm))
                Cell("Бак", Fmt.percent(live.fuelLevelPercent))
            }
            if (live.connection == ConnectionState.LIVE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Расход: ${live.fuelSource.title}" +
                        if (!live.hasLocation) " · GPS не ловится" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Cell(title: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(title, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun AdapterPickerDialog(
    picker: AdapterPicker,
    onPick: (FoundAdapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val adapters by picker.adapters.collectAsStateWithLifecycle()
    val scanning by picker.scanning.collectAsStateWithLifecycle()

    // Похожие на ELM327 — наверх, дальше сопряжённые, дальше по уровню сигнала
    val sorted = remember(adapters) {
        adapters.values.sortedWith(
            compareByDescending<FoundAdapter> { it.looksLikeObd }
                .thenByDescending { it.bonded }
                .thenByDescending { it.rssi },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (scanning) "Ищу адаптеры…" else "Адаптеры") },
        text = {
            if (sorted.isEmpty()) {
                Text("Пока ничего не нашлось. Включи адаптер в разъём OBD-II и подожди.")
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    items(sorted, key = { it.address }) { adapter ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        ) {
                            TextButton(onClick = { onPick(adapter) }) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = adapter.displayName +
                                            if (adapter.looksLikeObd) "  ✓ похоже на OBD" else "",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = adapter.address +
                                            if (adapter.bonded) " · сопряжён" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
