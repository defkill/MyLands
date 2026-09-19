package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.RouteDao
import com.example.data.dao.RouteWaypointDao
import com.example.data.dao.TrackDao
import com.example.data.dao.WaypointDao
import com.example.data.entity.RouteEntity
import com.example.data.entity.RouteWaypointCrossRef
import com.example.data.entity.TrackEntity
import com.example.data.entity.TrackPointEntity
import com.example.data.entity.WaypointEntity

@Database(
    entities = [
        WaypointEntity::class,
        TrackEntity::class,
        TrackPointEntity::class,
        RouteEntity::class,
        RouteWaypointCrossRef::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun waypointDao(): WaypointDao
    abstract fun trackDao(): TrackDao
    abstract fun routeDao(): RouteDao
    abstract fun routeWaypointDao(): RouteWaypointDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create table route_waypoints with composite primary key and foreign keys
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `route_waypoints` (
                        `routeId` INTEGER NOT NULL,
                        `waypointId` INTEGER NOT NULL,
                        `orderIndex` INTEGER NOT NULL,
                        PRIMARY KEY(`routeId`, `orderIndex`),
                        FOREIGN KEY(`routeId`) REFERENCES `routes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`waypointId`) REFERENCES `waypoints`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                // 2. Create required indices
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_waypoints_routeId` ON `route_waypoints` (`routeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_waypoints_waypointId` ON `route_waypoints` (`waypointId`)")

                // 3. Migrate data from waypointIdsCsv in routes table
                val cursor = db.query("SELECT id, waypointIdsCsv FROM routes")
                cursor.use { c ->
                    val idCol = c.getColumnIndex("id")
                    val csvCol = c.getColumnIndex("waypointIdsCsv")
                    if (idCol >= 0 && csvCol >= 0) {
                        while (c.moveToNext()) {
                            val routeId = c.getLong(idCol)
                            val csv = c.getString(csvCol) ?: ""
                            if (csv.isNotBlank()) {
                                val waypointIds = csv.split(",").mapNotNull { it.trim().toLongOrNull() }
                                waypointIds.forEachIndexed { orderIndex, waypointId ->
                                    db.execSQL(
                                        "INSERT OR REPLACE INTO route_waypoints (routeId, waypointId, orderIndex) VALUES (?, ?, ?)",
                                        arrayOf<Any>(routeId, waypointId, orderIndex)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val ALL_MIGRATIONS = arrayOf<Migration>(
            MIGRATION_1_2
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
