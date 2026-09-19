package com.example.map.vector

import android.database.sqlite.SQLiteDatabase
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
class GeoPackageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = tempFolder.newFolder("gpkg_test")
    }

    /**
     * Helper to synthesize standard OGC GeoPackage geometry binary blob.
     */
    private fun createGpkgGeometryBlob(
        wkbType: Int,
        coordinates: List<List<DoublePoint>>,
        envelope: GeoBoundingBox? = null,
        isLittleEndian: Boolean = true
    ): ByteArray {
        val byteOrder = if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val envelopeIndicator = if (envelope != null) 1 else 0
        val flags = (if (isLittleEndian) 1 else 0) or (envelopeIndicator shl 1)

        val headerSize = 8 + (if (envelope != null) 32 else 0)
        val buffer = ByteBuffer.allocate(1024).order(byteOrder)

        // GeoPackage Header
        buffer.put(0x47.toByte()) // 'G'
        buffer.put(0x50.toByte()) // 'P'
        buffer.put(0.toByte())    // version
        buffer.put(flags.toByte())
        buffer.putInt(4326) // SRS WGS84

        if (envelope != null) {
            buffer.putDouble(envelope.minX)
            buffer.putDouble(envelope.maxX)
            buffer.putDouble(envelope.minY)
            buffer.putDouble(envelope.maxY)
        }

        // WKB Payload
        buffer.put(if (isLittleEndian) 1.toByte() else 0.toByte())
        buffer.putInt(wkbType)

        when (wkbType) {
            1 -> { // Point
                val pt = coordinates.first().first()
                buffer.putDouble(pt.lon)
                buffer.putDouble(pt.lat)
            }
            2 -> { // LineString
                val line = coordinates.first()
                buffer.putInt(line.size)
                line.forEach { pt ->
                    buffer.putDouble(pt.lon)
                    buffer.putDouble(pt.lat)
                }
            }
            3 -> { // Polygon
                buffer.putInt(coordinates.size) // num rings
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
    fun testParse_PointWithHeaderEnvelope() {
        val bbox = GeoBoundingBox(1.5, 42.5, 1.5, 42.5)
        val blob = createGpkgGeometryBlob(
            wkbType = 1,
            coordinates = listOf(listOf(DoublePoint(1.5, 42.5))),
            envelope = bbox
        )

        val extractedBbox = GeoPackageGeometryParser.extractBoundingBox(blob)
        assertNotNull(extractedBbox)
        assertEquals(1.5, extractedBbox!!.minX, 0.0001)
        assertEquals(42.5, extractedBbox.minY, 0.0001)

        val feature = GeoPackageGeometryParser.parse(101L, blob, mapOf("name" to "Andorra la Vella"))
        assertNotNull(feature)
        assertEquals(101L, feature!!.fid)
        assertEquals("POINT", feature.geometryType)
        assertEquals(1, feature.rings.size)
        assertEquals(DoublePoint(1.5, 42.5), feature.rings[0][0])
        assertEquals("Andorra la Vella", feature.attributes["name"])
    }

    @Test
    fun testParse_LineStringWithoutEnvelope_ComputesBBoxFromWkb() {
        val line = listOf(
            DoublePoint(1.50, 42.50),
            DoublePoint(1.55, 42.52),
            DoublePoint(1.60, 42.48)
        )
        val blob = createGpkgGeometryBlob(
            wkbType = 2,
            coordinates = listOf(line),
            envelope = null // Test fallback WKB bbox computation
        )

        val extractedBbox = GeoPackageGeometryParser.extractBoundingBox(blob)
        assertNotNull(extractedBbox)
        assertEquals(1.50, extractedBbox!!.minX, 0.0001)
        assertEquals(1.60, extractedBbox.maxX, 0.0001)
        assertEquals(42.48, extractedBbox.minY, 0.0001)
        assertEquals(42.52, extractedBbox.maxY, 0.0001)

        val feature = GeoPackageGeometryParser.parse(202L, blob, mapOf("fclass" to "primary"))
        assertNotNull(feature)
        assertEquals("LINESTRING", feature!!.geometryType)
        assertEquals(3, feature.rings[0].size)
    }

    @Test
    fun testParse_PolygonWithHole() {
        val exterior = listOf(
            DoublePoint(1.0, 42.0),
            DoublePoint(2.0, 42.0),
            DoublePoint(2.0, 43.0),
            DoublePoint(1.0, 43.0),
            DoublePoint(1.0, 42.0)
        )
        val interior = listOf(
            DoublePoint(1.3, 42.3),
            DoublePoint(1.7, 42.3),
            DoublePoint(1.7, 42.7),
            DoublePoint(1.3, 42.7),
            DoublePoint(1.3, 42.3)
        )
        val blob = createGpkgGeometryBlob(
            wkbType = 3,
            coordinates = listOf(exterior, interior)
        )

        val feature = GeoPackageGeometryParser.parse(303L, blob)
        assertNotNull(feature)
        assertEquals("POLYGON", feature!!.geometryType)
        assertEquals(2, feature.rings.size)
        assertEquals(5, feature.rings[0].size)
        assertEquals(5, feature.rings[1].size)
    }

    @Test
    fun testSpatialIndexer_BuildsRTreeAndQueriesViewport() = runBlocking {
        // Create synthetic GeoPackage SQLite database
        val gpkgFile = File(testDir, "test_andorra.gpkg")
        val db = SQLiteDatabase.openOrCreateDatabase(gpkgFile, null)
        try {
            db.execSQL("CREATE TABLE gpkg_contents (table_name TEXT, data_type TEXT, identifier TEXT)")
            db.execSQL("INSERT INTO gpkg_contents VALUES ('gis_osm_roads_free', 'features', 'roads')")
            db.execSQL("INSERT INTO gpkg_contents VALUES ('gis_osm_buildings_a_free', 'features', 'buildings')")

            db.execSQL("CREATE TABLE gpkg_geometry_columns (table_name TEXT, column_name TEXT, geometry_type_name TEXT, srs_id INTEGER, z INTEGER, m INTEGER)")
            db.execSQL("INSERT INTO gpkg_geometry_columns VALUES ('gis_osm_roads_free', 'geom', 'LINESTRING', 4326, 0, 0)")
            db.execSQL("INSERT INTO gpkg_geometry_columns VALUES ('gis_osm_buildings_a_free', 'geom', 'POLYGON', 4326, 0, 0)")

            db.execSQL("CREATE TABLE gis_osm_roads_free (fid INTEGER PRIMARY KEY, geom BLOB, fclass TEXT, name TEXT)")
            db.execSQL("CREATE TABLE gis_osm_buildings_a_free (fid INTEGER PRIMARY KEY, geom BLOB, name TEXT)")

            // Insert 3 roads
            val road1 = createGpkgGeometryBlob(2, listOf(listOf(DoublePoint(1.50, 42.50), DoublePoint(1.52, 42.52))))
            val road2 = createGpkgGeometryBlob(2, listOf(listOf(DoublePoint(1.60, 42.60), DoublePoint(1.62, 42.62))))
            val road3 = createGpkgGeometryBlob(2, listOf(listOf(DoublePoint(2.50, 45.00), DoublePoint(2.52, 45.02)))) // Far away

            db.execSQL("INSERT INTO gis_osm_roads_free (fid, geom, fclass, name) VALUES (1, ?, 'primary', 'Main St')", arrayOf(road1))
            db.execSQL("INSERT INTO gis_osm_roads_free (fid, geom, fclass, name) VALUES (2, ?, 'secondary', 'Side St')", arrayOf(road2))
            db.execSQL("INSERT INTO gis_osm_roads_free (fid, geom, fclass, name) VALUES (3, ?, 'residential', 'Far St')", arrayOf(road3))

            // Insert 1 building
            val bldg = createGpkgGeometryBlob(3, listOf(listOf(
                DoublePoint(1.51, 42.51), DoublePoint(1.515, 42.51), DoublePoint(1.515, 42.515), DoublePoint(1.51, 42.515), DoublePoint(1.51, 42.51)
            )))
            db.execSQL("INSERT INTO gis_osm_buildings_a_free (fid, geom, name) VALUES (10, ?, 'Hotel')", arrayOf(bldg))
        } finally {
            db.close()
        }

        // Build Index
        val indexFile = GeoPackageIndexer.buildSpatialIndex(gpkgFile)
        assertTrue(indexFile.exists())

        // Verify Spatial querying
        val indexDb = SQLiteDatabase.openDatabase(indexFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // Viewport around Andorra (1.49..1.55, 42.49..42.55)
            val minX = 1.49
            val maxX = 1.55
            val minY = 42.49
            val maxY = 42.55

            val hits = GeoPackageIndexer.querySpatialIndex(indexDb, "gis_osm_roads_free", minX, maxX, minY, maxY)
            assertEquals(1, hits.size)
            assertEquals(1L, hits[0]) // Only road1 matched, road2 and road3 outside bbox

            // Query building
            val bldgHits = GeoPackageIndexer.querySpatialIndex(indexDb, "gis_osm_buildings_a_free", minX, maxX, minY, maxY)
            assertEquals(1, bldgHits.size)
            assertEquals(10L, bldgHits[0])
        } finally {
            indexDb.close()
        }
    }
}
