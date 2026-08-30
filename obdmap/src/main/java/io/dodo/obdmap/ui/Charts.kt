package io.dodo.obdmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import io.dodo.obdmap.analysis.HistogramBin
import io.dodo.obdmap.analysis.SpeedBinStats
import io.dodo.obdmap.analysis.TrackPalette

/**
 * График ряда значений во времени. Без библиотек: обычный Canvas.
 * Разрывы (null) не соединяются прямой — иначе график врёт о том, чего не мерили.
 */
@Composable
fun SeriesChart(
    values: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(120.dp),
    maxOverride: Double? = null,
) {
    val present = remember(values) { values.filterNotNull() }
    val maxValue = maxOverride ?: present.maxOrNull()?.takeIf { it > 0 } ?: return

    Canvas(modifier) {
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        var started = false
        values.forEachIndexed { index, value ->
            if (value == null) {
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
            color = color.copy(alpha = 0.25f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 2f,
        )
    }
}

/** Гистограмма распределения: столбики с подписями крайних значений. */
@Composable
fun HistogramChart(
    bins: List<HistogramBin>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(160.dp),
) {
    if (bins.isEmpty()) return
    val maxCount = bins.maxOf { it.count }.coerceAtLeast(1)

    Column {
        Canvas(modifier) {
            val slot = size.width / bins.size
            val gap = (slot * 0.15f).coerceAtMost(4f)
            bins.forEachIndexed { index, bin ->
                val height = size.height * bin.count / maxCount
                drawRect(
                    color = color,
                    topLeft = Offset(index * slot + gap / 2, size.height - height),
                    size = Size(slot - gap, height),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Fmt.litersPer100(bins.first().from), style = MaterialTheme.typography.labelSmall)
            Text(Fmt.litersPer100(bins.last().to), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Расход по диапазонам скорости: медиана линией, коридор p25–p75 заливкой.
 * Коридор важнее самой медианы — по нему видно, где разброс велик и цифре
 * верить нельзя.
 */
@Composable
fun SpeedBinChart(
    bins: List<SpeedBinStats>,
    lineColor: Color,
    bandColor: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(180.dp),
) {
    if (bins.size < 2) return
    val maxValue = bins.maxOf { it.p75 }.coerceAtLeast(0.1)

    Column {
        Canvas(modifier) {
            val stepX = size.width / (bins.size - 1)
            fun y(value: Double) =
                size.height - (value / maxValue).toFloat().coerceIn(0f, 1f) * size.height

            val band = Path()
            bins.forEachIndexed { index, bin ->
                val x = index * stepX
                if (index == 0) band.moveTo(x, y(bin.p75)) else band.lineTo(x, y(bin.p75))
            }
            bins.reversed().forEachIndexed { reverseIndex, bin ->
                val x = (bins.size - 1 - reverseIndex) * stepX
                band.lineTo(x, y(bin.p25))
            }
            band.close()
            drawPath(band, bandColor)

            val line = Path()
            bins.forEachIndexed { index, bin ->
                val x = index * stepX
                if (index == 0) line.moveTo(x, y(bin.median)) else line.lineTo(x, y(bin.median))
            }
            drawPath(line, lineColor, style = Stroke(width = 4f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${bins.first().speedFrom.toInt()} км/ч",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "до ${Fmt.litersPer100(maxValue)} л/100",
                style = MaterialTheme.typography.labelSmall,
            )
            Text("${bins.last().speedTo.toInt()} км/ч", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Легенда полос раскраски: квадратик цвета и подпись диапазона. */
@Composable
fun PaletteLegend(
    mode: TrackPalette.Mode,
    speedThresholds: List<Double>,
    modifier: Modifier = Modifier,
) {
    val bands = remember(mode, speedThresholds) { TrackPalette.bands(mode, speedThresholds) }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bands.forEach { band ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(band.color)),
                )
                Spacer(Modifier.size(4.dp))
                Text(band.label, style = MaterialTheme.typography.labelSmall)
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
