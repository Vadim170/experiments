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
)
