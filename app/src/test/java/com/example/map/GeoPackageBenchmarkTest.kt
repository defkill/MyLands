package com.example.map

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import com.example.map.vector.DoublePoint
import com.example.map.vector.GeoBoundingBox
import com.example.map.vector.GeoPackageIndexer
import com.example.map.vector.GeoPackageStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeoPackageBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = tempFolder.newFolder("gpkg_benchmark")
    }

    private fun createGpkgGeometryBlob(
        wkbType: Int,
        coordinates: List<List<DoublePoint>>,
        envelope: GeoBoundingBox? = null
    ): ByteArray {
        val buffer = ByteBuffer.allocate(coordinates.sumOf { it.size } * 16 + coordinates.size * 32 + 128).order(ByteOrder.LITTLE_ENDIAN)
        val envelopeIndicator = if (envelope != null) 1 else 0
        val flags = 1 or (envelopeIndicator shl 1)

        buffer.put(0x47.toByte())
        buffer.put(0x50.toByte())
        buffer.put(0.toByte())
        buffer.put(flags.toByte())
        buffer.putInt(4326)

        if (envelope != null) {
            buffer.putDouble(envelope.minX)
            buffer.putDouble(envelope.maxX)
            buffer.putDouble(envelope.minY)
            buffer.putDouble(envelope.maxY)
        }

        buffer.put(1.toByte())
        buffer.putInt(wkbType)

        when (wkbType) {
            2 -> { // LineString
                val line = coordinates.first()
                buffer.putInt(line.size)
                line.forEach { pt ->
                    buffer.putDouble(pt.lon)
                    buffer.putDouble(pt.lat)
                }
            }
            3 -> { // Polygon
                buffer.putInt(coordinates.size)
                coordinates.forEach { ring ->
                    buffer.putInt(ring.size)
                    ring.forEach { pt ->
                        buffer.putDouble(pt.lon)
                        buffer.putDouble(pt.lat)
                    }
                }
            }
            5 -> { // MultiLineString
                buffer.putInt(coordinates.size)
                coordinates.forEach { line ->
                    buffer.put(1.toByte())
                    buffer.putInt(2) // LineString
                    buffer.putInt(line.size)
                    line.forEach { pt ->
                        buffer.putDouble(pt.lon)
                        buffer.putDouble(pt.lat)
                    }
                }
            }
        }

        val bytes = ByteArray(buffer.position())
        System.arraycopy(buffer.array(), 0, bytes, 0, bytes.size)
        return bytes
    }

    @Test
    fun benchmark_IndexingAndTileRenderPerformance() = runBlocking {
        val gpkgFile = File(testDir, "dense_urban_sim.gpkg")
        val db = SQLiteDatabase.openOrCreateDatabase(gpkgFile, null)

        val totalRoads = 2000
        val totalBuildings = 3000
        val totalLanduse = 200

        try {
            db.execSQL("CREATE TABLE gpkg_contents (table_name TEXT, data_type TEXT, identifier TEXT)")
            db.execSQL("INSERT INTO gpkg_contents VALUES ('gis_osm_roads_free', 'features', 'roads')")
            db.execSQL("INSERT INTO gpkg_contents VALUES ('gis_osm_buildings_a_free', 'features', 'buildings')")
            db.execSQL("INSERT INTO gpkg_contents VALUES ('gis_osm_landuse_a_free', 'features', 'landuse')")

            db.execSQL("CREATE TABLE gpkg_geometry_columns (table_name TEXT, column_name TEXT, geometry_type_name TEXT, srs_id INTEGER, z INTEGER, m INTEGER)")
            db.execSQL("INSERT INTO gpkg_geometry_columns VALUES ('gis_osm_roads_free', 'geom', 'LINESTRING', 4326, 0, 0)")
            db.execSQL("INSERT INTO gpkg_geometry_columns VALUES ('gis_osm_buildings_a_free', 'geom', 'POLYGON', 4326, 0, 0)")
            db.execSQL("INSERT INTO gpkg_geometry_columns VALUES ('gis_osm_landuse_a_free', 'geom', 'POLYGON', 4326, 0, 0)")

            db.execSQL("CREATE TABLE gis_osm_roads_free (fid INTEGER PRIMARY KEY, geom BLOB, fclass TEXT, name TEXT)")
            db.execSQL("CREATE TABLE gis_osm_buildings_a_free (fid INTEGER PRIMARY KEY, geom BLOB, fclass TEXT, name TEXT)")
            db.execSQL("CREATE TABLE gis_osm_landuse_a_free (fid INTEGER PRIMARY KEY, geom BLOB, fclass TEXT, name TEXT)")

            db.beginTransaction()
            try {
                // Populate dense roads in 0.05 x 0.05 degree area
                for (i in 1..totalRoads) {
                    val baseLon = 1.50 + (i % 50) * 0.001
                    val baseLat = 42.50 + (i / 50) * 0.001
                    val roadBlob = createGpkgGeometryBlob(
                        2,
                        listOf(listOf(DoublePoint(baseLon, baseLat), DoublePoint(baseLon + 0.0008, baseLat + 0.0008))),
                        GeoBoundingBox(baseLon, baseLat, baseLon + 0.0008, baseLat + 0.0008)
                    )
                    val fclass = if (i % 10 == 0) "primary" else if (i % 5 == 0) "secondary" else "residential"
                    db.execSQL("INSERT INTO gis_osm_roads_free (fid, geom, fclass, name) VALUES (?, ?, ?, ?)", arrayOf(i, roadBlob, fclass, "Road $i"))
                }

                // Populate dense buildings
                for (i in 1..totalBuildings) {
                    val baseLon = 1.50 + (i % 60) * 0.0008
                    val baseLat = 42.50 + (i / 60) * 0.0008
                    val bldgBlob = createGpkgGeometryBlob(
                        3,
                        listOf(listOf(
                            DoublePoint(baseLon, baseLat),
                            DoublePoint(baseLon + 0.0004, baseLat),
                            DoublePoint(baseLon + 0.0004, baseLat + 0.0004),
                            DoublePoint(baseLon, baseLat + 0.0004),
                            DoublePoint(baseLon, baseLat)
                        )),
                        GeoBoundingBox(baseLon, baseLat, baseLon + 0.0004, baseLat + 0.0004)
                    )
                    db.execSQL("INSERT INTO gis_osm_buildings_a_free (fid, geom, fclass, name) VALUES (?, ?, 'building', 'Bldg $i')", arrayOf(i, bldgBlob))
                }

                // Populate landuse polygons
                for (i in 1..totalLanduse) {
                    val baseLon = 1.50 + (i % 10) * 0.005
                    val baseLat = 42.50 + (i / 10) * 0.005
                    val landBlob = createGpkgGeometryBlob(
                        3,
                        listOf(listOf(
                            DoublePoint(baseLon, baseLat),
                            DoublePoint(baseLon + 0.004, baseLat),
                            DoublePoint(baseLon + 0.004, baseLat + 0.004),
                            DoublePoint(baseLon, baseLat + 0.004),
                            DoublePoint(baseLon, baseLat)
                        )),
                        GeoBoundingBox(baseLon, baseLat, baseLon + 0.004, baseLat + 0.004)
                    )
                    db.execSQL("INSERT INTO gis_osm_landuse_a_free (fid, geom, fclass, name) VALUES (?, ?, 'residential', 'Quarter $i')", arrayOf(i, landBlob))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }

        // Measure Indexing Time
        val indexDurationNs = measureNanoTime {
            GeoPackageIndexer.buildSpatialIndex(gpkgFile)
        }
        val indexDurationMs = indexDurationNs / 1_000_000.0
        val indexFile = File(testDir, "${gpkgFile.nameWithoutExtension}.spatialindex")

        println("=== BENCHMARK: SPATIAL INDEXING ===")
        println("Objects indexed: ${totalRoads + totalBuildings + totalLanduse} features")
        println("Original GPKG size: ${gpkgFile.length() / 1024} KB")
        println("Spatial Index size: ${indexFile.length() / 1024} KB")
        println("Indexing time: %.2f ms (%.1f obj/sec)".format(indexDurationMs, (totalRoads + totalBuildings + totalLanduse) / (indexDurationMs / 1000.0)))

        // Measure Tile Rendering
        val tileSource = GeoPackageTileSource.create(gpkgFile)!!
        val zoom = 15
        val x = 16520
        val y = 12184

        // Warm up
        tileSource.getTileBitmap(zoom, x, y)

        val iterations = 10
        val renderTimes = mutableListOf<Double>()
        for (i in 1..iterations) {
            val renderDurationNs = measureNanoTime {
                val bmp = tileSource.getTileBitmap(zoom, x, y)
                assertNotNull(bmp)
                assertEquals(Bitmap.Config.RGB_565, bmp!!.config)
            }
            renderTimes.add(renderDurationNs / 1_000_000.0)
        }

        val avgRenderMs = renderTimes.average()
        val minRenderMs = renderTimes.minOrNull() ?: 0.0
        val maxRenderMs = renderTimes.maxOrNull() ?: 0.0

        println("=== BENCHMARK: TILE RENDER ===")
        println("Average render time (512x512 RGB_565 tile): %.2f ms (min: %.2f ms, max: %.2f ms)".format(avgRenderMs, minRenderMs, maxRenderMs))
        println("Tile RAM consumption: 512 * 512 * 2 bytes = 512 KB per tile (RGB_565)")

        assertTrue("Tile rendering should be reasonably fast with spatial index", avgRenderMs < 2500.0)
        tileSource.close()
    }

    @Test
    fun testOutdatedIndex_DetectedAndMarkedNotReady() = runBlocking {
        val gpkgFile = File(testDir, "outdated_test.gpkg")
        val db = SQLiteDatabase.openOrCreateDatabase(gpkgFile, null)
        db.execSQL("CREATE TABLE gpkg_contents (table_name TEXT, data_type TEXT)")
        db.execSQL("INSERT INTO gpkg_contents VALUES ('test_layer', 'features')")
        db.execSQL("CREATE TABLE test_layer (id INTEGER PRIMARY KEY, geom BLOB)")
        db.close()

        // Create an old spatialindex (schema version 1 or without version)
        val indexFile = File(testDir, "outdated_test.spatialindex")
        val indexDb = SQLiteDatabase.openOrCreateDatabase(indexFile, null)
        indexDb.execSQL("CREATE TABLE index_metadata (key TEXT PRIMARY KEY, value TEXT)")
        indexDb.execSQL("INSERT INTO index_metadata (key, value) VALUES ('version', '1')")
        indexDb.close()

        // Index compatibility check should return false
        assertFalse(GeoPackageIndexer.isIndexFileCompatible(indexFile))

        // Opening via GeoPackageTileSource should detect outdated index and not be ready
        val tileSource = GeoPackageTileSource.create(gpkgFile)!!
        assertTrue(tileSource.isIndexOutdated)
        assertFalse(tileSource.isIndexReady())
        tileSource.close()
    }

    @Test
    fun testMultiLineString_SegmentedAndParsedCorrectly() {
        // Create MULTILINESTRING with 2 sub-lines:
        // Sub-line 1: 120 points (lon: 30.0 + i*0.01, lat: 50.0 + i*0.01)
        // Sub-line 2: 30 points (lon: 35.0 + i*0.01, lat: 55.0 + i*0.01)
        val line1 = (0 until 120).map { DoublePoint(30.0 + it * 0.01, 50.0 + it * 0.01) }
        val line2 = (0 until 30).map { DoublePoint(35.0 + it * 0.01, 55.0 + it * 0.01) }
        val geomBytes = createGpkgGeometryBlob(wkbType = 5, coordinates = listOf(line1, line2))

        val segments = com.example.map.vector.GeoPackageGeometryParser.extractSegmentedBoundingBoxes(geomBytes, maxPointsPerSegment = 50)
        // Line 1 (120 pts) -> 3 segments: 0..49, 49..98, 98..119
        // Line 2 (30 pts)  -> 1 segment: 120..149
        assertEquals(4, segments.size)

        assertEquals(0, segments[0].pointStart)
        assertEquals(49, segments[0].pointEnd)

        assertEquals(49, segments[1].pointStart)
        assertEquals(98, segments[1].pointEnd)

        assertEquals(98, segments[2].pointStart)
        assertEquals(119, segments[2].pointEnd)

        assertEquals(120, segments[3].pointStart)
        assertEquals(149, segments[3].pointEnd)

        // Parse segment 1 (points 49..98 of subline 1)
        val seg1Feature = com.example.map.vector.GeoPackageGeometryParser.parsePointRange(
            fid = 101L,
            geomBytes = geomBytes,
            pointStart = segments[1].pointStart,
            pointEnd = segments[1].pointEnd
        )
        assertNotNull(seg1Feature)
        assertEquals("MULTILINESTRING", seg1Feature!!.geometryType)
        assertEquals(1, seg1Feature.rings.size)
        assertEquals(50, seg1Feature.rings[0].size)
        assertEquals(line1[49].lon, seg1Feature.rings[0].first().lon, 1e-6)
        assertEquals(line1[98].lon, seg1Feature.rings[0].last().lon, 1e-6)

        // Parse segment 3 (points 0..29 of subline 2, global 120..149)
        val seg3Feature = com.example.map.vector.GeoPackageGeometryParser.parsePointRange(
            fid = 101L,
            geomBytes = geomBytes,
            pointStart = segments[3].pointStart,
            pointEnd = segments[3].pointEnd
        )
        assertNotNull(seg3Feature)
        assertEquals(1, seg3Feature!!.rings.size)
        assertEquals(30, seg3Feature.rings[0].size)
        assertEquals(line2[0].lon, seg3Feature.rings[0].first().lon, 1e-6)
        assertEquals(line2[29].lon, seg3Feature.rings[0].last().lon, 1e-6)
    }

    @Test
    fun testZoomRules_SqlAndKotlinMatch() {
        val testRoadClasses = listOf("motorway", "primary", "secondary", "tertiary", "residential", "service", "track_grade1", "footway")
        for (zoom in 5..16) {
            val sqlFilter = GeoPackageStyle.sqlVisibilityFilter("gis_osm_roads_free", zoom)
            for (fclass in testRoadClasses) {
                val isVisible = GeoPackageStyle.isFeatureVisibleAtZoom("gis_osm_roads_free", mapOf("fclass" to fclass), zoom)
                if (sqlFilter != null) {
                    val sqlMatches = if (sqlFilter.second.isNotEmpty()) {
                        fclass in sqlFilter.second
                    } else {
                        !sqlFilter.first.contains("'$fclass'") && !(sqlFilter.first.contains("track_grade") && fclass.startsWith("track_grade"))
                    }
                    assertEquals("Mismatch for road $fclass at zoom $zoom", isVisible, sqlMatches)
                } else {
                    assertTrue("All roads should be visible at zoom $zoom", isVisible)
                }
            }
        }
    }
}
