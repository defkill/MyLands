package com.example.map

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.util.Log
import android.util.LruCache
import com.example.map.vector.DoublePoint
import com.example.map.vector.GeoFeature
import com.example.map.vector.GeoPackageGeometryParser
import com.example.map.vector.GeoPackageIndexer
import com.example.map.vector.GeoPackageStyle
import com.example.model.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/**
 * High-performance, memory-safe TileSource for OGC GeoPackage (.gpkg) vector feature datasets.
 *
 * Utilizes sidecar spatial index (`.spatialindex`) to render OSM vector features into 512x512
 * RGB_565 Bitmaps on-the-fly.
 */
class GeoPackageTileSource(
    val file: File,
    val indexFile: File,
    val bounds: GeoBoundingBox? = null
) : TileSource(
    id = "gpkg_${file.nameWithoutExtension.lowercase().replace("[^a-z0-9_]".toRegex(), "_")}",
    name = file.nameWithoutExtension,
    type = MapTileType.GEOPACKAGE,
    urlTemplate = "",
    maxZoom = 17,
    minZoom = 1
), Closeable {

    private var sourceDb: SQLiteDatabase? = null
    private var indexDb: SQLiteDatabase? = null
    private val tileSize = 512f

    var isIndexOutdated: Boolean = false
        private set

    data class GeoBoundingBox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)

    // Memory-bounded feature cache (capacity 8MB)
    private val featureCache = object : LruCache<String, GeoFeature>((Runtime.getRuntime().maxMemory() / 32).toInt().coerceAtLeast(2 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: GeoFeature): Int {
            var pts = 0
            for (r in value.rings) pts += r.size
            return (pts * 16 + 64 + value.attributes.size * 32).coerceAtLeast(64)
        }
    }

    init {
        try {
            sourceDb = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            if (indexFile.exists()) {
                val candidate = SQLiteDatabase.openDatabase(indexFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                if (GeoPackageIndexer.isIndexCompatible(candidate)) {
                    indexDb = candidate
                    isIndexOutdated = false
                } else {
                    candidate.close()
                    indexDb = null
                    isIndexOutdated = true
                    Log.w(TAG, "Индекс ${indexFile.name} устарел (несовместимая схема v${GeoPackageIndexer.CURRENT_INDEX_SCHEMA_VERSION}) — требуется пересборка")
                }
            } else {
                isIndexOutdated = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening GeoPackage / Index: ${e.message}", e)
        }
    }

    fun isIndexReady(): Boolean = indexDb != null && indexDb?.isOpen == true && !isIndexOutdated

    val isBtreeFallback: Boolean
        get() {
            val iDb = indexDb ?: return false
            return if (iDb.isOpen) GeoPackageIndexer.isUsingBtreeFallback(iDb) else false
        }

    /**
     * Renders a 512x512 RGB_565 bitmap tile for given zoom, x, y coordinates.
     */
    fun getTileBitmap(zoom: Int, x: Int, y: Int): Bitmap? {
        if (zoom < minZoom || zoom > maxZoom) return null
        val sDb = sourceDb ?: return null
        val iDb = indexDb ?: return null
        if (!sDb.isOpen || !iDb.isOpen) return null

        val tileBounds = getTileGeoBounds(zoom, x, y)

        val bitmap = try {
            Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Throwable) {
            return null
        }

        val canvas = Canvas(bitmap)
        canvas.drawColor(GeoPackageStyle.COLOR_BACKGROUND)

        try {
            for (layerName in GeoPackageStyle.RENDER_LAYER_ORDER) {
                if (!GeoPackageStyle.isLayerPossiblyVisibleAtZoom(layerName, zoom)) continue
                renderLayer(canvas, sDb, iDb, layerName, tileBounds, zoom)
            }
            return bitmap
        } catch (e: Throwable) {
            Log.w(TAG, "Tile rendering interrupted ($zoom/$x/$y): ${e.message}")
            return bitmap
        }
    }

    private fun renderLayer(
        canvas: Canvas,
        sDb: SQLiteDatabase,
        iDb: SQLiteDatabase,
        layerName: String,
        bounds: GeoBoundingBox,
        zoom: Int
    ) {
        val segments = GeoPackageIndexer.querySpatialSegments(
            iDb,
            layerName,
            bounds.minLon,
            bounds.maxLon,
            bounds.minLat,
            bounds.maxLat
        )
        if (segments.isEmpty()) return

        // Batch query feature data
        val features = mutableListOf<GeoFeature>()
        val uncachedSegments = mutableListOf<com.example.map.vector.SpatialSegment>()
        for (seg in segments) {
            val cacheKey = if (seg.pointEnd >= 0) "$layerName:${seg.fid}:${seg.pointStart}:${seg.pointEnd}" else "$layerName:${seg.fid}"
            val cached = featureCache.get(cacheKey)
            if (cached != null) {
                if (GeoPackageStyle.isFeatureVisibleAtZoom(layerName, cached.attributes, zoom)) {
                    features.add(cached)
                }
            } else {
                uncachedSegments.add(seg)
            }
        }

        if (uncachedSegments.isNotEmpty()) {
            val zoomFilter = GeoPackageStyle.sqlVisibilityFilter(layerName, zoom)
            val segmentsByFid = uncachedSegments.groupBy { it.fid }
            val uniqueFids = segmentsByFid.keys.toList()

            uniqueFids.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val whereClause = if (zoomFilter != null) {
                    "rowid IN ($placeholders) AND ${zoomFilter.first}"
                } else {
                    "rowid IN ($placeholders)"
                }
                val args = (chunk.map { it.toString() } + (zoomFilter?.second ?: emptyList())).toTypedArray()
                try {
                    sDb.rawQuery(
                        "SELECT rowid, geom, fclass, name FROM \"$layerName\" WHERE $whereClause",
                        args
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val fid = cursor.getLong(0)
                            val geomBytes = cursor.getBlob(1)
                            val fclass = cursor.getString(2) ?: ""
                            val name = cursor.getString(3) ?: ""
                            val attrs = mapOf("fclass" to fclass, "name" to name)

                            if (geomBytes != null) {
                                val targetSegments = segmentsByFid[fid] ?: emptyList()
                                for (seg in targetSegments) {
                                    val parsed = if (seg.pointEnd >= 0) {
                                        GeoPackageGeometryParser.parsePointRange(
                                            fid = fid,
                                            geomBytes = geomBytes,
                                            pointStart = seg.pointStart,
                                            pointEnd = seg.pointEnd,
                                            attributes = attrs
                                        )
                                    } else {
                                        GeoPackageGeometryParser.parse(fid, geomBytes, attrs)
                                    }

                                    if (parsed != null) {
                                        val key = if (seg.pointEnd >= 0) "$layerName:$fid:${seg.pointStart}:${seg.pointEnd}" else "$layerName:$fid"
                                        featureCache.put(key, parsed)
                                        if (GeoPackageStyle.isFeatureVisibleAtZoom(layerName, attrs, zoom)) {
                                            features.add(parsed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        if (features.isEmpty()) return

        // Draw Polygons (batched by fclass to minimize draw calls)
        if (layerName.endsWith("_a_free")) {
            val polygonsByFclass = features
                .filter { it.geometryType == "POLYGON" || it.geometryType == "MULTIPOLYGON" }
                .groupBy { (it.attributes["fclass"] as? String) ?: "" }

            for ((fclass, fList) in polygonsByFclass) {
                val paint = GeoPackageStyle.getPolygonPaint(layerName, fclass)
                val path = Path()
                for (f in fList) {
                    for (ring in f.rings) {
                        if (ring.isEmpty()) continue
                        var first = true
                        for (pt in ring) {
                            val px = lonToPixel(pt.lon, bounds.minLon, bounds.maxLon)
                            val py = latToPixel(pt.lat, bounds.minLat, bounds.maxLat)
                            if (first) {
                                path.moveTo(px, py)
                                first = false
                            } else {
                                path.lineTo(px, py)
                            }
                        }
                        path.close()
                    }
                }
                canvas.drawPath(path, paint)
            }
        }

        // Draw Lines (batched by fclass)
        val lineFeatures = features.filter { it.geometryType == "LINESTRING" || it.geometryType == "MULTILINESTRING" }
        if (lineFeatures.isNotEmpty()) {
            val linesByFclass = lineFeatures.groupBy { (it.attributes["fclass"] as? String) ?: "" }

            // Road casing first
            if (layerName == "gis_osm_roads_free") {
                for ((fclass, fList) in linesByFclass) {
                    val casingPaint = GeoPackageStyle.getLinePaint(layerName, fclass, zoom, isCasing = true)
                    val casingPath = Path()
                    buildLinesPath(casingPath, fList, bounds)
                    canvas.drawPath(casingPath, casingPaint)
                }
            }

            // Main stroke
            for ((fclass, fList) in linesByFclass) {
                val paint = GeoPackageStyle.getLinePaint(layerName, fclass, zoom, isCasing = false)
                val path = Path()
                buildLinesPath(path, fList, bounds)
                canvas.drawPath(path, paint)
            }
        }

        // Draw Points (POIs and Places)
        val pointFeatures = features.filter { it.geometryType == "POINT" || it.geometryType == "MULTIPOINT" }
        if (pointFeatures.isNotEmpty()) {
            for (f in pointFeatures) {
                val fclass = (f.attributes["fclass"] as? String) ?: ""
                val name = (f.attributes["name"] as? String) ?: ""
                val paint = GeoPackageStyle.getPointPaint(layerName, fclass)

                for (ring in f.rings) {
                    for (pt in ring) {
                        val px = lonToPixel(pt.lon, bounds.minLon, bounds.maxLon)
                        val py = latToPixel(pt.lat, bounds.minLat, bounds.maxLat)
                        val radius = if (layerName == "gis_osm_places_free") 4f else 3f
                        canvas.drawCircle(px, py, radius, paint)

                        if (name.isNotBlank() && zoom >= 11) {
                            val fontSize = if (layerName == "gis_osm_places_free") 12f else 10f
                            val haloPaint = GeoPackageStyle.getTextPaint(fontSize, isHalo = true)
                            val textPaint = GeoPackageStyle.getTextPaint(fontSize, isHalo = false)
                            canvas.drawText(name, px, py - radius - 3f, haloPaint)
                            canvas.drawText(name, px, py - radius - 3f, textPaint)
                        }
                    }
                }
            }
        }
    }

    private fun buildLinesPath(path: Path, features: List<GeoFeature>, bounds: GeoBoundingBox) {
        for (f in features) {
            for (line in f.rings) {
                if (line.size < 2) continue
                var first = true
                for (pt in line) {
                    val px = lonToPixel(pt.lon, bounds.minLon, bounds.maxLon)
                    val py = latToPixel(pt.lat, bounds.minLat, bounds.maxLat)
                    if (first) {
                        path.moveTo(px, py)
                        first = false
                    } else {
                        path.lineTo(px, py)
                    }
                }
            }
        }
    }

    private fun lonToPixel(lon: Double, minLon: Double, maxLon: Double): Float {
        val span = maxLon - minLon
        if (span <= 0) return 0f
        return ((lon - minLon) / span * tileSize).toFloat()
    }

    private fun latToPixel(lat: Double, minLat: Double, maxLat: Double): Float {
        val mercY = ln(tan(Math.toRadians(lat) / 2.0 + PI / 4.0))
        val mercMinY = ln(tan(Math.toRadians(minLat) / 2.0 + PI / 4.0))
        val mercMaxY = ln(tan(Math.toRadians(maxLat) / 2.0 + PI / 4.0))
        val span = mercMaxY - mercMinY
        if (span <= 0) return 0f
        return (tileSize - ((mercY - mercMinY) / span * tileSize)).toFloat()
    }

    private fun getTileGeoBounds(zoom: Int, x: Int, y: Int): GeoBoundingBox {
        val n = 1 shl zoom
        val minLon = x.toDouble() / n * 360.0 - 180.0
        val maxLon = (x + 1).toDouble() / n * 360.0 - 180.0
        val maxLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y.toDouble() / n))))
        val minLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * (y + 1).toDouble() / n))))
        return GeoBoundingBox(minLon = minLon, minLat = minLat, maxLon = maxLon, maxLat = maxLat)
    }

    override fun close() {
        try { sourceDb?.close() } catch (_: Exception) {}
        try { indexDb?.close() } catch (_: Exception) {}
        sourceDb = null
        indexDb = null
        featureCache.evictAll()
    }

    companion object {
        private const val TAG = "GeoPackageTileSource"

        fun create(file: File): GeoPackageTileSource? {
            if (!file.exists() || file.length() < 1024) return null
            val indexFile = File(file.parentFile, "${file.nameWithoutExtension}.spatialindex")
            return GeoPackageTileSource(file, indexFile)
        }
    }
}
