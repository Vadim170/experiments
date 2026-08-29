package io.dodo.obdmap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TripEntity::class, PointEntity::class], version = 1, exportSchema = false)
abstract class TripDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao

    companion object {
        @Volatile
        private var instance: TripDatabase? = null

        fun get(context: Context): TripDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TripDatabase::class.java,
                "trips.db",
            ).build().also { instance = it }
        }
    }
}
