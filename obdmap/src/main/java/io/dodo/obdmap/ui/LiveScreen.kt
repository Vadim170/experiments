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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
    openPicker: Boolean,
    onPickerHandled: () -> Unit,
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
    LaunchedEffect(openPicker) {
        if (openPicker) {
            pickerOpen = true
            onPickerHandled()
        }
    }

    var showCharts by rememberSaveable { mutableStateOf(false) }
    var colorMode by rememberSaveable {
        mutableStateOf(
            Prefs.colorMode(context)
                ?.let { runCatching { TrackPalette.Mode.valueOf(it) }.getOrNull() }
                ?: TrackPalette.Mode.SPEED,
        )
    }
    val speedThresholds = remember { Prefs.speedThresholds(context) }
    val tankLiters = remember { Prefs.tankLiters(context) }

    val busy = live.connection != ConnectionState.IDLE

    Column(Modifier.fillMaxSize()) {

        if (!permissionsGranted) {
            Panel(Modifier.fillMaxWidth().padding(12.dp), accent = Palette.Coral) {
                Text(
                    "Нужны разрешения: Bluetooth, геолокация, уведомления",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(10.dp))
                ActionButton("Выдать", onClick = onRequestPermissions)
            }
        }

        StatusStrip(live)
        Gauges(live, tankLiters)
        Spacer(Modifier.height(8.dp))
        TripStrip(live)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("Карта", !showCharts) { showCharts = false }
            Pill("Графики", showCharts) { showCharts = true }
            if (!showCharts) {
                TrackPalette.Mode.entries.forEach { mode ->
                    Pill(mode.title, colorMode == mode) {
                        colorMode = mode
                        Prefs.setColorMode(context, mode.name)
                    }
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 160.dp)
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            when {
                showCharts -> CurrentTripCharts(series)

                track.isEmpty() -> EmptyState(
                    if (busy) {
                        "Жду координаты — трек появится, как только поймается GPS. " +
                            "Показания и графики уже пишутся."
                    } else if (autoMode) {
                        "Автоматический режим включён. Поездка начнётся сама, " +
                            "когда заведёшь мотор."
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
                    GradientLegend(
                        mode = colorMode,
                        speedThresholds = speedThresholds,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Controls(
            savedAdapterName = savedAdapterName,
            savedAdapterAddress = savedAdapterAddress,
            autoMode = autoMode,
            busy = busy,
            onPickerOpen = { pickerOpen = true; onStartScan() },
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

/** Полоса состояния: точка, текст, протокол и что ответили PID. */
@Composable
private fun StatusStrip(live: LiveState) {
    val color = when (live.connection) {
        ConnectionState.LIVE -> Palette.Accent
        ConnectionState.ERROR -> Palette.Coral
        ConnectionState.IDLE -> Palette.TextMuted
        else -> Palette.Amber
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Spacer(Modifier.width(8.dp))
            Text(
                text = live.status,
                style = MaterialTheme.typography.bodySmall,
                color = if (live.connection == ConnectionState.ERROR) {
                    Palette.Coral
                } else {
                    Palette.TextSecondary
                },
            )
        }
        val details = listOfNotNull(
            live.protocol.takeIf { it.isNotBlank() },
            live.diagnostics.takeIf { it.isNotBlank() },
        )
        if (details.isNotEmpty()) {
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
            )
        }
    }
}

@Composable
private fun Gauges(live: LiveState, tankLiters: Float) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Readout(
            modifier = Modifier.weight(1f),
            caption = "Скорость",
            value = Fmt.speed(live.speedKmh),
            unit = "км/ч",
        )
        // На стоянке л/100 км не определён — крупно показываем л/ч, а не прочерк
        val showPerHour = live.litersPer100Km == null && live.fuelRateLitersPerHour != null
        Readout(
            modifier = Modifier.weight(1f),
            caption = "Расход",
            value = if (showPerHour) {
                Fmt.number(live.fuelRateLitersPerHour, decimals = 2)
            } else {
                Fmt.number(live.litersPer100Km)
            },
            unit = if (showPerHour) "л/ч · стоим" else "л/100 км",
            accent = Palette.Amber,
        )
    }

    Spacer(Modifier.height(8.dp))

    StatRow(Modifier.padding(horizontal = 12.dp)) {
        MiniStat(title = "Ускор.", value = Fmt.acceleration(live.accelerationMs2), modifier = Modifier.weight(1f))
        MiniStat(title = "Обороты", value = Fmt.rpm(live.rpm), modifier = Modifier.weight(1f))
        MiniStat(title = "Бак", value = fuelText(live.fuelLevelPercent, tankLiters), modifier = Modifier.weight(1f))
        MiniStat(title = "Двиг.", value = Fmt.temp(live.coolantTempC), modifier = Modifier.weight(1f))
    }
    if (live.fuelLevelPercent != null) {
        Text(
            text = fuelDetail(live.fuelLevelPercent, tankLiters),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** В плитке — литры: процент сам по себе мало что говорит. */
private fun fuelText(percent: Double?, tankLiters: Float): String {
    if (percent == null) return "—"
    return "${Fmt.number(percent / 100.0 * tankLiters)} л"
}

private fun fuelDetail(percent: Double, tankLiters: Float): String {
    val inTank = percent / 100.0 * tankLiters
    val free = tankLiters - inTank
    return "Бак ${Fmt.number(percent, 0)}% · залито ${Fmt.number(inTank)} л · " +
        "свободно ${Fmt.number(free)} л из ${tankLiters.toInt()} л"
}

@Composable
private fun TripStrip(live: LiveState) {
    StatRow(Modifier.padding(horizontal = 12.dp)) {
        MiniStat(title = "Пробег", value = Fmt.km(live.stats.distanceMeters), modifier = Modifier.weight(1f))
        MiniStat(title = "Топливо", value = Fmt.liters(live.stats.fuelLiters), modifier = Modifier.weight(1f))
        MiniStat(
            title = "Средний",
            value = Fmt.litersPer100(live.stats.averageLitersPer100Km),
            modifier = Modifier.weight(1f),
            accent = Palette.Amber,
        )
        MiniStat(title = "В пути", value = Fmt.duration(live.stats.durationMillis), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Controls(
    savedAdapterName: String?,
    savedAdapterAddress: String?,
    autoMode: Boolean,
    busy: Boolean,
    onPickerOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.padding(12.dp)) {
        if (savedAdapterAddress == null) {
            ActionButton("Выбрать адаптер", onClick = onPickerOpen, modifier = Modifier.fillMaxWidth())
            return@Column
        }
        // В автоматическом режиме кнопки не нужны: всё решает сервис
        if (autoMode) {
            Text(
                text = "Автоматический режим · ${savedAdapterName ?: savedAdapterAddress}",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            GhostButton(
                text = "Сменить адаптер",
                onClick = onPickerOpen,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        } else if (busy) {
            ActionButton(
                text = "Завершить поездку",
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                danger = true,
            )
        } else {
            ActionButton("Начать поездку", onClick = onStart, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** История текущей поездки: как менялись скорость, расход и ускорение. */
@Composable
private fun CurrentTripCharts(series: List<LiveSample>) {
    if (series.size < 2) {
        EmptyState("Данных пока нет — графики появятся через несколько секунд записи")
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChartCard(
            title = "Скорость, км/ч",
            explanation = "PID 0x0D, целые км/ч прямо с шины. Точка ставится в " +
                "каждом цикле опроса; провалы — это циклы, где блок не ответил.",
            modifier = Modifier.weight(1f),
        ) {
            InteractiveSeriesChart(
                values = series.map { it.speedKmh },
                color = Palette.Accent,
                unit = "км/ч",
                modifier = Modifier.fillMaxSize(),
            )
        }
        ChartCard(
            title = "Расход, л/100 км",
            explanation = "Мгновенный расход: литры в час, делённые на скорость. " +
                "Литры в час берутся от PID 0x5E, если блок его отдаёт, иначе " +
                "считаются из расхода воздуха (MAF) по стехиометрии 14.7 и " +
                "плотности бензина 745 г/л. Ниже 3 км/ч величина не определена и " +
                "на графике её нет.",
            modifier = Modifier.weight(1f),
        ) {
            InteractiveSeriesChart(
                values = series.map { it.litersPer100Km },
                color = Palette.Amber,
                unit = "л/100",
                modifier = Modifier.fillMaxSize(),
            )
        }
        ChartCard(
            title = "Ускорение, м/с²",
            explanation = "Наклон скорости по времени, посчитанный методом " +
                "наименьших квадратов на окне 1.5 секунды. Разностью соседних " +
                "замеров считать нельзя: скорость приходит целыми км/ч, и на " +
                "ровном ходу это давало бы ±1.1 м/с² шума. Ноль на графике — " +
                "посередине.",
            modifier = Modifier.weight(1f),
        ) {
            InteractiveSeriesChart(
                values = series.map { sample -> sample.accelerationMs2?.plus(ACCEL_SHIFT) },
                color = Palette.Coral,
                unit = "м/с²",
                modifier = Modifier.fillMaxSize(),
                maxOverride = ACCEL_SHIFT * 2,
                valueOffset = -ACCEL_SHIFT,
            )
        }
    }
}

/** На сколько сдвигаем ускорение, чтобы отрицательные значения попали на график. */
private const val ACCEL_SHIFT = 4.0

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
        containerColor = Palette.SurfaceHigh,
        title = {
            Text(
                if (scanning) "Ищу адаптеры…" else "Адаптеры",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            if (sorted.isEmpty()) {
                Text(
                    "Пока ничего не нашлось. Включи адаптер в разъём OBD-II и подожди.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    items(sorted, key = { it.address }) { adapter ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            Panel(onClick = { onPick(adapter) }) {
                                Text(
                                    text = adapter.displayName +
                                        if (adapter.looksLikeObd) "  ✓ похоже на OBD" else "",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = adapter.address +
                                        if (adapter.bonded) " · сопряжён" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Palette.TextMuted,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = Palette.Accent) }
        },
    )
}
