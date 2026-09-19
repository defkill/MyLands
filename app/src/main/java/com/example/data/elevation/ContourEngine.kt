package com.example.data.elevation

import android.util.LruCache
import com.example.map.MapProjection
import com.example.map.TileCoordinate
import com.example.model.GeoPoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class ContourSegment(
    val p1: GeoPoint,
    val p2: GeoPoint,
    val elevation: Double,
    val isIndex: Boolean
)

data class ContourTile(
    val tile: TileCoordinate,
    val segments: List<ContourSegment>
)

/**
 * Generates topographical contour lines (isohypses) from SRTM elevation matrices
 * using the Marching Squares algorithm with linear interpolation.
 */
class ContourEngine(private val elevationEngine: ElevationEngine) {

    // Cache recently computed contour tiles by memory size (KB) so rendering remains at 60 FPS
    // without exceeding memory budget on low-RAM devices
    private val maxCacheKb = ((Runtime.getRuntime().maxMemory() / 1024) / 16).toInt().coerceIn(2048, 16384)
    private val tileCache = object : LruCache<String, ContourTile>(maxCacheKb) {
        override fun sizeOf(key: String, value: ContourTile): Int {
            val segmentsSize = value.segments.size * 112
            val totalBytes = 64 + segmentsSize
            return (totalBytes / 1024).coerceAtLeast(1)
        }
    }

    /**
     * Determines contour interval in meters depending on the map zoom level.
     */
    fun contourIntervalForZoom(zoom: Int): Double = when {
        zoom <= 11 -> 50.0
        zoom in 12..13 -> 20.0
        zoom in 14..15 -> 10.0
        else -> 5.0
    }

    /**
     * Generates or fetches cached contour segments for a specific map tile.
     * Returns empty list if no elevation data is available for this tile.
     */
    fun getContourTile(tile: TileCoordinate): ContourTile {
        val cacheKey = "${tile.zoom}/${tile.x}/${tile.y}"
        tileCache.get(cacheKey)?.let { return it }

        if (tile.zoom < 10 || !elevationEngine.hasAnyTiles()) {
            val empty = ContourTile(tile, emptyList())
            tileCache.put(cacheKey, empty)
            return empty
        }

        // Calculate geographical bounding box for this tile
        val northLat = MapProjection.tileYToLat(tile.y, tile.zoom)
        val southLat = MapProjection.tileYToLat(tile.y + 1, tile.zoom)
        val westLon = MapProjection.tileXToLon(tile.x, tile.zoom)
        val eastLon = MapProjection.tileXToLon(tile.x + 1, tile.zoom)

        // Check if any HGT tiles cover this bounding box
        val (needed, missing) = elevationEngine.tilesForBounds(southLat, northLat, westLon, eastLon)
        if (needed.isEmpty() || missing.size == needed.size) {
            val empty = ContourTile(tile, emptyList())
            tileCache.put(cacheKey, empty)
            return empty
        }

        val interval = contourIntervalForZoom(tile.zoom)
        val gridSize = 32 // 32x32 elevation sampling matrix per tile for crisp contours and high performance

        // Sample elevation matrix
        val elevGrid = Array(gridSize + 1) { DoubleArray(gridSize + 1) }
        var minElev = Double.POSITIVE_INFINITY
        var maxElev = Double.NEGATIVE_INFINITY
        var hasValidElev = false

        for (r in 0..gridSize) {
            val lat = northLat - (r.toDouble() / gridSize) * (northLat - southLat)
            for (c in 0..gridSize) {
                val lon = westLon + (c.toDouble() / gridSize) * (eastLon - westLon)
                val elev = elevationEngine.getElevation(lat, lon)
                if (elev != null) {
                    elevGrid[r][c] = elev
                    if (elev < minElev) minElev = elev
                    if (elev > maxElev) maxElev = elev
                    hasValidElev = true
                } else {
                    elevGrid[r][c] = Double.NaN
                }
            }
        }

        if (!hasValidElev || minElev >= maxElev) {
            val empty = ContourTile(tile, emptyList())
            tileCache.put(cacheKey, empty)
            return empty
        }

        val startLevel = (ceil(minElev / interval) * interval).roundToInt()
        val endLevel = (floor(maxElev / interval) * interval).roundToInt()

        val segments = mutableListOf<ContourSegment>()
        val indexInterval = interval * 5.0

        for (levelInt in startLevel..endLevel step interval.toInt().coerceAtLeast(1)) {
            val level = levelInt.toDouble()
            val isIndex = (levelInt % indexInterval.toInt()) == 0

            // Marching Squares per cell
            for (r in 0 until gridSize) {
                val latTop = northLat - (r.toDouble() / gridSize) * (northLat - southLat)
                val latBottom = northLat - ((r + 1).toDouble() / gridSize) * (northLat - southLat)

                for (c in 0 until gridSize) {
                    val lonLeft = westLon + (c.toDouble() / gridSize) * (eastLon - westLon)
                    val lonRight = westLon + ((c + 1).toDouble() / gridSize) * (eastLon - westLon)

                    val vTL = elevGrid[r][c]
                    val vTR = elevGrid[r][c + 1]
                    val vBR = elevGrid[r + 1][c + 1]
                    val vBL = elevGrid[r + 1][c]

                    if (vTL.isNaN() || vTR.isNaN() || vBR.isNaN() || vBL.isNaN()) continue

                    var mask = 0
                    if (vTL >= level) mask = mask or 8
                    if (vTR >= level) mask = mask or 4
                    if (vBR >= level) mask = mask or 2
                    if (vBL >= level) mask = mask or 1

                    if (mask == 0 || mask == 15) continue

                    // Interpolate edge crossing points
                    // Top edge (between TL and TR)
                    fun topPoint(): GeoPoint {
                        val frac = if (vTR != vTL) ((level - vTL) / (vTR - vTL)).coerceIn(0.0, 1.0) else 0.5
                        return GeoPoint(latTop, lonLeft + frac * (lonRight - lonLeft))
                    }

                    // Right edge (between TR and BR)
                    fun rightPoint(): GeoPoint {
                        val frac = if (vBR != vTR) ((level - vTR) / (vBR - vTR)).coerceIn(0.0, 1.0) else 0.5
                        return GeoPoint(latTop - frac * (latTop - latBottom), lonRight)
                    }

                    // Bottom edge (between BL and BR)
                    fun bottomPoint(): GeoPoint {
                        val frac = if (vBR != vBL) ((level - vBL) / (vBR - vBL)).coerceIn(0.0, 1.0) else 0.5
                        return GeoPoint(latBottom, lonLeft + frac * (lonRight - lonLeft))
                    }

                    // Left edge (between TL and BL)
                    fun leftPoint(): GeoPoint {
                        val frac = if (vBL != vTL) ((level - vTL) / (vBL - vTL)).coerceIn(0.0, 1.0) else 0.5
                        return GeoPoint(latTop - frac * (latTop - latBottom), lonLeft)
                    }

                    when (mask) {
                        1 -> segments.add(ContourSegment(leftPoint(), bottomPoint(), level, isIndex))
                        2 -> segments.add(ContourSegment(bottomPoint(), rightPoint(), level, isIndex))
                        3 -> segments.add(ContourSegment(leftPoint(), rightPoint(), level, isIndex))
                        4 -> segments.add(ContourSegment(topPoint(), rightPoint(), level, isIndex))
                        5 -> {
                            segments.add(ContourSegment(leftPoint(), topPoint(), level, isIndex))
                            segments.add(ContourSegment(bottomPoint(), rightPoint(), level, isIndex))
                        }
                        6 -> segments.add(ContourSegment(topPoint(), bottomPoint(), level, isIndex))
                        7 -> segments.add(ContourSegment(leftPoint(), topPoint(), level, isIndex))
                        8 -> segments.add(ContourSegment(leftPoint(), topPoint(), level, isIndex))
                        9 -> segments.add(ContourSegment(topPoint(), bottomPoint(), level, isIndex))
                        10 -> {
                            segments.add(ContourSegment(topPoint(), rightPoint(), level, isIndex))
                            segments.add(ContourSegment(leftPoint(), bottomPoint(), level, isIndex))
                        }
                        11 -> segments.add(ContourSegment(topPoint(), rightPoint(), level, isIndex))
                        12 -> segments.add(ContourSegment(leftPoint(), rightPoint(), level, isIndex))
                        13 -> segments.add(ContourSegment(bottomPoint(), rightPoint(), level, isIndex))
                        14 -> segments.add(ContourSegment(leftPoint(), bottomPoint(), level, isIndex))
                    }
                }
            }
        }

        val result = ContourTile(tile, segments)
        tileCache.put(cacheKey, result)
        return result
    }

    fun clearCache() {
        tileCache.evictAll()
    }
}
