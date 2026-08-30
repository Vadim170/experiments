package io.dodo.obdmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.dodo.obdmap.analysis.HistogramBin
import io.dodo.obdmap.analysis.SpeedBinStats
import io.dodo.obdmap.analysis.TrackPalette

/**
 * Карточка графика: заголовок, значок «i» с объяснением расчёта и сам график.
 * Объяснение — половина ценности: по цифре не видно, откуда она взялась.
 */
@Composable
fun ChartCard(
    title: String,
    explanation: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Panel(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Palette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextMuted,
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
            }
            InfoBadge(title = title, explanation = explanation)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/**
 * График ряда с чтением значения пальцем: тап или удержание с протяжкой
 * ставит вертикаль и показывает величину под пальцем.
 *
 * @param valueOffset прибавка к отображаемому значению — для рядов, сдвинутых
 *   в положительную область (ускорение)
 */
@Composable
fun InteractiveSeriesChart(
    values: List<Double?>,
    color: Color,
    unit: String,
    modifier: Modifier = Modifier,
    maxOverride: Double? = null,
    valueOffset: Double = 0.0,
) {
    val present = remember(values) { values.filterNotNull() }
    val maxValue = maxOverride ?: present.maxOrNull()?.takeIf { it > 0 } ?: return
    var marker by remember { mutableStateOf<Int?>(null) }

    Box(modifier.fillMaxWidth().heightIn(min = 90.dp)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(values.size) {
                    detectTapGestures { offset ->
                        marker = indexAt(offset.x, size.width, values.size)
                    }
                }
                .pointerInput(values.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { marker = indexAt(it.x, size.width, values.size) },
                        onDragEnd = { },
                        onDragCancel = { marker = null },
                        onDrag = { change, _ ->
                            marker = indexAt(change.position.x, size.width, values.size)
                        },
                    )
                },
        ) {
            val stepX = size.width / (values.size - 1).coerceAtLeast(1)
            val path = Path()
            var started = false
            values.forEachIndexed { index, value ->
                if (value == null) {
                    // Разрыв не соединяем прямой: иначе график врёт о том, чего не мерили
                    started = false
                    return@forEachIndexed
                }
                val x = index * stepX
                val y = size.height - (value / maxValue).toFloat().coerceIn(0f, 1f) * size.height
                if (started) path.lineTo(x, y) else path.moveTo(x, y)
                started = true
            }
            drawPath(path, color, style = Stroke(width = 3f))
            drawLine(
                color = Palette.Outline,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 2f,
            )

            marker?.let { index ->
                val x = index * stepX
                drawLine(
                    color = Palette.TextSecondary,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f,
                )
                values.getOrNull(index)?.let { value ->
                    val y = size.height -
                        (value / maxValue).toFloat().coerceIn(0f, 1f) * size.height
                    drawCircle(color, radius = 5f, center = Offset(x, y))
                }
            }
        }

        marker?.let { index ->
            val value = values.getOrNull(index)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.SurfaceHigh)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (value == null) {
                        "нет данных"
                    } else {
                        "${Fmt.number(value + valueOffset, 2)} $unit"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                )
            }
        }
    }
}

private fun indexAt(x: Float, width: Int, count: Int): Int? {
    if (count <= 0 || width <= 0) return null
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * (count - 1)).toInt()
}

/** Гистограмма: тап по столбику показывает диапазон и число замеров. */
@Composable
fun HistogramChart(
    bins: List<HistogramBin>,
    color: Color,
    unit: String,
    modifier: Modifier = Modifier,
) {
    if (bins.isEmpty()) return
    val maxCount = bins.maxOf { it.count }.coerceAtLeast(1)
    var selected by remember(bins) { mutableStateOf<Int?>(null) }

    Column {
        Canvas(
            modifier.fillMaxWidth().height(150.dp).pointerInput(bins.size) {
                detectTapGestures { offset ->
                    val index = (offset.x / size.width * bins.size).toInt()
                    selected = index.coerceIn(0, bins.size - 1)
                }
            },
        ) {
            val slot = size.width / bins.size
            val gap = (slot * 0.15f).coerceAtMost(4f)
            bins.forEachIndexed { index, bin ->
                val height = size.height * bin.count / maxCount
                drawRect(
                    color = if (index == selected) Palette.TextPrimary else color,
                    topLeft = Offset(index * slot + gap / 2, size.height - height),
                    size = Size(slot - gap, height),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${Fmt.number(bins.first().from, 1)} $unit",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
            )
            selected?.let { index ->
                val bin = bins[index]
                Text(
                    text = "${Fmt.number(bin.from, 1)}–${Fmt.number(bin.to, 1)}: " +
                        "${bin.count} замеров",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextPrimary,
                )
            }
            Text(
                text = "${Fmt.number(bins.last().to, 1)} $unit",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
            )
        }
    }
}

/** Кривая расхода по скорости: медиана линией, коридор p25–p75 заливкой. */
@Composable
fun SpeedBinChart(
    bins: List<SpeedBinStats>,
    lineColor: Color,
    bandColor: Color,
    modifier: Modifier = Modifier,
) {
    if (bins.size < 2) return
    val maxValue = bins.maxOf { it.p75 }.coerceAtLeast(0.1)
    var selected by remember(bins) { mutableStateOf<Int?>(null) }

    Column {
        Canvas(
            modifier.fillMaxWidth().height(170.dp).pointerInput(bins.size) {
                detectTapGestures { offset ->
                    val index = (offset.x / size.width * (bins.size - 1)).toInt()
                    selected = index.coerceIn(0, bins.size - 1)
                }
            },
        ) {
            val stepX = size.width / (bins.size - 1)
            fun y(value: Double) =
                size.height - (value / maxValue).toFloat().coerceIn(0f, 1f) * size.height

            val band = Path()
            bins.forEachIndexed { index, bin ->
                val x = index * stepX
                if (index == 0) band.moveTo(x, y(bin.p75)) else band.lineTo(x, y(bin.p75))
            }
            bins.reversed().forEachIndexed { reverseIndex, bin ->
                band.lineTo((bins.size - 1 - reverseIndex) * stepX, y(bin.p25))
            }
            band.close()
            drawPath(band, bandColor)

            val line = Path()
            bins.forEachIndexed { index, bin ->
                val x = index * stepX
                if (index == 0) line.moveTo(x, y(bin.median)) else line.lineTo(x, y(bin.median))
            }
            drawPath(line, lineColor, style = Stroke(width = 4f))

            selected?.let { index ->
                val x = index * stepX
                drawLine(Palette.TextSecondary, Offset(x, 0f), Offset(x, size.height), 2f)
                drawCircle(lineColor, radius = 6f, center = Offset(x, y(bins[index].median)))
            }
        }
        selected?.let { index ->
            val bin = bins[index]
            Text(
                text = "${bin.speedFrom.toInt()}–${bin.speedTo.toInt()} км/ч · " +
                    "медиана ${Fmt.litersPer100(bin.median)} · " +
                    "p25 ${Fmt.litersPer100(bin.p25)} · p75 ${Fmt.litersPer100(bin.p75)} · " +
                    "${bin.count} замеров",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextPrimary,
            )
        }
    }
}

/** Несколько кривых на одном поле — сравнение периодов. */
@Composable
fun MultiSpeedBinChart(
    series: List<Pair<String, List<SpeedBinStats>>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val drawable = series.filter { it.second.size >= 2 }
    if (drawable.isEmpty()) return

    val minSpeed = drawable.minOf { pair -> pair.second.minOf { it.speedFrom } }
    val maxSpeed = drawable.maxOf { pair -> pair.second.maxOf { it.speedTo } }
    val maxValue = drawable.maxOf { pair -> pair.second.maxOf { it.median } } * 1.15

    Column {
        Canvas(modifier.fillMaxWidth().height(180.dp)) {
            fun x(speed: Double) =
                ((speed - minSpeed) / (maxSpeed - minSpeed)).toFloat() * size.width

            fun y(value: Double) =
                size.height - (value / maxValue).toFloat().coerceIn(0f, 1f) * size.height

            drawLine(
                Palette.Outline,
                Offset(0f, size.height),
                Offset(size.width, size.height),
                2f,
            )
            drawable.forEachIndexed { index, (_, bins) ->
                val path = Path()
                bins.forEachIndexed { pointIndex, bin ->
                    val cx = x((bin.speedFrom + bin.speedTo) / 2)
                    val cy = y(bin.median)
                    if (pointIndex == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
                }
                drawPath(path, colors[index % colors.size], style = Stroke(width = 3f))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            drawable.forEachIndexed { index, (label, _) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .height(3.dp)
                            .padding(end = 4.dp)
                            .background(colors[index % colors.size])
                            .fillMaxWidth(0f),
                    )
                    StatusDot(colors[index % colors.size])
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextSecondary,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${minSpeed.toInt()} км/ч",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
            )
            Text(
                "до ${Fmt.litersPer100(maxValue)} л/100",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
            )
            Text(
                "${maxSpeed.toInt()} км/ч",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
            )
        }
    }
}

/** Легенда градиента: плавная полоса с подписями по краям и в середине. */
@Composable
fun GradientLegend(
    mode: TrackPalette.Mode,
    speedThresholds: List<Double>,
    modifier: Modifier = Modifier,
) {
    val stops = remember(mode, speedThresholds) { TrackPalette.stops(mode, speedThresholds) }
    val colors = remember(stops) { stops.map { Color(it.color) } }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(colors)),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(stops.first(), stops[stops.size / 2], stops.last()).forEach { stop ->
                Text(
                    text = Fmt.number(stop.value, if (mode == TrackPalette.Mode.SPEED) 0 else 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextMuted,
                )
            }
        }
    }
}

/** Подпись единиц измерения для режима раскраски. */
fun unitOf(mode: TrackPalette.Mode): String = when (mode) {
    TrackPalette.Mode.SPEED -> "км/ч"
    TrackPalette.Mode.CONSUMPTION -> "л/100 км"
    TrackPalette.Mode.ACCELERATION -> "м/с²"
}
