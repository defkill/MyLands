package com.example.map

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import com.example.map.vector.DoublePoint
import com.example.map.vector.GeoBoundingBox
import com.example.map.vector.GeoPackageGeometryParser
import com.example.map.vector.GeoPackageIndexer
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeoPackageTileSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = tempFolder.newFolder("gpkg_render_test")
    }

    private fun createGpkgGeometryBlob(
        wkbType: Int,
        coordinates: List<List<DoublePoint>>,
        envelope: GeoBoundingBox? = null
    ): ByteArray {
        val requiredSize = coordinates.sumOf { it.size } * 16 + 128
        val buffer = ByteBuffer.allocate(requiredSize).order(ByteOrder.LITTLE_ENDIAN)
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

        buffer.put(1.toByte()) // Little endian
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
        }

        val bytes = ByteArray(buffer.position())
        System.arraycopy(buffer.array(), 0, bytes, 0, bytes.size)
        return bytes
    }

    @Test
    fun testRenderTile_RasterizesFeaturesToBitmap() = runBlocking {
        val gpkgFile = File(testDir, "andorra_sample.gpkg")
        val db = SQLiteDatabase.openOrCreateDatabase(gpkgFile, null)
        try {
            db.execSQL("CREATE TABLE gpkg_contents (table_name TEXT, data_type TEXT, identifier TEXT)")
            db.execSQL("INSERT INTO gpkg_contents VALUES ('gis_osm_roads_free', 'features', 'roads')")
            db.execSQL("INSERT INTO gpkg_contents VALUES ('gis_osm_landuse_a_free', 'features', 'landuse')")

            db.execSQL("CREATE TABLE gpkg_geometry_columns (table_name TEXT, column_name TEXT, geometry_type_name TEXT, srs_id INTEGER, z INTEGER, m INTEGER)")
            db.execSQL("INSERT INTO gpkg_geometry_columns VALUES ('gis_osm_roads_free', 'geom', 'LINESTRING', 4326, 0, 0)")
            db.execSQL("INSERT INTO gpkg_geometry_columns VALUES ('gis_osm_landuse_a_free', 'geom', 'POLYGON', 4326, 0, 0)")

            db.execSQL("CREATE TABLE gis_osm_roads_free (fid INTEGER PRIMARY KEY, geom BLOB, fclass TEXT, name TEXT)")
            db.execSQL("CREATE TABLE gis_osm_landuse_a_free (fid INTEGER PRIMARY KEY, geom BLOB, fclass TEXT, name TEXT)")

            // Line in Andorra (1.52, 42.50)
            val road = createGpkgGeometryBlob(2, listOf(listOf(DoublePoint(1.52, 42.50), DoublePoint(1.53, 42.51))))
            db.execSQL("INSERT INTO gis_osm_roads_free (fid, geom, fclass, name) VALUES (1, ?, 'primary', 'Avinguda Meritxell')", arrayOf(road))

            // Forest in Andorra
            val forest = createGpkgGeometryBlob(3, listOf(listOf(
                DoublePoint(1.50, 42.49), DoublePoint(1.55, 42.49), DoublePoint(1.55, 42.53), DoublePoint(1.50, 42.53), DoublePoint(1.50, 42.49)
            )))
            db.execSQL("INSERT INTO gis_osm_landuse_a_free (fid, geom, fclass, name) VALUES (2, ?, 'forest', 'Bosc de la Massana')", arrayOf(forest))
        } finally {
            db.close()
        }

        // Build spatial index
        val indexFile = GeoPackageIndexer.buildSpatialIndex(gpkgFile)
        assertTrue(indexFile.exists())

        // Create TileSource
        val tileSource = GeoPackageTileSource.create(gpkgFile)
        assertNotNull(tileSource)
        assertTrue(tileSource!!.isIndexReady())

        // Calculate tile coordinate for (lat=42.50, lon=1.52, zoom=13)
        // lon: 1.52 -> x: (1.52 + 180)/360 * 2^13 = 4130
        // lat: 42.50 -> y: 3046
        val zoom = 13
        val x = 4130
        val y = 3046

        val bitmap = tileSource.getTileBitmap(zoom, x, y)
        assertNotNull("Bitmap should be generated for valid tile", bitmap)
        assertEquals(512, bitmap!!.width)
        assertEquals(512, bitmap.height)
        assertEquals(Bitmap.Config.RGB_565, bitmap.config)

        tileSource.close()
    }

    @Test
    fun testSegmentation_PreventsFalsePositiveHitsInCorners() = runBlocking {
        val gpkgFile = File(testDir, "long_diagonal_road.gpkg")
        // Diagonal road with 150 points from (1.0, 42.0) to (2.0, 43.0)
        val points = (0..150).map { i ->
            val frac = i / 150.0
            DoublePoint(1.0 + frac * 1.0, 42.0 + frac * 1.0)
        }
        val roadBlob = createGpkgGeometryBlob(2, listOf(points))

        val db = SQLiteDatabase.openOrCreateDatabase(gpkgFile, null)
        try {
            db.execSQL("CREATE TABLE gpkg_contents (table_name TEXT, data_type TEXT, identifier TEXT)")
            db.execSQL("INSERT INTO gpkg_contents VALUES ('gis_osm_roads_free', 'features', 'roads')")

            db.execSQL("CREATE TABLE gpkg_geometry_columns (table_name TEXT, column_name TEXT, geometry_type_name TEXT, srs_id INTEGER, z INTEGER, m INTEGER)")
            db.execSQL("INSERT INTO gpkg_geometry_columns VALUES ('gis_osm_roads_free', 'geom', 'LINESTRING', 4326, 0, 0)")

            db.execSQL("CREATE TABLE gis_osm_roads_free (fid INTEGER PRIMARY KEY, geom BLOB, fclass TEXT, name TEXT)")
            db.execSQL("INSERT INTO gis_osm_roads_free (fid, geom, fclass, name) VALUES (10, ?, 'motorway', 'M06 Highway')", arrayOf(roadBlob))
        } finally {
            db.close()
        }

        val indexFile = GeoPackageIndexer.buildSpatialIndex(gpkgFile)
        val indexDb = SQLiteDatabase.openDatabase(indexFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // 1. Box directly on the line at (1.05, 42.05) -> SHOULD match fid 10
            val hitOnLine = GeoPackageIndexer.querySpatialIndex(
                indexDb, "gis_osm_roads_free",
                minX = 1.04, maxX = 1.06, minY = 42.04, maxY = 42.06
            )
            assertEquals(listOf(10L), hitOnLine)

            // Segment query should return segment with point range
            val segments = GeoPackageIndexer.querySpatialSegments(
                indexDb, "gis_osm_roads_free",
                minX = 1.04, maxX = 1.06, minY = 42.04, maxY = 42.06
            )
            assertEquals(1, segments.size)
            assertEquals(10L, segments[0].fid)
            assertEquals(0, segments[0].pointStart)
            assertEquals(49, segments[0].pointEnd)

            val parsedSegment = GeoPackageGeometryParser.parsePointRange(
                fid = 10L,
                geomBytes = roadBlob,
                pointStart = segments[0].pointStart,
                pointEnd = segments[0].pointEnd
            )
            assertNotNull(parsedSegment)
            assertEquals(50, parsedSegment?.rings?.firstOrNull()?.size)

            // 2. Box in opposite corner (1.05, 42.95) -> Inside overall bbox [1.0..2.0, 42.0..43.0]
            // BUT far from the diagonal road! With segmentation, this corner tile has NO segments and returns EMPTY.
            val hitInCorner = GeoPackageIndexer.querySpatialIndex(
                indexDb, "gis_osm_roads_free",
                minX = 1.04, maxX = 1.06, minY = 42.94, maxY = 42.96
            )
            assertTrue("Segmented index must NOT match far-away corner inside overall bbox", hitInCorner.isEmpty())
        } finally {
            indexDb.close()
        }
    }
}
