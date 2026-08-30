package io.dodo.obdmap.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Поездка целиком. Итоги дублируются в строке, чтобы список не пересчитывал треки. */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val distanceMeters: Double = 0.0,
    val fuelLiters: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val movingMillis: Long = 0,
    val idleMillis: Long = 0,
    /** Источник расхода на момент поездки — полезно при разборе странных цифр. */
    val fuelSource: String = "",
)

/**
 * Архив трека: точки старой поездки, упакованные в один сжатый блоб.
 *
 * Когда история упирается в лимит размера, точки самых старых поездок
 * переезжают сюда, а строки из `points` удаляются. Сама поездка остаётся в
 * списке со всеми итогами — пользователь разницы не видит, кроме того, что
 * трек разворачивается чуть медленнее.
 */
@Entity(
    tableName = "archives",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArchiveEntity(
    @PrimaryKey val tripId: Long,
    val pointCount: Int,
    val originalCount: Int,
    val data: ByteArray,
) {
    // ByteArray в data-классе требует ручного сравнения: массивы сравниваются
    // по ссылке, и автоматический equals сломал бы кеши Room.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArchiveEntity) return false
        return tripId == other.tripId &&
            pointCount == other.pointCount &&
            originalCount == other.originalCount &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = tripId.hashCode()
        result = 31 * result + pointCount
        result = 31 * result + originalCount
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/** Точка трека. Координаты могут отсутствовать: в туннеле GPS молчит, а данные с шины идут. */
@Entity(
    tableName = "points",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tripId")],
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timeMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    val fuelRateLitersPerHour: Double? = null,
    val litersPer100Km: Double? = null,
    /** Ускорение в этот момент, м/с². Считается по окну замеров скорости. */
    val accelerationMs2: Double? = null,
)
