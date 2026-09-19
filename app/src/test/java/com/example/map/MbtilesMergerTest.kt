package com.example.map

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MbtilesMergerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = tempFolder.newFolder("mbtiles_tests")
    }

    private fun createTestBitmapBytes(color: Int = Color.RED): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun createTestMbtiles(
        fileName: String = "test_${System.nanoTime()}.mbtiles",
        format: String = "png",
        minZoom: Int = 0,
        maxZoom: Int = 18,
        tiles: List<Triple<Int, Int, Int>> = emptyList(),
        tileContent: ByteArray? = null
    ): File {
        val file = File(tempDir, fileName)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                """
                CREATE TABLE tiles (
                    zoom_level INTEGER,
                    tile_column INTEGER,
                    tile_row INTEGER,
                    tile_data BLOB,
                    PRIMARY KEY (zoom_level, tile_column, tile_row)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            db.execSQL("INSERT INTO metadata (name, value) VALUES ('format', ?)", arrayOf(format))
            db.execSQL("INSERT INTO metadata (name, value) VALUES ('minzoom', ?)", arrayOf(minZoom.toString()))
            db.execSQL("INSERT INTO metadata (name, value) VALUES ('maxzoom', ?)", arrayOf(maxZoom.toString()))
            db.execSQL("INSERT INTO metadata (name, value) VALUES ('name', ?)", arrayOf(file.nameWithoutExtension))

            val defaultBytes = tileContent ?: (if (format == "pbf" || format == "mvt") byteArrayOf(0x1a, 0x02) else createTestBitmapBytes())

            tiles.forEach { (z, x, y) ->
                val stmt = db.compileStatement("INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)")
                stmt.bindLong(1, z.toLong())
                stmt.bindLong(2, x.toLong())
                // MBTiles TMS row = ((1 shl z) - 1) - y
                val tmsY = ((1 shl z) - 1) - y
                stmt.bindLong(3, tmsY.toLong())
                stmt.bindBlob(4, defaultBytes)
                stmt.executeInsert()
                stmt.close()
            }
        } finally {
            db.close()
        }
        return file
    }

    @Test
    fun testMerge_TwoRasterFiles_CombinesNonOverlappingTiles() {
        val fileA = createTestMbtiles(fileName = "fileA.mbtiles", tiles = listOf(Triple(5, 10, 10)), format = "png")
        val fileB = createTestMbtiles(fileName = "fileB.mbtiles", tiles = listOf(Triple(5, 20, 20)), format = "png")
        val output = File(tempDir, "merged.mbtiles")

        val result = runBlocking { MbtilesMerger.merge(listOf(fileA, fileB), output) { _, _ -> } }

        assertTrue("Expected MergeResult.Success, got: $result", result is MergeResult.Success)
        val merged = MbtilesTileSource.create(output)
        assertNotNull(merged)
        assertNotNull(merged?.getTileBitmap(5, 10, 10))
        assertNotNull(merged?.getTileBitmap(5, 20, 20))
    }

    @Test
    fun testMerge_RasterAndVector_RejectsWithClearError() {
        val raster = createTestMbtiles(fileName = "raster.mbtiles", format = "png")
        val vector = createTestMbtiles(fileName = "vector.mbtiles", format = "pbf")
        val output = File(tempDir, "out.mbtiles")

        val result = runBlocking { MbtilesMerger.merge(listOf(raster, vector), output) { _, _ -> } }

        assertTrue("Expected MergeResult.Error, got: $result", result is MergeResult.Error)
        assertFalse(output.exists())
    }

    @Test
    fun testMerge_OverlappingTiles_LaterSourceWins() {
        val bytesA = createTestBitmapBytes(Color.RED)
        val bytesB = createTestBitmapBytes(Color.BLUE)

        val fileA = createTestMbtiles(fileName = "overlapA.mbtiles", tiles = listOf(Triple(5, 10, 10)), tileContent = bytesA)
        val fileB = createTestMbtiles(fileName = "overlapB.mbtiles", tiles = listOf(Triple(5, 10, 10)), tileContent = bytesB)
        val output = File(tempDir, "merged_overlap.mbtiles")

        val result = runBlocking { MbtilesMerger.merge(listOf(fileA, fileB), output) { _, _ -> } }
        assertTrue(result is MergeResult.Success)

        val db = SQLiteDatabase.openDatabase(output.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level = 5", null)
        assertTrue(cursor.moveToFirst())
        val blob = cursor.getBlob(0)
        cursor.close()
        db.close()

        assertArrayEquals(bytesB, blob)
    }
}
