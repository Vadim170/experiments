package io.dodo.obdmap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TripEntity::class, PointEntity::class, ArchiveEntity::class],
    version = 3,
    exportSchema = false,
)
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

        /** Архив старых треков появился в третьей версии. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS archives (
                        tripId INTEGER NOT NULL PRIMARY KEY,
                        pointCount INTEGER NOT NULL,
                        originalCount INTEGER NOT NULL,
                        data BLOB NOT NULL,
                        FOREIGN KEY(tripId) REFERENCES trips(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        @Volatile
        private var instance: TripDatabase? = null

        fun get(context: Context): TripDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TripDatabase::class.java,
                "trips.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
