package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.map.MbtilesTileSource
import com.example.map.OfflineMapDetector
import com.example.map.OfflineMapFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MbtilesOfflineMapTest {

    @Test
    fun `test offline map detector by extension and magic bytes`() {
        // Test extension-based detection
        assertEquals(OfflineMapFormat.MBTILES, OfflineMapDetector.detect("crimea_topo.mbtiles"))
        assertEquals(OfflineMapFormat.ORNTPACK, OfflineMapDetector.detect("donbass_region.orntpack"))
        assertEquals(OfflineMapFormat.ORNTPACK, OfflineMapDetector.detect("sector_cache.zip"))
        assertEquals(OfflineMapFormat.UNKNOWN, OfflineMapDetector.detect("track_export.dat"))

        // Test SQLite header detection
        val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        assertEquals(OfflineMapFormat.MBTILES, OfflineMapDetector.detect("unknown_downloaded_file", sqliteHeader))

        // Test ZIP header detection (0x50, 0x4B, 0x03, 0x04)
        val zipHeader = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte(), 0, 0)
        assertEquals(OfflineMapFormat.ORNTPACK, OfflineMapDetector.detect("cache_dump.bin", zipHeader))
    }

    @Test
    fun `test TMS vs XYZ row calculation`() {
        // Formula: tile_row = (1 shl zoom) - 1 - y_xyz
        fun tmsRow(zoom: Int, y: Int) = (1 shl zoom) - 1 - y

        // At zoom 0: total 1 tile (y=0) -> tmsRow = 0
        assertEquals(0, tmsRow(0, 0))

        // At zoom 1: tiles y in 0..1
        assertEquals(1, tmsRow(1, 0))
        assertEquals(0, tmsRow(1, 1))

        // At zoom 4: tiles y in 0..15
        assertEquals(15, tmsRow(4, 0))
        assertEquals(0, tmsRow(4, 15))
        assertEquals(10, tmsRow(4, 5))

        // At zoom 10: 2^10 = 1024 tiles (0..1023)
        assertEquals(1023, tmsRow(10, 0))
        assertEquals(923, tmsRow(10, 100))
        assertEquals(0, tmsRow(10, 1023))
    }

    @Test
    fun `test MBTiles SQLite metadata and tile retrieval`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.cacheDir, "test_map.mbtiles")
        if (dbFile.exists()) dbFile.delete()

        // Create standard MBTiles SQLite structure
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT);")
        db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);")

        // Populate metadata
        db.execSQL("INSERT INTO metadata VALUES ('name', 'Crimea Topo 50k');")
        db.execSQL("INSERT INTO metadata VALUES ('format', 'png');")
        db.execSQL("INSERT INTO metadata VALUES ('minzoom', '5');")
        db.execSQL("INSERT INTO metadata VALUES ('maxzoom', '16');")
        db.execSQL("INSERT INTO metadata VALUES ('bounds', '33.0,44.0,36.5,46.0');")
        db.execSQL("INSERT INTO metadata VALUES ('center', '34.5,45.2,10');")
        db.execSQL("INSERT INTO metadata VALUES ('description', 'Military-grade tactical topographic raster');")

        // Create a 1x1 dummy PNG tile
        val dummyBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        dummyBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val pngBytes = stream.toByteArray()

        // Insert tile at zoom=10, x=600, TMS tile_row=400 (corresponds to XYZ y = 1023 - 400 = 623)
        val stmt = db.compileStatement("INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)")
        stmt.bindLong(1, 10L)
        stmt.bindLong(2, 600L)
        stmt.bindLong(3, 400L)
        stmt.bindBlob(4, pngBytes)
        stmt.executeInsert()

        db.close()

        // Open via MbtilesTileSource
        val source = MbtilesTileSource.create(dbFile)
        assertNotNull("MbtilesTileSource should be created successfully", source)

        assertEquals("Crimea Topo 50k", source!!.name)
        assertEquals(5, source.minZoom)
        assertEquals(16, source.maxZoom)
        assertEquals("png", source.metadata.format)

        val center = source.getCenterPoint()
        assertNotNull("Center point should be parsed", center)
        assertEquals(45.2, center!!.first.latitude, 0.001)
        assertEquals(34.5, center.first.longitude, 0.001)
        assertEquals(10.0, center.second!!, 0.001)

        val bounds = source.getBounds()
        assertNotNull("Bounds should be parsed", bounds)
        assertEquals(4, bounds!!.size)
        assertEquals(33.0, bounds[0], 0.001)

        // Query tile with XYZ coordinates: zoom 10, x 600, y 623
        // Inside MbtilesTileSource, tmsRow = (1 shl 10) - 1 - 623 = 1023 - 623 = 400.
        val tileBmp = source.getTileBitmap(10, 600, 623)
        assertNotNull("Tile bitmap should be found and decoded", tileBmp)
        assertEquals(256, tileBmp!!.width)
        assertEquals(256, tileBmp.height)

        // Query non-existent tile
        val nonExistent = source.getTileBitmap(10, 600, 624)
        assertNull("Non-existent tile should return null", nonExistent)

        source.close()
    }
}
