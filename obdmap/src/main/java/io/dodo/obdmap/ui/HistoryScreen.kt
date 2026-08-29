package io.dodo.obdmap.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dodo.obdmap.data.TripDatabase
import io.dodo.obdmap.data.TripEntity
import io.dodo.obdmap.obd.FuelMath

@Composable
fun HistoryScreen(onOpenTrip: (Long) -> Unit) {
    val context = LocalContext.current
    val dao = remember { TripDatabase.get(context).tripDao() }
    val trips by dao.observeTrips().collectAsStateWithLifecycle(initialValue = emptyList())

    if (trips.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Поездок пока нет.\nЗапиши первую на вкладке «Поездка».",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(trips, key = { it.id }) { trip ->
            TripRow(trip, onClick = { onOpenTrip(trip.id) })
        }
    }
}

@Composable
private fun TripRow(trip: TripEntity, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Fmt.dateTime(trip.startedAt), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (trip.finishedAt == null) "не завершена" else Fmt.km(trip.distanceMeters),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(6.dp))
            val average = FuelMath.averageLitersPer100Km(trip.fuelLiters, trip.distanceMeters)
            Text(
                text = buildString {
                    append("${Fmt.liters(trip.fuelLiters)} · ")
                    append("средний ${Fmt.litersPer100(average)} л/100 км · ")
                    append("макс ${Fmt.speed(trip.maxSpeedKmh)} км/ч")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "в движении ${Fmt.duration(trip.movingMillis)} · " +
                    "стоянка ${Fmt.duration(trip.idleMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
