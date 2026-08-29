package io.dodo.obdmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Точка трека в терминах карты — чтобы не тащить сюда модели записи и базы. */
data class MapPoint(val latitude: Double, val longitude: Double)

/**
 * Карта поездки на OpenStreetMap.
 *
 * osmdroid — обычный View, поэтому оборачиваем в AndroidView. Ключей API он не
 * требует, тайлы тянет сам и кеширует на диск.
 *
 * @param followLast держать камеру на последней точке — режим живой поездки
 * @param fitAll вписать весь трек в экран — режим просмотра истории
 */
@Composable
fun TrackMap(
    points: List<MapPoint>,
    modifier: Modifier = Modifier,
    followLast: Boolean = false,
    fitAll: Boolean = false,
) {
    val context = LocalContext.current
    val trackColor = MaterialTheme.colorScheme.primary.toArgb()

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            view.overlays.clear()

            if (points.isNotEmpty()) {
                val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }

                view.overlays.add(
                    Polyline(view).apply {
                        setPoints(geoPoints)
                        outlinePaint.color = trackColor
                        outlinePaint.strokeWidth = 10f
                    },
                )

                if (geoPoints.size > 1) {
                    view.overlays.add(
                        Marker(view).apply {
                            position = geoPoints.first()
                            title = "Старт"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
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
