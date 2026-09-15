package com.example.data.elevation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.floor

/**
 * Reads terrain elevation from local SRTM `.hgt` tiles.
 *
 * Format: a bare grid of 16-bit signed big-endian samples, no header. SRTM-1 is 3601x3601 (~30 m
 * spacing, ~25 MB per tile), SRTM-3 is 1201x1201 (~90 m). The file name carries the tile's
 * south-west corner, e.g. N50E036.hgt covers latitude 50..51, longitude 36..37.
 *
 * Two details that are easy to get wrong and produce silently broken terrain:
 *  - Row 0 is the NORTHERN edge, not the southern one. Reading it the other way mirrors the
 *    relief vertically.
 *  - Voids are marked -32768. Left unfiltered they read as a 32 km drop and wreck any profile
 *    or line-of-sight result.
 *
 * Tiles are memory-mapped rather than loaded: a 25 MB tile does not belong in RAM, and the OS
 * pages in only the parts actually touched.
 */
class ElevationEngine(private val context: Context) {

    companion object {
        private const val TAG = "ElevationEngine"

        const val VOID_VALUE = -32768

        /** SRTM-1 and SRTM-3 grid sizes, deduced from file length. */
        const val SIZE_SRTM1 = 3601
        const val SIZE_SRTM3 = 1201

        /** How many tiles stay mapped at once while panning. */
        private const val MAX_OPEN_TILES = 4

        /** Tile name for a coordinate, e.g. N50E036. */
        fun tileNameFor(lat: Double, lon: Double): String {
            val latPrefix = if (lat >= 0) "N" else "S"
            val lonPrefix = if (lon >= 0) "E" else "W"
            val latDeg = floor(lat).toInt()
            val lonDeg = floor(lon).toInt()
            return String.format(
                "%s%02d%s%03d",
                latPrefix, abs(latDeg),
                lonPrefix, abs(lonDeg)
            )
        }
    }

    /** Where imported tiles live; survives app restarts and is never auto-cleared. */
    val elevationDir: File = File(context.filesDir, "elevation").apply { mkdirs() }

    private class Tile(
        // ByteBuffer, not MappedByteBuffer: order() returns the base type even though the
        // underlying buffer is still the memory-mapped one.
        val buffer: java.nio.ByteBuffer,
        val size: Int,
        val raf: RandomAccessFile
    )

    private val openTiles = LinkedHashMap<String, Tile>()

    /** Tile names present on disk. */
    fun availableTiles(): List<String> =
        elevationDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".hgt", ignoreCase = true) }
            ?.map { it.nameWithoutExtension.uppercase() }
            ?.sorted()
            ?: emptyList()

    fun hasAnyTiles(): Boolean = availableTiles().isNotEmpty()

    /**
     * Tile names needed to cover a bounding box, and which of them are missing. Users should not
     * have to work out that their area is "N50E036" by hand.
     */
    fun tilesForBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): Pair<List<String>, List<String>> {
        val needed = LinkedHashSet<String>()
        var lat = floor(minLat)
        while (lat <= floor(maxLat)) {
            var lon = floor(minLon)
            while (lon <= floor(maxLon)) {
                needed.add(tileNameFor(lat, lon))
                lon += 1.0
            }
            lat += 1.0
        }
        val have = availableTiles().toSet()
        return needed.toList() to needed.filter { !have.contains(it) }
    }

    private fun openTile(name: String): Tile? {
        openTiles[name]?.let { return it }

        val file = File(elevationDir, "$name.hgt")
        if (!file.exists()) return null

        return try {
            val size = when (file.length()) {
                SIZE_SRTM1.toLong() * SIZE_SRTM1 * 2 -> SIZE_SRTM1
                SIZE_SRTM3.toLong() * SIZE_SRTM3 * 2 -> SIZE_SRTM3
                else -> {
                    Log.w(TAG, "$name: unexpected size ${file.length()}")
                    return null
                }
            }

            val raf = RandomAccessFile(file, "r")
            val buffer = raf.channel
                .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                .order(ByteOrder.BIG_ENDIAN)

            val tile = Tile(buffer, size, raf)

            if (openTiles.size >= MAX_OPEN_TILES) {
                val oldest = openTiles.keys.first()
                openTiles.remove(oldest)?.let { runCatching { it.raf.close() } }
            }
            openTiles[name] = tile
            Log.d(TAG, "Opened $name (${size}x$size)")
            tile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open $name", e)
            null
        }
    }

    /** Raw grid sample, or null for a void / out-of-range index. */
    private fun sampleAt(tile: Tile, row: Int, col: Int): Int? {
        if (row < 0 || col < 0 || row >= tile.size || col >= tile.size) return null
        val index = (row * tile.size + col) * 2
        val value = tile.buffer.getShort(index).toInt()
        return if (value == VOID_VALUE) null else value
    }

    /**
     * Terrain elevation above sea level, bilinearly interpolated between the four surrounding
     * grid nodes so profiles come out smooth instead of stepped.
     *
     * @return metres, or null when no tile covers the point or the data there is void.
     */
    fun getElevation(lat: Double, lon: Double): Double? {
        val tile = openTile(tileNameFor(lat, lon)) ?: return null
        val n = tile.size - 1

        // Position inside the tile, 0..1 from the south-west corner.
        val latFrac = lat - floor(lat)
        val lonFrac = lon - floor(lon)

        val x = lonFrac * n
        // Row 0 is the northern edge, hence the inversion.
        val y = (1.0 - latFrac) * n

        val col0 = floor(x).toInt()
        val row0 = floor(y).toInt()
        val col1 = (col0 + 1).coerceAtMost(n)
        val row1 = (row0 + 1).coerceAtMost(n)

        val dx = x - col0
        val dy = y - row0

        val v00 = sampleAt(tile, row0, col0)
        val v01 = sampleAt(tile, row0, col1)
        val v10 = sampleAt(tile, row1, col0)
        val v11 = sampleAt(tile, row1, col1)

        // With any corner void, fall back to the average of whatever is valid rather than
        // returning nothing: a slightly coarser height beats a hole in the profile.
        if (v00 == null || v01 == null || v10 == null || v11 == null) {
            val valid = listOfNotNull(v00, v01, v10, v11)
            return if (valid.isEmpty()) null else valid.average()
        }

        val top = v00 + (v01 - v00) * dx
        val bottom = v10 + (v11 - v10) * dx
        return top + (bottom - top) * dy
    }

    suspend fun getElevationAsync(lat: Double, lon: Double): Double? =
        withContext(Dispatchers.IO) { getElevation(lat, lon) }

    fun close() {
        openTiles.values.forEach { runCatching { it.raf.close() } }
        openTiles.clear()
    }
}
