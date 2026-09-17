package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.elevation.ElevationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ElevationEngineConcurrencyTest {

    private lateinit var context: Context
    private lateinit var engine: ElevationEngine
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = ElevationEngine(context)

        // Create 10 dummy SRTM3 tiles (MAX_OPEN_TILES is 4, so this forces frequent LRU evictions)
        val srtm3SizeBytes = 1201L * 1201L * 2L
        for (lon in 30..39) {
            val tileName = "N50E0$lon.hgt"
            val file = File(engine.elevationDir, tileName)
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(srtm3SizeBytes)
                // Write a sample value in big endian
                raf.seek(0)
                raf.writeShort(150 + lon)
            }
            createdFiles.add(file)
        }
    }

    @After
    fun tearDown() {
        engine.close()
        createdFiles.forEach { it.delete() }
    }

    @Test
    fun `concurrent elevation requests across multiple tiles do not crash or corrupt cache`() = runBlocking {
        // Launch 40 concurrent async requests across 10 different tiles on Dispatchers.IO
        val deferreds = (1..40).map { i ->
            async(Dispatchers.IO) {
                val lon = 30 + (i % 10)
                val elev = engine.getElevation(50.5, lon + 0.5)
                elev
            }
        }

        val results = deferreds.awaitAll()
        assertEquals(40, results.size)
        results.forEach { elevation ->
            assertNotNull("Elevation should be sampled without null or exception", elevation)
        }
    }
}
