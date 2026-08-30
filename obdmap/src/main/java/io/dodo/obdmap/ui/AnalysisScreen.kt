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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.dodo.obdmap.analysis.ConsumptionStats
import io.dodo.obdmap.analysis.DriveSample
import io.dodo.obdmap.data.TripDatabase
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Разбор истории: какой расход получается на какой скорости.
 *
 * Смысл фильтра по ускорению — отделить установившееся движение от разгонов.
 * Без него медиана «на 60 км/ч» смешивает равномерную езду с разгоном до сотни,
 * и цифра получается бессмысленной.
 */
@Composable
fun AnalysisScreen() {
    val context = LocalContext.current
    val dao = remember { TripDatabase.get(context).tripDao() }

    var all by remember { mutableStateOf<List<DriveSample>?>(null) }
    LaunchedEffect(Unit) {
        all = dao.driveRows(null).map {
            DriveSample(it.speedKmh, it.litersPer100Km, it.accelerationMs2)
        }
    }

    // Ниже 20 км/ч мгновенный л/100 км почти бессмысленен: делим на околоноль
    var speedRange by remember { mutableStateOf(20f..140f) }
    var maxAcceleration by remember {
        mutableStateOf(ConsumptionStats.STEADY_ACCELERATION_MS2.toFloat())
    }
    var filterByAcceleration by remember { mutableStateOf(true) }

    val samples = all
    if (samples == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Читаю историю…") }
        return
    }
    if (samples.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Нет данных для анализа.\nНужна хотя бы одна поездка с посчитанным расходом.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    val filtered = remember(samples, speedRange, maxAcceleration, filterByAcceleration) {
        ConsumptionStats.filter(
            samples = samples,
            minSpeedKmh = speedRange.start.toDouble(),
            maxSpeedKmh = speedRange.endInclusive.toDouble(),
            maxAbsAcceleration = if (filterByAcceleration) maxAcceleration.toDouble() else null,
        )
    }
    // Кривая «расход от стабильной скорости» строится по всему диапазону:
    // ограничение по скорости здесь только мешало бы видеть картину целиком.
    val bySpeed = remember(samples, maxAcceleration, filterByAcceleration) {
        ConsumptionStats.bySpeedBin(
            samples = ConsumptionStats.filter(
                samples = samples,
                minSpeedKmh = 0.0,
                maxSpeedKmh = 300.0,
                maxAbsAcceleration = if (filterByAcceleration) maxAcceleration.toDouble() else null,
            ),
            binKmh = SPEED_BIN_KMH,
        )
    }
    // Обрезаем края: без фильтра по ускорению в выборку попадают разгоны с
    // расходом в десятки л/100 км, и гистограмма по сырому min..max
    // превращалась в пустой прямоугольник из сотен корзин.
    val histogram = remember(filtered) {
        ConsumptionStats.trimmedHistogram(filtered.map { it.litersPer100Km })
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Диапазон скорости: ${speedRange.start.roundToInt()}–" +
                        "${speedRange.endInclusive.roundToInt()} км/ч",
                    style = MaterialTheme.typography.titleSmall,
                )
                RangeSlider(
                    value = speedRange,
                    onValueChange = { speedRange = it },
                    valueRange = 0f..200f,
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = filterByAcceleration,
                        onClick = { filterByAcceleration = !filterByAcceleration },
                        label = { Text("Только стабильная скорость") },
                    )
                }
                if (filterByAcceleration) {
                    Text(
                        "Максимум |ускорения|: " +
                            String.format(Locale.US, "%.2f", maxAcceleration) + " м/с²",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = maxAcceleration,
                        onValueChange = { maxAcceleration = it },
                        valueRange = 0.05f..3f,
                    )
                    Text(
                        "Замеры без известного ускорения при этом фильтре отбрасываются: " +
                            "они могли быть разгоном. Выключи фильтр, чтобы увидеть всё " +
                            "подряд, включая разгоны и торможения.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Распределение расхода в диапазоне", style = MaterialTheme.typography.titleSmall)
                Text(
                    "замеров: ${filtered.size} из ${samples.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.size < ConsumptionStats.MIN_BIN_COUNT) {
                    Text(
                        text = "Слишком мало замеров. Расширь диапазон скорости, " +
                            "ослабь фильтр по ускорению или запиши больше поездок. " +
                            "Всего замеров с расходом в истории: ${samples.size}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    HistogramChart(
                        bins = histogram,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    val values = filtered.map { it.litersPer100Km }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Stat("p25", ConsumptionStats.percentile(values, 0.25))
                        Stat("медиана", ConsumptionStats.median(values))
                        Stat("p75", ConsumptionStats.percentile(values, 0.75))
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Расход от стабильной скорости",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "медиана линией, коридор p25–p75 заливкой; шаг " +
                        "${SPEED_BIN_KMH.roundToInt()} км/ч",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (bySpeed.size < 2) {
                    Text(
                        "Пока не хватает данных: нужны замеры хотя бы в двух диапазонах скорости.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    SpeedBinChart(
                        bins = bySpeed,
                        lineColor = MaterialTheme.colorScheme.primary,
                        bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    bySpeed.forEach { bin ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${bin.speedFrom.roundToInt()}–${bin.speedTo.roundToInt()} км/ч",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "${Fmt.litersPer100(bin.median)} л/100 · ${bin.count} замеров",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SPEED_BIN_KMH = 10.0

@Composable
private fun Stat(title: String, value: Double?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelSmall)
        Text(
            "${Fmt.litersPer100(value)} л/100",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
