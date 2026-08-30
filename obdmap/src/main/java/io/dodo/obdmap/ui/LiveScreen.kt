package io.dodo.obdmap.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dodo.obdmap.analysis.TrackPalette
import io.dodo.obdmap.obd.AdapterPicker
import io.dodo.obdmap.obd.FoundAdapter
import io.dodo.obdmap.trip.ConnectionState
import io.dodo.obdmap.trip.LiveState
import io.dodo.obdmap.trip.TripSession
import io.dodo.obdmap.util.Prefs

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
    val context = LocalContext.current
    val live by TripSession.live.collectAsStateWithLifecycle()
    val track by TripSession.track.collectAsStateWithLifecycle()
    val series by TripSession.series.collectAsStateWithLifecycle()

    var pickerOpen by remember { mutableStateOf(false) }
    var showCharts by rememberSaveable { mutableStateOf(false) }
    var colorMode by rememberSaveable {
        mutableStateOf(
            Prefs.colorMode(context)
                ?.let { runCatching { TrackPalette.Mode.valueOf(it) }.getOrNull() }
                ?: TrackPalette.Mode.SPEED,
        )
    }
    val speedThresholds = remember { Prefs.speedThresholds(context) }

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

        StatusRow(live)

        // Крупные цифры: то, на что смотрят за рулём
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BigValue(
                modifier = Modifier.weight(1f),
                value = Fmt.speed(live.speedKmh),
                unit = "км/ч",
            )
            BigValue(
                modifier = Modifier.weight(1f),
                value = Fmt.litersPer100(live.litersPer100Km),
                // На стоянке л/100 км не определён — показываем мгновенный л/ч
                unit = if (live.litersPer100Km == null && live.fuelRateLitersPerHour != null) {
                    Fmt.litersPerHour(live.fuelRateLitersPerHour)
                } else {
                    "л/100 км"
                },
            )
            BigValue(
                modifier = Modifier.weight(1f),
                value = Fmt.acceleration(live.accelerationMs2),
                unit = "м/с²",
            )
        }

        Spacer(Modifier.height(8.dp))
        TripSummary(live)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !showCharts,
                onClick = { showCharts = false },
                label = { Text("Карта") },
            )
            FilterChip(
                selected = showCharts,
                onClick = { showCharts = true },
                label = { Text("Графики") },
            )
            if (!showCharts) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TrackPalette.Mode.entries.forEach { mode ->
                        Spacer(Modifier.width(6.dp))
                        FilterChip(
                            selected = colorMode == mode,
                            onClick = {
                                colorMode = mode
                                Prefs.setColorMode(context, mode.name)
                            },
                            label = { Text(mode.title) },
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            when {
                showCharts -> CurrentTripCharts(series)

                track.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (recording) {
                            "Жду координаты — трек появится, как только поймается GPS.\n" +
                                "Графики поездки уже пишутся на вкладке «Графики»."
                        } else {
                            "Выбери адаптер и нажми «Начать поездку»"
                        },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> Column(Modifier.fillMaxSize()) {
                    TrackMap(
                        points = track.map {
                            MapPoint(
                                latitude = it.latitude,
                                longitude = it.longitude,
                                speedKmh = it.speedKmh,
                                litersPer100Km = it.litersPer100Km,
                                accelerationMs2 = it.accelerationMs2,
                            )
                        },
                        mode = colorMode,
                        speedThresholds = speedThresholds,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        followLast = true,
                    )
                    PaletteLegend(
                        mode = colorMode,
                        speedThresholds = speedThresholds,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
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

/** История текущей поездки: как менялись скорость, расход и ускорение. */
@Composable
private fun CurrentTripCharts(series: List<io.dodo.obdmap.trip.LiveSample>) {
    if (series.size < 2) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Данных пока нет — графики появятся через несколько секунд записи",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChartBlock("Скорость, км/ч", MaterialTheme.colorScheme.primary) {
            SeriesChart(
                values = series.map { it.speedKmh },
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(90.dp),
            )
        }
        ChartBlock("Расход, л/100 км", MaterialTheme.colorScheme.tertiary) {
            SeriesChart(
                values = series.map { it.litersPer100Km },
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth().height(90.dp),
            )
        }
        ChartBlock("Ускорение, м/с² (середина — ноль)", MaterialTheme.colorScheme.error) {
            // Ускорение бывает отрицательным, а SeriesChart рисует от нуля вверх:
            // сдвигаем в положительную область и подписываем это в заголовке.
            val shifted = series.map { sample -> sample.accelerationMs2?.plus(ACCEL_SHIFT) }
            SeriesChart(
                values = shifted,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().height(90.dp),
                maxOverride = ACCEL_SHIFT * 2,
            )
        }
    }
}

/** На сколько сдвигаем ускорение, чтобы отрицательные значения попали на график. */
private const val ACCEL_SHIFT = 4.0

@Composable
private fun ChartBlock(title: String, color: Color, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = color)
            content()
        }
    }
}

@Composable
private fun StatusRow(live: LiveState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live.connection == ConnectionState.CONNECTING ||
                live.connection == ConnectionState.INITIALIZING
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = live.status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (live.connection == ConnectionState.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (live.diagnostics.isNotBlank()) {
            Text(
                text = "PID расхода: ${live.diagnostics}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BigValue(value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(unit, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
private fun TripSummary(live: LiveState) {
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
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
