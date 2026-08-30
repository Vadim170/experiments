package io.dodo.obdmap.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Узкая проекция точки для экрана анализа. */
data class DriveRow(
    val speedKmh: Double,
    val litersPer100Km: Double,
    val accelerationMs2: Double?,
    val timeMs: Long,
)

@Dao
interface TripDao {

    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Insert
    suspend fun insertPoints(points: List<PointEntity>)

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun trip(tripId: Long): TripEntity?

    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY timeMs ASC")
    suspend fun points(tripId: Long): List<PointEntity>

    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY timeMs ASC")
    fun observePoints(tripId: Long): Flow<List<PointEntity>>

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: Long)

    // --- Архив ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchive(archive: ArchiveEntity)

    @Query("SELECT * FROM archives WHERE tripId = :tripId")
    suspend fun archive(tripId: Long): ArchiveEntity?

    @Query("DELETE FROM points WHERE tripId = :tripId")
    suspend fun deletePoints(tripId: Long)

    @Query("SELECT COUNT(*) FROM points WHERE tripId = :tripId")
    suspend fun pointCount(tripId: Long): Int

    /** Поездки от старых к новым — в этом порядке и архивируем. */
    @Query("SELECT id FROM trips WHERE id NOT IN (SELECT tripId FROM archives) ORDER BY startedAt ASC")
    suspend fun unarchivedTripIds(): List<Long>

    @Query("SELECT COUNT(*) FROM archives")
    suspend fun archivedCount(): Int

    @Query("SELECT COUNT(*) FROM points")
    suspend fun totalPoints(): Int

    /** Поездки без единой точки только засоряют список — чистим при старте. */
    @Query("DELETE FROM trips WHERE id NOT IN (SELECT DISTINCT tripId FROM points)")
    suspend fun deleteEmptyTrips()

    /**
     * Замеры для анализа расхода: только те, где есть и скорость, и расход.
     * Тянем узкую проекцию — точек за месяц езды набирается очень много.
     *
     * @param tripId null — по всей истории, иначе по одной поездке
     */
    @Query(
        """
        SELECT speedKmh AS speedKmh,
               litersPer100Km AS litersPer100Km,
               accelerationMs2 AS accelerationMs2,
               timeMs AS timeMs
        FROM points
        WHERE speedKmh IS NOT NULL
          AND litersPer100Km IS NOT NULL
          AND (:tripId IS NULL OR tripId = :tripId)
        """,
    )
    suspend fun driveRows(tripId: Long?): List<DriveRow>
}
