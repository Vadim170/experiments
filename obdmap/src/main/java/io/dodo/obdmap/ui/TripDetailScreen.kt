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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.dodo.obdmap.analysis.TrackPalette
import io.dodo.obdmap.data.HistoryStore
import io.dodo.obdmap.data.PointEntity
import io.dodo.obdmap.data.TripDatabase
import io.dodo.obdmap.data.TripEntity
import io.dodo.obdmap.obd.FuelMath
import io.dodo.obdmap.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TripDetailScreen(tripId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { TripDatabase.get(context).tripDao() }

    var trip by remember(tripId) { mutableStateOf<TripEntity?>(null) }
    var points by remember(tripId) { mutableStateOf<List<PointEntity>?>(null) }
    var archived by remember(tripId) { mutableStateOf(false) }

    LaunchedEffect(tripId) {
        withContext(Dispatchers.IO) {
            trip = dao.trip(tripId)
            archived = dao.archive(tripId) != null
            points = HistoryStore.points(context, tripId)
        }
    }

    var colorMode by rememberSaveable {
        mutableStateOf(
            Prefs.colorMode(context)
                ?.let { runCatching { TrackPalette.Mode.valueOf(it) }.getOrNull() }
                ?: TrackPalette.Mode.SPEED,
        )
    }
    val speedThresholds = remember { Prefs.speedThresholds(context) }

    val loaded = trip
    val track = points
    if (loaded == null || track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState("Загружаю…")
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton("← Назад", onClick = onBack)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column {
                Text(Fmt.dateTime(loaded.startedAt), style = MaterialTheme.typography.titleMedium)
                if (archived) {
                    Text(
                        text = "из архива · трек прорежен",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextMuted,
                    )
                }
            }
        }

        val mapPoints = remember(track) {
            track.mapNotNull { point ->
                val latitude = point.latitude
                val longitude = point.longitude
                if (latitude != null && longitude != null) {
                    MapPoint(
                        latitude = latitude,
                        longitude = longitude,
                        speedKmh = point.speedKmh,
                        litersPer100Km = point.litersPer100Km,
                        accelerationMs2 = point.accelerationMs2,
                    )
                } else {
                    null
                }
            }
        }

        if (mapPoints.isEmpty()) {
            Panel { EmptyState("У поездки нет координат — есть только данные с шины.") }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TrackPalette.Mode.entries.forEach { mode ->
                    Pill(mode.title, colorMode == mode) {
                        colorMode = mode
                        Prefs.setColorMode(context, mode.name)
                    }
                }
            }
            TrackMap(
                points = mapPoints,
                mode = colorMode,
                speedThresholds = speedThresholds,
                modifier = Modifier.fillMaxWidth().height(300.dp),
                fitAll = true,
            )
            GradientLegend(mode = colorMode, speedThresholds = speedThresholds)
        }

        Stats(loaded)

        if (track.size > 1) {
            ChartCard(
                title = "Скорость, км/ч",
                explanation = "PID 0x0D по ходу поездки. Тап или удержание " +
                    "показывает значение в точке.",
            ) {
                InteractiveSeriesChart(
                    values = track.map { it.speedKmh },
                    color = Palette.Accent,
                    unit = "км/ч",
                )
            }
            ChartCard(
                title = "Расход, л/100 км",
                explanation = "Литры в час, делённые на скорость. Литры в час — " +
                    "либо PID 0x5E от блока, либо расчёт из расхода воздуха (MAF). " +
                    "Ниже 3 км/ч величина не определена.",
            ) {
                InteractiveSeriesChart(
                    values = track.map { it.litersPer100Km },
                    color = Palette.Amber,
                    unit = "л/100",
                )
            }
            ChartCard(
                title = "Ускорение, м/с²",
                explanation = "Наклон скорости по времени методом наименьших " +
                    "квадратов на окне 1.5 с. Ноль — посередине графика.",
            ) {
                InteractiveSeriesChart(
                    values = track.map { it.accelerationMs2?.plus(ACCEL_SHIFT) },
                    color = Palette.Coral,
                    unit = "м/с²",
                    maxOverride = ACCEL_SHIFT * 2,
                    valueOffset = -ACCEL_SHIFT,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private const val ACCEL_SHIFT = 4.0

@Composable
private fun Stats(trip: TripEntity) {
    val average = FuelMath.averageLitersPer100Km(trip.fuelLiters, trip.distanceMeters)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatRow {
            MiniStat(title = "Пробег", value = Fmt.km(trip.distanceMeters), modifier = Modifier.weight(1f))
            MiniStat(title = "Топливо", value = Fmt.liters(trip.fuelLiters), modifier = Modifier.weight(1f))
            MiniStat(
                title = "Средний",
                value = Fmt.litersPer100(average),
                modifier = Modifier.weight(1f),
                accent = Palette.Amber,
            )
        }
        StatRow {
            MiniStat(
                title = "Макс. скорость",
                value = Fmt.speed(trip.maxSpeedKmh),
                modifier = Modifier.weight(1f),
            )
            MiniStat(
                title = "В движении",
                value = Fmt.duration(trip.movingMillis),
                modifier = Modifier.weight(1f),
            )
            MiniStat(
                title = "Стоянка",
                value = Fmt.duration(trip.idleMillis),
                modifier = Modifier.weight(1f),
            )
        }
        if (trip.fuelSource.isNotBlank()) {
            Text(
                text = "Расход считался по: ${trip.fuelSource}",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
            )
        }
    }
}
