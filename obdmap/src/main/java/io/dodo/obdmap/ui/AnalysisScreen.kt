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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.dodo.obdmap.analysis.ConsumptionStats
import io.dodo.obdmap.analysis.DriveSample
import io.dodo.obdmap.analysis.Periods
import io.dodo.obdmap.data.TripDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Разбор истории: какой расход получается на какой скорости, насколько полна
 * выборка и как кривая менялась от периода к периоду.
 */
@Composable
fun AnalysisScreen() {
    val context = LocalContext.current
    val dao = remember { TripDatabase.get(context).tripDao() }

    var all by remember { mutableStateOf<List<DriveSample>?>(null) }
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) {
            dao.driveRows(null).map {
                DriveSample(it.speedKmh, it.litersPer100Km, it.accelerationMs2, it.timeMs)
            }
        }
    }

    // Ниже 20 км/ч мгновенный л/100 км почти бессмысленен: делим на околоноль
    var speedRange by remember { mutableStateOf(20f..140f) }
    var maxAcceleration by remember {
        mutableFloatStateOf(ConsumptionStats.STEADY_ACCELERATION_MS2.toFloat())
    }
    var filterByAcceleration by remember { mutableStateOf(true) }
    var histogramStep by remember { mutableStateOf<Double?>(null) }
    var speedStep by remember { mutableStateOf(SPEED_BIN_KMH) }
    var periodMode by remember { mutableStateOf(Periods.Mode.MONTH) }

    val samples = all
    if (samples == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState("Читаю историю…")
        }
        return
    }
    if (samples.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState("Нет данных для анализа.\nНужна поездка с посчитанным расходом.")
        }
        return
    }

    val accelerationLimit = if (filterByAcceleration) maxAcceleration.toDouble() else null

    val filtered = remember(samples, speedRange, maxAcceleration, filterByAcceleration) {
        ConsumptionStats.filter(
            samples = samples,
            minSpeedKmh = speedRange.start.toDouble(),
            maxSpeedKmh = speedRange.endInclusive.toDouble(),
            maxAbsAcceleration = accelerationLimit,
        )
    }
    val steadyAll = remember(samples, maxAcceleration, filterByAcceleration) {
        ConsumptionStats.filter(samples, 0.0, 300.0, accelerationLimit)
    }
    val bySpeed = remember(steadyAll, speedStep) {
        ConsumptionStats.bySpeedBin(steadyAll, binKmh = speedStep)
    }
    val histogram = remember(filtered, histogramStep) {
        ConsumptionStats.trimmedHistogram(
            values = filtered.map { it.litersPer100Km },
            binWidth = histogramStep,
        )
    }
    val speedCoverage = remember(samples, speedStep) {
        ConsumptionStats.speedHistogram(samples, speedStep)
    }
    val periods = remember(steadyAll, periodMode, speedStep) {
        Periods.groups(steadyAll, periodMode)
            .map { (label, group) ->
                label to ConsumptionStats.bySpeedBin(group, binKmh = speedStep)
            }
            .filter { it.second.size >= 2 }
    }

    val withAcceleration = remember(samples) { samples.count { it.accelerationMs2 != null } }
    val inSpeedRange = remember(samples, speedRange) {
        ConsumptionStats.filter(
            samples,
            speedRange.start.toDouble(),
            speedRange.endInclusive.toDouble(),
            null,
        ).size
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {

        SectionTitle("Отбор данных")
        Panel {
            Text(
                text = "Скорость ${speedRange.start.roundToInt()}–" +
                    "${speedRange.endInclusive.roundToInt()} км/ч",
                style = MaterialTheme.typography.titleSmall,
            )
            RangeSlider(
                value = speedRange,
                onValueChange = { speedRange = it },
                valueRange = 0f..200f,
                colors = sliderColors(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("Только стабильная скорость", filterByAcceleration) {
                    filterByAcceleration = !filterByAcceleration
                }
            }
            if (filterByAcceleration) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Максимум |ускорения|: " +
                        String.format(Locale.US, "%.2f", maxAcceleration) + " м/с²",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
                Slider(
                    value = maxAcceleration,
                    onValueChange = { maxAcceleration = it },
                    valueRange = 0.05f..3f,
                    colors = sliderColors(),
                )
            }
        }

        Panel {
            SectionTitle("Что в выборке")
            Spacer(Modifier.height(6.dp))
            Text(
                text = "всего замеров с расходом: ${samples.size}\n" +
                    "в диапазоне скорости: $inSpeedRange\n" +
                    "с известным ускорением: $withAcceleration\n" +
                    "после всех фильтров: ${filtered.size}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
            if (filterByAcceleration && withAcceleration == 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Ни у одного замера нет ускорения — поездки записаны до " +
                        "того, как оно появилось. С включённым фильтром они все " +
                        "отбрасываются: выключи фильтр или запиши новую поездку.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Coral,
                )
            }
        }

        SectionTitle("Шаг корзин")
        Panel {
            StepPills(
                title = "Расход, л/100 км",
                options = HISTOGRAM_STEPS,
                selected = histogramStep,
                onSelect = { histogramStep = it },
            )
            Spacer(Modifier.height(8.dp))
            StepPills(
                title = "Скорость, км/ч",
                options = SPEED_STEPS,
                selected = speedStep,
                onSelect = { speedStep = it ?: SPEED_BIN_KMH },
            )
        }

        ChartCard(
            title = "Распределение расхода",
            trailing = "${filtered.size} замеров",
            explanation = "Сколько замеров попало в каждую корзину расхода. Края " +
                "обрезаются по перцентилям: у границы отсечки по скорости " +
                "мгновенный расход улетает в сотни л/100 км, и без обрезки " +
                "гистограмма растянулась бы на тысячу корзин. Замеры выше " +
                "60 л/100 км отбрасываются как деление на почти ноль. Тап по " +
                "столбику показывает диапазон и число замеров.",
        ) {
            if (filtered.size < ConsumptionStats.MIN_BIN_COUNT) {
                EmptyState("Слишком мало замеров — расширь диапазон или ослабь фильтр.")
            } else {
                HistogramChart(bins = histogram, color = Palette.Amber, unit = "л/100")
                Spacer(Modifier.height(8.dp))
                val values = filtered.map { it.litersPer100Km }
                StatRow {
                    MiniStat(
                        title = "p25",
                        value = Fmt.litersPer100(ConsumptionStats.percentile(values, 0.25)),
                        modifier = Modifier.weight(1f),
                    )
                    MiniStat(
                        title = "медиана",
                        value = Fmt.litersPer100(ConsumptionStats.median(values)),
                        modifier = Modifier.weight(1f),
                        accent = Palette.Amber,
                    )
                    MiniStat(
                        title = "p75",
                        value = Fmt.litersPer100(ConsumptionStats.percentile(values, 0.75)),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        ChartCard(
            title = "Полнота выборки по скорости",
            trailing = "${samples.size} замеров",
            explanation = "Сколько замеров есть на каждой скорости. По этому " +
                "графику видно, каким участкам кривой расхода можно верить: там, " +
                "где столбик низкий, медиана посчитана по нескольким точкам. " +
                "Фильтры сюда не применяются — это вся история целиком.",
        ) {
            if (speedCoverage.isEmpty()) {
                EmptyState("Нет данных")
            } else {
                HistogramChart(bins = speedCoverage, color = Palette.Accent, unit = "км/ч")
            }
        }

        ChartCard(
            title = "Расход от стабильной скорости",
            trailing = "шаг ${speedStep.roundToInt()} км/ч",
            explanation = "Медиана расхода в каждой корзине скорости, линией. " +
                "Заливка — коридор от p25 до p75: по нему видно, где разброс " +
                "велик и цифре верить нельзя. Корзины меньше " +
                "${ConsumptionStats.MIN_BIN_COUNT} замеров отбрасываются. Тап по " +
                "графику показывает подробности корзины.",
        ) {
            when {
                bySpeed.isEmpty() -> EmptyState(
                    "Ни в один диапазон не набралось ${ConsumptionStats.MIN_BIN_COUNT} " +
                        "замеров. Возьми шаг покрупнее или ослабь фильтр.",
                )

                bySpeed.size < 2 -> Text(
                    "Набрался один диапазон — графика нет, но цифры ниже верны.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )

                else -> SpeedBinChart(
                    bins = bySpeed,
                    lineColor = Palette.Accent,
                    bandColor = Palette.Accent.copy(alpha = 0.18f),
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
                            color = Palette.TextSecondary,
                        )
                        Text(
                            "${Fmt.litersPer100(bin.median)} л/100 · ${bin.count}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextPrimary,
                        )
                    }
                }
            }
        }

        SectionTitle("Сравнение периодов")
        Panel {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Periods.Mode.entries.forEach { mode ->
                    Pill(mode.title, periodMode == mode) { periodMode = mode }
                }
            }
        }
        ChartCard(
            title = "Кривая расхода по периодам",
            trailing = "${periods.size} кривых",
            explanation = "Та же зависимость расхода от скорости, но отдельно для " +
                "каждого периода. Так видно, что кривая поднялась или изогнулась: " +
                "зимой, на другом бензине, после смены резины. Показываем не " +
                "больше ${Periods.DEFAULT_MAX_GROUPS} периодов — иначе линии " +
                "сливаются. Отбор данных тот же, что и выше, но без ограничения " +
                "по диапазону скорости.",
        ) {
            if (periods.size < 2) {
                EmptyState(
                    "Нужны хотя бы два периода с достаточным числом замеров. " +
                        "Попробуй другую разбивку или накопи больше поездок.",
                )
            } else {
                MultiSpeedBinChart(
                    series = periods,
                    colors = listOf(Palette.Accent, Palette.Amber, Palette.Coral, Palette.TextSecondary),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepPills(
    title: String,
    options: List<Double?>,
    selected: Double?,
    onSelect: (Double?) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                Pill(
                    text = option?.let { Fmt.number(it, if (it < 1) 1 else 0) } ?: "авто",
                    selected = selected == option,
                ) { onSelect(option) }
            }
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Palette.Accent,
    activeTrackColor = Palette.Accent,
    inactiveTrackColor = Palette.Outline,
)

private const val SPEED_BIN_KMH = 5.0

/** Шаги гистограммы расхода; null — автоматический подбор. */
private val HISTOGRAM_STEPS = listOf(null, 0.2, 0.5, 1.0, 2.0)

/** Шаги корзин по скорости. Крупнее 5 км/ч картина смазывается. */
private val SPEED_STEPS = listOf<Double?>(1.0, 2.0, 5.0)
