package io.dodo.obdmap.data

import android.content.Context
import io.dodo.obdmap.util.Logger
import io.dodo.obdmap.util.Prefs
import java.io.File

/**
 * Хранение истории в пределах заданного объёма.
 *
 * Когда база перерастает лимит, точки самых старых поездок упаковываются в
 * сжатый архив и удаляются из таблицы. Поездка остаётся в списке со всеми
 * итогами, трек разворачивается из архива при открытии. Ничего не пропадает —
 * до тех пор, пока места хватает даже под архивы.
 */
object HistoryStore {

    /** Во сколько раз архив плотнее исходных строк — по замерам около 12. */
    private const val ARCHIVE_RATIO = 12

    /** Точек в архиве оставляем не больше: секундный трек и так избыточен. */
    private const val ARCHIVE_MAX_POINTS = 4_000

    /** Сколько байтов занимает одна точка в базе вместе с индексом. */
    const val BYTES_PER_POINT = 110L

    /** Средний темп записи точек, шт/с. Опрос ~3 Гц. */
    const val POINTS_PER_SECOND = 3.0

    /** Файлы базы: сам файл плюс журнал упреждающей записи. */
    fun databaseBytes(context: Context): Long {
        val db = context.getDatabasePath("trips.db")
        return listOf(db, File("${db.path}-wal"), File("${db.path}-shm"))
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    /** Сколько примерно часов записи помещается в лимит. */
    fun estimatedHours(limitBytes: Long): Double =
        limitBytes / BYTES_PER_POINT / POINTS_PER_SECOND / 3600.0

    /**
     * Ужимает историю до лимита. Возвращает число заархивированных поездок.
     *
     * Идём от самых старых: свежие поездки нужны чаще, и разворачивать их из
     * архива на каждом открытии было бы обидно.
     */
    suspend fun enforceLimit(context: Context): Int {
        val dao = TripDatabase.get(context).tripDao()
        val limit = Prefs.maxStorageBytes(context)
        if (databaseBytes(context) <= limit) return 0

        var archived = 0
        val candidates = dao.unarchivedTripIds()
        for (tripId in candidates) {
            if (databaseBytes(context) <= limit) break
            if (archiveTrip(dao, tripId)) archived++
        }
        if (archived > 0) {
            Logger.log("история ужата: заархивировано поездок $archived")
        }
        return archived
    }

    /** @return true, если поездка действительно уехала в архив. */
    private suspend fun archiveTrip(dao: TripDao, tripId: Long): Boolean {
        val points = dao.points(tripId)
        if (points.isEmpty()) return false

        val thinned = thin(points, ARCHIVE_MAX_POINTS)
        val blob = TrackCodec.encode(thinned)
        dao.insertArchive(
            ArchiveEntity(
                tripId = tripId,
                pointCount = thinned.size,
                originalCount = points.size,
                data = blob,
            ),
        )
        dao.deletePoints(tripId)
        return true
    }

    /** Точки поездки: из таблицы, а если она уже в архиве — из архива. */
    suspend fun points(context: Context, tripId: Long): List<PointEntity> {
        val dao = TripDatabase.get(context).tripDao()
        val live = dao.points(tripId)
        if (live.isNotEmpty()) return live
        val archive = dao.archive(tripId) ?: return emptyList()
        return runCatching { TrackCodec.decode(tripId, archive.data) }
            .onFailure { Logger.error("не смог развернуть архив поездки $tripId", it) }
            .getOrDefault(emptyList())
    }

    /** Равномерно прореживает, сохраняя первую и последнюю точку. */
    fun thin(points: List<PointEntity>, limit: Int): List<PointEntity> {
        if (points.size <= limit) return points
        val step = points.size.toDouble() / limit
        val result = ArrayList<PointEntity>(limit + 1)
        var position = 0.0
        while (position < points.size) {
            result += points[position.toInt()]
            position += step
        }
        if (result.last() !== points.last()) result += points.last()
        return result
    }

    /** Насколько плотнее архив исходных строк — для подписи в настройках. */
    fun archiveRatio(): Int = ARCHIVE_RATIO
}
