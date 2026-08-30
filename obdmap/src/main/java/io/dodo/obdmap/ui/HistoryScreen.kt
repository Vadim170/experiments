package io.dodo.obdmap.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dodo.obdmap.data.HistoryStore
import io.dodo.obdmap.data.TripDatabase
import io.dodo.obdmap.data.TripEntity
import io.dodo.obdmap.obd.FuelMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(onOpenTrip: (Long) -> Unit) {
    val context = LocalContext.current
    val dao = remember { TripDatabase.get(context).tripDao() }
    val trips by dao.observeTrips().collectAsStateWithLifecycle(initialValue = emptyList())

    if (trips.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState("Поездок пока нет.\nПервая запишется сама, когда заведёшь мотор.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionTitle("Поездки", trailing = "${trips.size}")
        }
        items(trips, key = { it.id }) { trip ->
            TripRow(trip, onClick = { onOpenTrip(trip.id) })
        }
    }
}

@Composable
private fun TripRow(trip: TripEntity, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnail by remember(trip.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    // Миниатюра рисуется один раз и лежит на диске: разворачивать трек на
    // каждую прокрутку списка нельзя.
    LaunchedEffect(trip.id, trip.finishedAt) {
        thumbnail = withContext(Dispatchers.IO) {
            val points = HistoryStore.points(context, trip.id)
            val cached = TripThumbnails.cached(context, trip.id, points.size)
            (cached ?: TripThumbnails.render(context, trip.id, points))?.asImageBitmap()
        }
    }

    Panel(Modifier.fillMaxWidth(), onClick = onClick) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            val image = thumbnail
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "Маршрут поездки",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = "без координат",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextMuted,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Fmt.dateTime(trip.startedAt), style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (trip.finishedAt == null) "пишется" else Fmt.km(trip.distanceMeters),
                style = ReadoutStyle.copy(
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    color = Palette.Accent,
                ),
            )
        }
        Spacer(Modifier.height(6.dp))

        val average = FuelMath.averageLitersPer100Km(trip.fuelLiters, trip.distanceMeters)
        StatRow {
            MiniStat(title = "Топливо", value = Fmt.liters(trip.fuelLiters), modifier = Modifier.weight(1f))
            MiniStat(
                title = "Средний",
                value = Fmt.litersPer100(average),
                modifier = Modifier.weight(1f),
                accent = Palette.Amber,
            )
            MiniStat(
                title = "Макс",
                value = Fmt.speed(trip.maxSpeedKmh),
                modifier = Modifier.weight(1f),
            )
            MiniStat(
                title = "В пути",
                value = Fmt.duration(trip.movingMillis),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
