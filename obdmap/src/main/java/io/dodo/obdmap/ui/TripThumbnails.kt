package io.dodo.obdmap.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import io.dodo.obdmap.analysis.TrackPalette
import io.dodo.obdmap.data.PointEntity
import io.dodo.obdmap.util.Logger
import java.io.File

/**
 * Миниатюры маршрутов для списка поездок.
 *
 * Рисуем сам трек, без тайлов карты: картинка нужна мгновенно и в офлайне, а
 * форма маршрута узнаётся и без улиц. Готовый PNG кладём на диск и больше не
 * пересчитываем — разворачивать тысячи точек на каждую прокрутку списка нельзя.
 */
object TripThumbnails {

    const val WIDTH = 360
    const val HEIGHT = 200
    private const val PADDING = 12f

    /** Фон миниатюры — та же приподнятая панель, что и в интерфейсе. */
    private val BACKGROUND = 0xFF1C242F.toInt()

    private fun dir(context: Context): File =
        File(context.cacheDir, "thumbs").apply { mkdirs() }

    /** Имя включает число точек: дописалась поездка — миниатюра перерисуется. */
    private fun file(context: Context, tripId: Long, pointCount: Int) =
        File(dir(context), "trip_${tripId}_$pointCount.png")

    /** Готовая миниатюра с диска или null, если её ещё не рисовали. */
    fun cached(context: Context, tripId: Long, pointCount: Int): Bitmap? {
        val file = file(context, tripId, pointCount)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    /**
     * Рисует и сохраняет миниатюру. Возвращает null, если рисовать нечего —
     * у поездки может не быть ни одной координаты.
     */
    fun render(context: Context, tripId: Long, points: List<PointEntity>): Bitmap? {
        val coordinates = points.filter { it.latitude != null && it.longitude != null }
        if (coordinates.size < 2) return null

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        val minLat = coordinates.minOf { it.latitude!! }
        val maxLat = coordinates.maxOf { it.latitude!! }
        val minLon = coordinates.minOf { it.longitude!! }
        val maxLon = coordinates.maxOf { it.longitude!! }

        // Долгота на широте 55° «короче» широты — иначе город выглядит растянутым
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-6)
        val lonSpan = (maxLon - minLon).coerceAtLeast(1e-6) *
            Math.cos(Math.toRadians((minLat + maxLat) / 2))
        val scale = minOf(
            (WIDTH - 2 * PADDING) / lonSpan.toFloat(),
            (HEIGHT - 2 * PADDING) / latSpan.toFloat(),
        )
        val offsetX = (WIDTH - lonSpan.toFloat() * scale) / 2
        val offsetY = (HEIGHT - latSpan.toFloat() * scale) / 2

        fun x(longitude: Double) =
            offsetX + ((longitude - minLon) * Math.cos(Math.toRadians((minLat + maxLat) / 2)) *
                scale).toFloat()

        // Широта растёт вверх, экранный Y — вниз
        fun y(latitude: Double) = HEIGHT - offsetY - ((latitude - minLat) * scale).toFloat()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        }

        for (index in 1 until coordinates.size) {
            val from = coordinates[index - 1]
            val to = coordinates[index]
            paint.color = TrackPalette.quantizedGradientColor(
                TrackPalette.Mode.SPEED,
                to.speedKmh,
            )
            canvas.drawLine(
                x(from.longitude!!),
                y(from.latitude!!),
                x(to.longitude!!),
                y(to.latitude!!),
                paint,
            )
        }

        runCatching {
            file(context, tripId, points.size).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, it)
            }
        }.onFailure { Logger.error("не сохранил миниатюру поездки $tripId", it) }

        return bitmap
    }

    /** Чистка при удалении истории. */
    fun clear(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }
}
