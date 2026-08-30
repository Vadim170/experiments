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
import androidx.compose.foundation.horizontalScroll
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

    // null — шаг подбирается автоматически по разбросу
    var histogramStep by remember { mutableStateOf<Double?>(null) }
    var speedStep by remember { mutableStateOf(SPEED_BIN_KMH) }

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
    val bySpeed = remember(samples, maxAcceleration, filterByAcceleration, speedStep) {
        ConsumptionStats.bySpeedBin(
            samples = ConsumptionStats.filter(
                samples = samples,
                minSpeedKmh = 0.0,
                maxSpeedKmh = 300.0,
                maxAbsAcceleration = if (filterByAcceleration) maxAcceleration.toDouble() else null,
            ),
            binKmh = speedStep,
        )
    }
    // Обрезаем края: без фильтра по ускорению в выборку попадают разгоны с
    // расходом в десятки л/100 км, и гистограмма по сырому min..max
    // превращалась в пустой прямоугольник из сотен корзин.
    // Сколько замеров вообще знают своё ускорение. Точки старых поездок
    // записаны до его появления, у них null — и жёсткий фильтр выкидывает их все.
    val withAcceleration = remember(samples) { samples.count { it.accelerationMs2 != null } }
    val inSpeedRange = remember(samples, speedRange) {
        ConsumptionStats.filter(
            samples = samples,
            minSpeedKmh = speedRange.start.toDouble(),
            maxSpeedKmh = speedRange.endInclusive.toDouble(),
            maxAbsAcceleration = null,
        ).size
    }

    val histogram = remember(filtered, histogramStep) {
        ConsumptionStats.trimmedHistogram(
            values = filtered.map { it.litersPer100Km },
            binWidth = histogramStep,
        )
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

        SampleSummary(
            total = samples.size,
            inSpeedRange = inSpeedRange,
            withAcceleration = withAcceleration,
            afterFilters = filtered.size,
            filterOn = filterByAcceleration,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Распределение расхода в диапазоне", style = MaterialTheme.typography.titleSmall)
                Text(
                    "замеров: ${filtered.size} из ${samples.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StepChips(
                    title = "Шаг, л/100 км",
                    options = HISTOGRAM_STEPS,
                    selected = histogramStep,
                    onSelect = { histogramStep = it },
                    format = { Fmt.number(it, decimals = if (it < 1) 1 else 0) },
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
                    "медиана линией, коридор p25–p75 заливкой",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StepChips(
                    title = "Шаг, км/ч",
                    options = SPEED_STEPS,
                    selected = speedStep,
                    onSelect = { speedStep = it ?: SPEED_BIN_KMH },
                    format = { Fmt.number(it, decimals = 0) },
                )
                Spacer(Modifier.height(8.dp))
                if (bySpeed.isEmpty()) {
                    Text(
                        text = "Ни в один диапазон не набралось " +
                            "${ConsumptionStats.MIN_BIN_COUNT} замеров. Возьми шаг покрупнее " +
                            "или ослабь фильтр по ускорению.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (bySpeed.size < 2) {
                    // График по одной точке бессмысленен, но цифру показать честно
                    Text(
                        "Пока набрался один диапазон — графика нет, но цифры ниже верны.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    SpeedBinChart(
                        bins = bySpeed,
                        lineColor = MaterialTheme.colorScheme.primary,
                        bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                }
                if (bySpeed.isNotEmpty()) {
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

/**
 * Сводка по выборке: сколько замеров осталось после каждого сита.
 * Без неё «данных не хватает» ничего не объясняет.
 */
@Composable
private fun SampleSummary(
    total: Int,
    inSpeedRange: Int,
    withAcceleration: Int,
    afterFilters: Int,
    filterOn: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Что в выборке", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "всего замеров с расходом: $total\n" +
                    "в диапазоне скорости: $inSpeedRange\n" +
                    "с известным ускорением: $withAcceleration\n" +
                    "после всех фильтров: $afterFilters",
                style = MaterialTheme.typography.bodySmall,
            )
            if (filterOn && withAcceleration == 0 && total > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Ни у одного замера нет ускорения — поездки записаны до того, " +
                        "как оно появилось. С включённым фильтром они все отбрасываются: " +
                        "выключи фильтр или запиши новую поездку.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private const val SPEED_BIN_KMH = 5.0

/** Шаги гистограммы расхода; null — автоматический подбор. */
private val HISTOGRAM_STEPS = listOf(null, 0.2, 0.5, 1.0, 2.0)

/** Шаги корзин по скорости. Крупнее 5 км/ч смысла нет — картина смазывается. */
private val SPEED_STEPS = listOf(1.0, 2.0, 5.0)

/**
 * Ряд чипов для выбора шага. [selected] == null означает автоматический
 * подбор, если он есть среди [options].
 */
@Composable
private fun StepChips(
    title: String,
    options: List<Double?>,
    selected: Double?,
    onSelect: (Double?) -> Unit,
    format: (Double) -> String,
) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option?.let(format) ?: "авто") },
                )
            }
        }
    }
}

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
