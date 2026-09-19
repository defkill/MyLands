package com.example.data.elevation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.map.MapProjection
import com.example.map.TileCoordinate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContourEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var elevationEngine: ElevationEngine
    private lateinit var contourEngine: ContourEngine
    private lateinit var hgtDir: File

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        elevationEngine = ElevationEngine(app)
        hgtDir = elevationEngine.elevationDir
        hgtDir.mkdirs()
        contourEngine = ContourEngine(elevationEngine)
    }

    /**
     * Creates a synthetic SRTM-3 HGT tile (1201x1201) with a conical hill.
     */
    private fun createSyntheticHgtTile(name: String = "N50E030.hgt", peakHeight: Short = 300) {
        val file = File(hgtDir, name)
        val size = 1201
        val bytes = ByteArray(size * size * 2)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        for (row in 0 until size) {
            for (col in 0 until size) {
                // Distance from center of tile
                val dr = (row - size / 2.0) / (size / 2.0)
                val dc = (col - size / 2.0) / (size / 2.0)
                val dist = Math.sqrt(dr * dr + dc * dc)
                val h = if (dist <= 1.0) ((1.0 - dist) * peakHeight).toInt().toShort() else 0.toShort()
                buf.putShort(h)
            }
        }

        RandomAccessFile(file, "rw").use { raf ->
            raf.write(bytes)
        }
    }

    @Test
    fun testContourIntervalForZoom() {
        assertEquals(50.0, contourEngine.contourIntervalForZoom(10), 0.001)
        assertEquals(50.0, contourEngine.contourIntervalForZoom(11), 0.001)
        assertEquals(20.0, contourEngine.contourIntervalForZoom(12), 0.001)
        assertEquals(20.0, contourEngine.contourIntervalForZoom(13), 0.001)
        assertEquals(10.0, contourEngine.contourIntervalForZoom(14), 0.001)
        assertEquals(10.0, contourEngine.contourIntervalForZoom(15), 0.001)
        assertEquals(5.0, contourEngine.contourIntervalForZoom(16), 0.001)
    }

    @Test
    fun testGetContourTile_WithElevationData_GeneratesContourSegments() {
        createSyntheticHgtTile("N50E030.hgt", peakHeight = 400)
        elevationEngine.hasAnyTiles()

        // Tile covering slope of the hill in N50 E030 at zoom 11
        val lat = 50.4
        val lon = 30.4
        val tileX = MapProjection.lonToTileX(lon, 11)
        val tileY = MapProjection.latToTileY(lat, 11)
        val tileCoord = TileCoordinate(tileX, tileY, 11)

        val contourTile = contourEngine.getContourTile(tileCoord)
        assertNotNull(contourTile)
        assertFalse("Should generate contour segments on conical hill slope", contourTile.segments.isEmpty())

        // Check segment properties
        val hasIndex = contourTile.segments.any { it.isIndex }
        val hasMinor = contourTile.segments.any { !it.isIndex }
        assertTrue("Should have minor contour lines", hasMinor)
        assertTrue("Should have index contour lines", hasIndex)

        for (seg in contourTile.segments) {
            assertTrue("Elevation should be positive", seg.elevation > 0)
            assertTrue("Segment coordinates should be valid lat/lon", seg.p1.latitude in -90.0..90.0)
            assertTrue("Segment coordinates should be valid lat/lon", seg.p2.latitude in -90.0..90.0)
        }
    }

    @Test
    fun testGetContourTile_NoElevationData_ReturnsEmpty() {
        val tileCoord = TileCoordinate(100, 100, 14)
        val contourTile = contourEngine.getContourTile(tileCoord)
        assertTrue(contourTile.segments.isEmpty())
    }
}
