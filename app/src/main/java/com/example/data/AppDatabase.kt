package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.RouteDao
import com.example.data.dao.TrackDao
import com.example.data.dao.WaypointDao
import com.example.data.entity.RouteEntity
import com.example.data.entity.TrackEntity
import com.example.data.entity.TrackPointEntity
import com.example.data.entity.WaypointEntity

@Database(
    entities = [
        WaypointEntity::class,
        TrackEntity::class,
        TrackPointEntity::class,
        RouteEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun waypointDao(): WaypointDao
    abstract fun trackDao(): TrackDao
    abstract fun routeDao(): RouteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration definitions for future schema versions to prevent accidental data loss.
         * Example:
         * val MIGRATION_1_2 = object : Migration(1, 2) {
         *     override fun migrate(db: SupportSQLiteDatabase) {
         *         // Execute SQL alter table / create table statements here
         *     }
         * }
         */
        private val ALL_MIGRATIONS = arrayOf<Migration>(
            // Add future migrations here: MIGRATION_1_2, MIGRATION_2_3, etc.
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "orientir_database.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
