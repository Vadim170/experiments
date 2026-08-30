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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.dodo.obdmap.data.PointEntity
import io.dodo.obdmap.data.TripDatabase
import io.dodo.obdmap.data.TripEntity
import io.dodo.obdmap.analysis.TrackPalette
import io.dodo.obdmap.obd.FuelMath
import io.dodo.obdmap.util.Prefs

@Composable
fun TripDetailScreen(tripId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { TripDatabase.get(context).tripDao() }

    var loaded by remember(tripId) {
        mutableStateOf<Pair<TripEntity?, List<PointEntity>>?>(null)
    }
    LaunchedEffect(tripId) { loaded = dao.trip(tripId) to dao.points(tripId) }

    var colorMode by rememberSaveable {
        mutableStateOf(
            Prefs.colorMode(context)
                ?.let { runCatching { TrackPalette.Mode.valueOf(it) }.getOrNull() }
                ?: TrackPalette.Mode.SPEED,
        )
    }
    val speedThresholds = remember { Prefs.speedThresholds(context) }

    val data = loaded
    if (data == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Загружаю…") }
        return
    }
    val trip = data.first
    val points = data.second
    if (trip == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Поездка не найдена") }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text(Fmt.dateTime(trip.startedAt), style = MaterialTheme.typography.titleMedium)
        }

        val mapPoints = remember(points) {
            points.mapNotNull { point ->
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
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    "У поездки нет координат — GPS не ловился, есть только данные с шины.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TrackPalette.Mode.entries.forEach { mode ->
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
            TrackMap(
                points = mapPoints,
                mode = colorMode,
                speedThresholds = speedThresholds,
                modifier = Modifier.fillMaxWidth().height(320.dp).padding(12.dp),
                fitAll = true,
            )
            PaletteLegend(
                mode = colorMode,
                speedThresholds = speedThresholds,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            MapCacheCard(Modifier.padding(12.dp))
        }

        Stats(trip)

        if (points.size > 1) {
            SeriesCard(
                title = "Скорость, км/ч",
                values = points.map { it.speedKmh },
                color = MaterialTheme.colorScheme.primary,
            )
            SeriesCard(
                title = "Расход, л/100 км",
                values = points.map { it.litersPer100Km },
                color = MaterialTheme.colorScheme.tertiary,
            )
            SeriesCard(
                title = "Ускорение, м/с² (середина — ноль)",
                values = points.map { it.accelerationMs2?.plus(ACCEL_SHIFT) },
                color = MaterialTheme.colorScheme.error,
                maxOverride = ACCEL_SHIFT * 2,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Stats(trip: TripEntity) {
    val average = FuelMath.averageLitersPer100Km(trip.fuelLiters, trip.distanceMeters)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Cell("Пробег", Fmt.km(trip.distanceMeters))
                Cell("Топливо", Fmt.liters(trip.fuelLiters))
                Cell("Средний", Fmt.litersPer100(average))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Cell("Макс. скорость", "${Fmt.speed(trip.maxSpeedKmh)} км/ч")
                Cell("В движении", Fmt.duration(trip.movingMillis))
                Cell("Стоянка", Fmt.duration(trip.idleMillis))
            }
            if (trip.fuelSource.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Расход считался по: ${trip.fuelSource}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Cell(title: String, value: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** Карточка с графиком по точкам поездки. */
@Composable
private fun SeriesCard(
    title: String,
    values: List<Double?>,
    color: Color,
    maxOverride: Double? = null,
) {
    val present = remember(values) { values.filterNotNull() }
    if (present.isEmpty()) return

    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SeriesChart(
                values = values,
                color = color,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                maxOverride = maxOverride,
            )
        }
    }
}

/** На сколько сдвигаем ускорение, чтобы отрицательные значения попали на график. */
private const val ACCEL_SHIFT = 4.0
