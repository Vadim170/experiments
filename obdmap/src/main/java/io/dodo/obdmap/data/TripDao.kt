package io.dodo.obdmap.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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

    /** Поездки без единой точки только засоряют список — чистим при старте. */
    @Query("DELETE FROM trips WHERE id NOT IN (SELECT DISTINCT tripId FROM points)")
    suspend fun deleteEmptyTrips()
}
