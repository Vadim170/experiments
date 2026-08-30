package io.dodo.obdmap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TripEntity::class, PointEntity::class], version = 2, exportSchema = false)
abstract class TripDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao

    companion object {

        /**
         * Ускорение появилось во второй версии. Мигрируем, а не сносим базу:
         * ради одной колонки терять историю поездок незачем. У старых точек
         * ускорение останется null — это честно, тогда его не считали.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE points ADD COLUMN accelerationMs2 REAL")
            }
        }

        @Volatile
        private var instance: TripDatabase? = null

        fun get(context: Context): TripDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TripDatabase::class.java,
                "trips.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
