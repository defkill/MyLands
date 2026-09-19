package com.example.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To2_preservesExistingRoutesAndCreatesCrossRefs() {
        // Create database with version 1 schema
        helper.createDatabase(TEST_DB, 1).apply {
            // Insert waypoints
            execSQL("INSERT INTO waypoints (id, name, description, latitude, longitude, altitudeMeters, timestamp, colorArgb, groupName, isSelectedInRoute) VALUES (1, 'Point 1', '', 50.0, 30.0, 100.0, 1000, -1, 'Default', 0)")
            execSQL("INSERT INTO waypoints (id, name, description, latitude, longitude, altitudeMeters, timestamp, colorArgb, groupName, isSelectedInRoute) VALUES (2, 'Point 2', '', 50.1, 30.1, 110.0, 2000, -1, 'Default', 0)")
            execSQL("INSERT INTO waypoints (id, name, description, latitude, longitude, altitudeMeters, timestamp, colorArgb, groupName, isSelectedInRoute) VALUES (3, 'Point 3', '', 50.2, 30.2, 120.0, 3000, -1, 'Default', 0)")

            // Insert route with CSV waypoints
            execSQL("INSERT INTO routes (id, name, waypointIdsCsv, totalDistanceMeters, createdAt) VALUES (1, 'Tactical Alpha', '1,2,3', 15000.0, 1700000000)")
            close()
        }

        // Run migration to version 2 and validate schema
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)

        // Verify route_waypoints table was created and populated with orderIndex
        val cursor = db.query("SELECT routeId, waypointId, orderIndex FROM route_waypoints WHERE routeId = 1 ORDER BY orderIndex ASC")
        cursor.use { c ->
            assertEquals(3, c.count)

            assertTrue(c.moveToNext())
            assertEquals(1L, c.getLong(0))
            assertEquals(1L, c.getLong(1))
            assertEquals(0, c.getInt(2))

            assertTrue(c.moveToNext())
            assertEquals(1L, c.getLong(0))
            assertEquals(2L, c.getLong(1))
            assertEquals(1, c.getInt(2))

            assertTrue(c.moveToNext())
            assertEquals(1L, c.getLong(0))
            assertEquals(3L, c.getLong(1))
            assertEquals(2, c.getInt(2))
        }

        // Verify the original route record and CSV is still preserved for compatibility
        val routeCursor = db.query("SELECT name, waypointIdsCsv FROM routes WHERE id = 1")
        routeCursor.use { rc ->
            assertTrue(rc.moveToFirst())
            assertEquals("Tactical Alpha", rc.getString(0))
            assertEquals("1,2,3", rc.getString(1))
        }
    }
}
