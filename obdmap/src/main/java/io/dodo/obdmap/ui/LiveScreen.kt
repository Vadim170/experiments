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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dodo.obdmap.analysis.TrackPalette
import io.dodo.obdmap.obd.AdapterPicker
import io.dodo.obdmap.obd.FoundAdapter
import io.dodo.obdmap.trip.ConnectionState
import io.dodo.obdmap.trip.LiveSample
import io.dodo.obdmap.trip.LiveState
import io.dodo.obdmap.trip.TripSession
import io.dodo.obdmap.util.Prefs

@Composable
fun LiveScreen(
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

    val busy = live.connection != ConnectionState.IDLE
    val recording = live.connection == ConnectionState.LIVE

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
        Gauges(live)
        Spacer(Modifier.height(6.dp))
        TripSummary(live)
        Spacer(Modifier.height(6.dp))
        ViewSwitch(
            showCharts = showCharts,
            onShowCharts = { showCharts = it },
            colorMode = colorMode,
            onColorMode = {
                colorMode = it
                Prefs.setColorMode(context, it.name)
            },
        )

        // Карте отдаём всё оставшееся место, но не меньше минимума: иначе на
        // низком экране она схлопывается в ноль.
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 160.dp)
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            when {
                showCharts -> CurrentTripCharts(series)

                track.isEmpty() -> Hint(
                    if (busy) {
                        "Жду координаты — трек появится, как только поймается GPS.\n" +
                            "Показания и графики уже пишутся."
                    } else {
                        "Выбери адаптер и начни поездку"
                    },
                )

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
                        showVehicle = true,
                    )
                    PaletteLegend(
                        mode = colorMode,
                        speedThresholds = speedThresholds,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }

        Controls(
            savedAdapterName = savedAdapterName,
            savedAdapterAddress = savedAdapterAddress,
            autoMode = autoMode,
            busy = busy,
            recording = recording,
            onPickerOpen = { pickerOpen = true; onStartScan() },
            onSetAutoMode = onSetAutoMode,
            onStart = onStart,
            onStop = onStop,
        )
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

/**
 * Показания. Две крупные плитки — то, на что смотрят за рулём, под ними
 * четыре мелких. Каждая плитка занимает равную долю ширины и режет длинный
 * текст: иначе одно большое число разъезжает всю строку.
 */
@Composable
private fun Gauges(live: LiveState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BigTile(
            modifier = Modifier.weight(1f),
            value = Fmt.speed(live.speedKmh),
            unit = "км/ч",
        )
        // На стоянке л/100 км не определён (деление на ноль), поэтому крупно
        // показываем л/ч, а не прочерк.
        val showPerHour = live.litersPer100Km == null && live.fuelRateLitersPerHour != null
        BigTile(
            modifier = Modifier.weight(1f),
            value = if (showPerHour) {
                Fmt.number(live.fuelRateLitersPerHour, decimals = 2)
            } else {
                Fmt.number(live.litersPer100Km)
            },
            unit = if (showPerHour) "л/ч (стоим)" else "л/100 км",
        )
    }

    Spacer(Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallTile(Modifier.weight(1f), "Ускорение", Fmt.acceleration(live.accelerationMs2), "м/с²")
        SmallTile(Modifier.weight(1f), "Обороты", Fmt.rpm(live.rpm), "об/мин")
        SmallTile(Modifier.weight(1f), "Бак", Fmt.percent(live.fuelLevelPercent), "")
        SmallTile(Modifier.weight(1f), "Двигатель", Fmt.temp(live.coolantTempC), "")
    }
}

@Composable
private fun BigTile(value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SmallTile(modifier: Modifier, title: String, value: String, unit: String) {
    Card(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ViewSwitch(
    showCharts: Boolean,
    onShowCharts: (Boolean) -> Unit,
    colorMode: TrackPalette.Mode,
    onColorMode: (TrackPalette.Mode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = !showCharts,
            onClick = { onShowCharts(false) },
            label = { Text("Карта") },
        )
        FilterChip(
            selected = showCharts,
            onClick = { onShowCharts(true) },
            label = { Text("Графики") },
        )
        if (!showCharts) {
            TrackPalette.Mode.entries.forEach { mode ->
                FilterChip(
                    selected = colorMode == mode,
                    onClick = { onColorMode(mode) },
                    label = { Text(mode.title) },
                )
            }
        }
    }
}

@Composable
private fun Controls(
    savedAdapterName: String?,
    savedAdapterAddress: String?,
    autoMode: Boolean,
    busy: Boolean,
    recording: Boolean,
    onPickerOpen: () -> Unit,
    onSetAutoMode: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Автоматический режим", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Поездка начнётся сама, когда заведёшь мотор",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoMode,
                onCheckedChange = onSetAutoMode,
                enabled = savedAdapterAddress != null,
            )
        }

        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = onPickerOpen,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) {
            Text(
                text = savedAdapterName?.let { "Адаптер: $it" }
                    ?: savedAdapterAddress?.let { "Адаптер: $it" }
                    ?: "Выбрать адаптер",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // В автоматическом режиме кнопка не нужна: режимом управляет переключатель
        if (!autoMode) {
            Spacer(Modifier.height(6.dp))
            if (busy) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(if (recording) "Завершить поездку" else "Отменить")
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
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/** История текущей поездки: как менялись скорость, расход и ускорение. */
@Composable
private fun CurrentTripCharts(series: List<LiveSample>) {
    if (series.size < 2) {
        Hint("Данных пока нет — графики появятся через несколько секунд записи")
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChartBlock("Скорость, км/ч", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) {
            SeriesChart(
                values = series.map { it.speedKmh },
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ChartBlock("Расход, л/100 км", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f)) {
            SeriesChart(
                values = series.map { it.litersPer100Km },
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ChartBlock(
            "Ускорение, м/с² (середина — ноль)",
            MaterialTheme.colorScheme.error,
            Modifier.weight(1f),
        ) {
            // SeriesChart рисует от нуля вверх, а ускорение бывает отрицательным:
            // сдвигаем в положительную область и подписываем это в заголовке.
            SeriesChart(
                values = series.map { sample -> sample.accelerationMs2?.plus(ACCEL_SHIFT) },
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxSize(),
                maxOverride = ACCEL_SHIFT * 2,
            )
        }
    }
}

/** На сколько сдвигаем ускорение, чтобы отрицательные значения попали на график. */
private const val ACCEL_SHIFT = 4.0

@Composable
private fun ChartBlock(
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun StatusRow(live: LiveState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live.connection == ConnectionState.CONNECTING ||
                live.connection == ConnectionState.INITIALIZING ||
                live.connection == ConnectionState.WAITING
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = live.status,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TripSummary(live: LiveState) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Cell(Modifier.weight(1f), "Пробег", Fmt.km(live.stats.distanceMeters))
            Cell(Modifier.weight(1f), "Топливо", Fmt.liters(live.stats.fuelLiters))
            Cell(Modifier.weight(1f), "Средний", Fmt.litersPer100(live.stats.averageLitersPer100Km))
            Cell(Modifier.weight(1f), "В пути", Fmt.duration(live.stats.durationMillis))
        }
    }
}

@Composable
private fun Cell(modifier: Modifier, title: String, value: String) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
