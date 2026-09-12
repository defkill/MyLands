package com.example.map

import com.example.model.GeoPoint
import kotlin.math.*

data class TileCoordinate(
    val x: Int,
    val y: Int,
    val zoom: Int
) {
    val key: String get() = "$zoom/$x/$y"
}

enum class MapTileType(val title: String) {
    OSM_STANDARD("Карта (OpenStreetMap)"),
    SATELLITE("Спутник (Esri World Imagery)"),
    TOPO("Рельеф (OpenTopoMap)"),
    MBTILES("Офлайн-карта MBTiles")
}

open class TileSource(
    open val id: String,
    open val name: String,
    open val type: MapTileType,
    open val urlTemplate: String = "",
    open val maxZoom: Int = 18,
    open val minZoom: Int = 1
) {
    companion object {
        // 1. "Карта" — OpenStreetMap standard raster (с названиями улиц/населённых пунктов)
        val MAP = TileSource(
            id = "osm_standard",
            name = "Карта",
            type = MapTileType.OSM_STANDARD,
            urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            maxZoom = 19
        )

        // 2. "Спутник" — Esri World Imagery (чистые снимки, без подписей)
        val SATELLITE = TileSource(
            id = "satellite",
            name = "Спутник",
            type = MapTileType.SATELLITE,
            urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            maxZoom = 18
        )

        // 3. "Рельеф" — OpenTopoMap (с горизонталями, минимум подписей)
        val TOPO = TileSource(
            id = "topo",
            name = "Рельеф",
            type = MapTileType.TOPO,
            urlTemplate = "https://tile.opentopomap.org/{z}/{x}/{y}.png",
            maxZoom = 17
        )

        // Backwards compatibility aliases
        val OSM = MAP

        val ALL = listOf(MAP, SATELLITE, TOPO)
    }

    open fun getTileUrl(tile: TileCoordinate): String {
        return urlTemplate
            .replace("{z}", tile.zoom.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TileSource) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

/**
 * Mathematical projections for Web Mercator (EPSG:3857) map tiles.
 */
object MapProjection {
    const val TILE_SIZE = 256.0

    fun lonToWorldX(lonDeg: Double, zoom: Double): Double {
        val tilesCount = 2.0.pow(zoom)
        return (lonDeg + 180.0) / 360.0 * TILE_SIZE * tilesCount
    }

    fun latToWorldY(latDeg: Double, zoom: Double): Double {
        val latClamped = latDeg.coerceIn(-85.05112878, 85.05112878)
        val latRad = Math.toRadians(latClamped)
        val tilesCount = 2.0.pow(zoom)
        val yFactor = 1.0 - (ln(tan(Math.PI / 4.0 + latRad / 2.0)) / Math.PI)
        return yFactor * 0.5 * TILE_SIZE * tilesCount
    }

    fun worldXToLon(worldX: Double, zoom: Double): Double {
        val tilesCount = 2.0.pow(zoom)
        return (worldX / (TILE_SIZE * tilesCount)) * 360.0 - 180.0
    }

    fun worldYToLat(worldY: Double, zoom: Double): Double {
        val tilesCount = 2.0.pow(zoom)
        val n = Math.PI * (1.0 - 2.0 * worldY / (TILE_SIZE * tilesCount))
        return Math.toDegrees(atan(sinh(n)))
    }

    fun geoToScreen(
        geoPoint: GeoPoint,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        screenWidth: Float,
        screenHeight: Float
    ): Pair<Float, Float> {
        val cX = lonToWorldX(centerLon, zoom)
        val cY = latToWorldY(centerLat, zoom)

        val pX = lonToWorldX(geoPoint.longitude, zoom)
        val pY = latToWorldY(geoPoint.latitude, zoom)

        val sX = (pX - cX).toFloat() + screenWidth / 2f
        val sY = (pY - cY).toFloat() + screenHeight / 2f
        return Pair(sX, sY)
    }

    fun screenToGeo(
        screenX: Float,
        screenY: Float,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        screenWidth: Float,
        screenHeight: Float
    ): GeoPoint {
        val cX = lonToWorldX(centerLon, zoom)
        val cY = latToWorldY(centerLat, zoom)

        val worldX = cX + (screenX - screenWidth / 2f)
        val worldY = cY + (screenY - screenHeight / 2f)

        val lon = worldXToLon(worldX, zoom)
        val lat = worldYToLat(worldY, zoom)
        return GeoPoint(lat, lon)
    }
}
