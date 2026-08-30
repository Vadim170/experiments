package io.dodo.obdmap.data

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Упаковка трека в компактный блоб для архива.
 *
 * Формат — текстовые строки, сжатые gzip. Байты можно было бы ужать сильнее
 * двоичным кодированием, но текст читаем, легко чинится руками и сжимается
 * почти так же: соседние точки отличаются в последних знаках, и gzip это ест.
 *
 * Время пишется дельтой от начала поездки, координаты — с шестью знаками
 * (≈0.1 м, точнее GPS всё равно не бывает).
 */
object TrackCodec {

    private const val HEADER = "obdtrack1"

    fun encode(points: List<PointEntity>): ByteArray {
        val text = buildString {
            append(HEADER).append('\n')
            val base = points.firstOrNull()?.timeMs ?: 0L
            append(base).append('\n')
            points.forEach { point ->
                append(point.timeMs - base).append(',')
                append(num(point.latitude, 6)).append(',')
                append(num(point.longitude, 6)).append(',')
                append(num(point.speedKmh, 1)).append(',')
                append(num(point.rpm, 0)).append(',')
                append(num(point.fuelRateLitersPerHour, 3)).append(',')
                append(num(point.litersPer100Km, 2)).append(',')
                append(num(point.accelerationMs2, 3)).append('\n')
            }
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** Битый архив — не повод падать: вернём пустой трек, поездка останется. */
    fun decode(tripId: Long, blob: ByteArray): List<PointEntity> {
        val text = runCatching {
            GZIPInputStream(blob.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return emptyList()
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.size < 2 || lines[0] != HEADER) return emptyList()
        val base = lines[1].toLongOrNull() ?: return emptyList()

        return lines.drop(2).mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size < 8) return@mapNotNull null
            val offset = parts[0].toLongOrNull() ?: return@mapNotNull null
            PointEntity(
                tripId = tripId,
                timeMs = base + offset,
                latitude = parts[1].toDoubleOrNull(),
                longitude = parts[2].toDoubleOrNull(),
                speedKmh = parts[3].toDoubleOrNull(),
                rpm = parts[4].toDoubleOrNull(),
                fuelRateLitersPerHour = parts[5].toDoubleOrNull(),
                litersPer100Km = parts[6].toDoubleOrNull(),
                accelerationMs2 = parts[7].toDoubleOrNull(),
            )
        }
    }

    /** Пустая строка вместо null: короче и однозначно. */
    private fun num(value: Double?, decimals: Int): String {
        if (value == null) return ""
        return if (decimals == 0) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.${decimals}f", value)
        }
    }
}
