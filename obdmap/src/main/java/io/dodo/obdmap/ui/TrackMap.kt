package io.dodo.obdmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.dodo.obdmap.R
import io.dodo.obdmap.analysis.TrackPalette
import io.dodo.obdmap.util.Geo
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Точка трека в терминах карты — чтобы не тащить сюда модели записи и базы. */
data class MapPoint(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double? = null,
    val litersPer100Km: Double? = null,
    val accelerationMs2: Double? = null,
) {
    fun value(mode: TrackPalette.Mode): Double? = when (mode) {
        TrackPalette.Mode.SPEED -> speedKmh
        TrackPalette.Mode.CONSUMPTION -> litersPer100Km
        TrackPalette.Mode.ACCELERATION -> accelerationMs2
    }
}

/**
 * Карта поездки на OpenStreetMap.
 *
 * osmdroid — обычный View, поэтому оборачиваем в AndroidView. Ключей API не
 * требует, тайлы тянет сам и кеширует на диск (настройка кеша — в ObdApp).
 *
 * Трек красится по полосам: подряд идущие точки одного цвета собираются в одну
 * ломаную. Рисовать по отрезку на точку нельзя — на длинной поездке это десятки
 * тысяч оверлеев, карта встанет.
 *
 * @param followLast держать камеру на последней точке — режим живой поездки
 * @param fitAll вписать весь трек в экран — режим просмотра истории
 * @param showVehicle рисовать машинку в текущей точке, развёрнутую по курсу
 */
@Composable
fun TrackMap(
    points: List<MapPoint>,
    mode: TrackPalette.Mode,
    modifier: Modifier = Modifier,
    speedThresholds: List<Double> = TrackPalette.DEFAULT_SPEED_THRESHOLDS,
    followLast: Boolean = false,
    fitAll: Boolean = false,
    showVehicle: Boolean = false,
) {
    val context = LocalContext.current
    val carIcon = remember { ContextCompat.getDrawable(context, R.drawable.ic_car_top) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(15.0)
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    // Длинный трек прореживаем: на экране всё равно не различить соседние точки,
    // а перерисовка живой карты идёт несколько раз в секунду.
    val drawn = remember(points, mode) { decimate(points, MAX_DRAWN_POINTS) }

    AndroidView(
        // Без clipToBounds osmdroid рисует тайлы за пределами отведённой области
        // и налезает на соседние элементы экрана.
        modifier = modifier.clipToBounds(),
        factory = { mapView },
        update = { view ->
            view.overlays.clear()

            if (drawn.isNotEmpty()) {
                val geoPoints = drawn.map { GeoPoint(it.latitude, it.longitude) }

                segments(drawn, mode, speedThresholds).forEach { segment ->
                    view.overlays.add(
                        Polyline(view).apply {
                            setPoints(segment.indices.map { geoPoints[it] })
                            outlinePaint.color = segment.color
                            outlinePaint.strokeWidth = 10f
                        },
                    )
                }

                if (geoPoints.size > 1) {
                    view.overlays.add(
                        Marker(view).apply {
                            position = geoPoints.first()
                            title = "Старт"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        },
                    )
                }

                if (showVehicle && carIcon != null) {
                    view.overlays.add(
                        Marker(view).apply {
                            position = geoPoints.last()
                            icon = carIcon
                            title = "Машина"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            // «Плоский» маркер живёт в системе координат карты;
                            // osmdroid поворачивает холст на -rotation, а иконка
                            // нарисована носом на север, поэтому знак обратный.
                            setFlat(true)
                            rotation = -(bearingOf(drawn)?.toFloat() ?: 0f)
                        },
                    )
                }

                when {
                    fitAll && geoPoints.size > 1 -> {
                        // zoomToBoundingBox до первой отрисовки молча ничего не делает,
                        // поэтому откладываем до момента, когда у view есть размеры.
                        val box = BoundingBox.fromGeoPointsSafe(geoPoints)
                        view.post { view.zoomToBoundingBox(box.increaseByScale(1.2f), false) }
                    }

                    followLast -> view.controller.animateTo(geoPoints.last())
                }
            }
            view.invalidate()
        },
    )
}

/** Сколько точек максимум рисуем: дальше глазу всё равно, а карте тяжело. */
private const val MAX_DRAWN_POINTS = 2_000

/**
 * По какому отрезку пути считаем курс. По двум последним точкам нельзя:
 * на стоянке шум GPS крутил бы машинку вокруг своей оси.
 */
private const val BEARING_DISTANCE_M = 12.0

/**
 * Курс в последней точке: ищем назад ближайшую точку, отстоящую хотя бы на
 * [BEARING_DISTANCE_M]. Если такой нет — стоим, и разворачивать нечего.
 */
private fun bearingOf(points: List<MapPoint>): Double? {
    if (points.size < 2) return null
    val last = points.last()
    for (index in points.size - 2 downTo 0) {
        val candidate = points[index]
        val distance = Geo.distanceMeters(
            candidate.latitude,
            candidate.longitude,
            last.latitude,
            last.longitude,
        )
        if (distance >= BEARING_DISTANCE_M) {
            return Geo.bearingDegrees(
                candidate.latitude,
                candidate.longitude,
                last.latitude,
                last.longitude,
            )
        }
    }
    return null
}

/** Отрезок трека одного цвета: индексы точек в прореженном списке. */
private data class Segment(val indices: List<Int>, val color: Int)

/**
 * Собирает подряд идущие точки одного цвета в отрезки. Соседние отрезки
 * делят общую точку, иначе на стыке будет разрыв.
 */
private fun segments(
    points: List<MapPoint>,
    mode: TrackPalette.Mode,
    speedThresholds: List<Double>,
): List<Segment> {
    if (points.size < 2) return emptyList()
    val result = mutableListOf<Segment>()
    var currentColor = TrackPalette.colorOf(mode, points[0].value(mode), speedThresholds)
    var currentIndices = mutableListOf(0)

    for (index in 1 until points.size) {
        val color = TrackPalette.colorOf(mode, points[index].value(mode), speedThresholds)
        currentIndices.add(index)
        if (color != currentColor) {
            result += Segment(currentIndices, currentColor)
            currentIndices = mutableListOf(index)
            currentColor = color
        }
    }
    if (currentIndices.size > 1) result += Segment(currentIndices, currentColor)
    return result
}

/** Равномерно прореживает список, всегда сохраняя первую и последнюю точку. */
private fun decimate(points: List<MapPoint>, limit: Int): List<MapPoint> {
    if (points.size <= limit) return points
    val step = points.size.toDouble() / limit
    val result = ArrayList<MapPoint>(limit + 1)
    var position = 0.0
    while (position < points.size) {
        result += points[position.toInt()]
        position += step
    }
    if (result.last() !== points.last()) result += points.last()
    return result
}
