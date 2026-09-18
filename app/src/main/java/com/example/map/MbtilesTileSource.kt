package com.example.map

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.model.GeoPoint
import java.io.Closeable
import java.io.File

/**
 * Metadata descriptor parsed from MBTiles SQLite "metadata" table.
 */
data class MbtilesMetadata(
    val name: String,
    val format: String = "png",
    val minZoom: Int = 0,
    val maxZoom: Int = 18,
    val bounds: String? = null,
    val center: String? = null,
    val description: String? = null
) {
    val isVector: Boolean
        get() = format.lowercase().let { it == "pbf" || it == "mvt" }
}

/**
 * Direct SQLite-based TileSource adapter for standard Mapbox/OSGeo .mbtiles files.
 *
 * Supports both raster (PNG, JPG, WEBP) and vector (Mapbox Vector Tile PBF / Shortbread) datasets.
 * Implements direct tile streaming without unzipping/unpacking, overzoom up to maxZoom + 4,
 * and high-performance tactical rasterization for vector tiles.
 *
 * Tile coordinates in MBTiles use the TMS schema where tile_row is inverted:
 * tile_row = (1 shl zoom_level) - 1 - y_xyz
 */
class MbtilesTileSource(
    val file: File,
    val metadata: MbtilesMetadata
) : TileSource(
    id = "mbtiles_${file.nameWithoutExtension.lowercase().replace("[^a-z0-9_]".toRegex(), "_")}",
    name = metadata.name.ifBlank { file.nameWithoutExtension },
    type = MapTileType.MBTILES,
    urlTemplate = "",
    maxZoom = if (metadata.isVector) (metadata.maxZoom + 4).coerceAtMost(22) else metadata.maxZoom,
    minZoom = metadata.minZoom
), Closeable {

    private var db: SQLiteDatabase? = null
    private val vectorRasterizer by lazy { com.example.map.vector.VectorTileRasterizer(512) }
    private var hasLoggedVectorDebug = false

    init {
        openDb()
    }

    private fun openDb() {
        if (db == null || !db!!.isOpen) {
            try {
                db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: Exception) {
                Log.e("MbtilesTileSource", "Could not open SQLite database at ${file.absolutePath}", e)
            }
        }
    }

    /**
     * Reads a tile bitmap directly from MBTiles SQLite table 'tiles'.
     *
     * Supports both raster images and vector MVT/PBF tiles with automatic
     * sub-pixel overzoom and decompression.
     *
     * TMS inversion: tile_row = (2^zoom - 1) - y
     */
    fun getTileBitmap(zoom: Int, x: Int, y: Int): Bitmap? {
        openDb()
        val database = db ?: return null
        if (!database.isOpen) return null

        val isVector = metadata.isVector
        val nativeMaxZoom = metadata.maxZoom

        // Calculate overzoom parameters if zoom level exceeds native dataset maxZoom
        val targetZoom: Int
        val targetX: Int
        val targetY: Int
        val offsetX: Int
        val offsetY: Int

        if (isVector && zoom > nativeMaxZoom) {
            val zoomDiff = zoom - nativeMaxZoom
            if (zoomDiff > 4) return null // Limit overzoom to maxZoom + 4
            targetZoom = nativeMaxZoom
            targetX = x ushr zoomDiff
            targetY = y ushr zoomDiff
            offsetX = x - (targetX shl zoomDiff)
            offsetY = y - (targetY shl zoomDiff)
        } else {
            targetZoom = zoom
            targetX = x
            targetY = y
            offsetX = 0
            offsetY = 0
        }

        // Invert tile_row according to TMS standard
        val tmsRow = (1 shl targetZoom) - 1 - targetY

        return try {
            database.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1",
                arrayOf(targetZoom.toString(), targetX.toString(), tmsRow.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val blob = cursor.getBlob(0)
                    if (blob != null && blob.isNotEmpty()) {
                        if (isVector) {
                            val vectorTile = com.example.map.vector.MvtParser.parse(blob)

                            // Diagnostic introspection for Stage 1 (Logcat Tag: VectorTileDebug)
                            if (!hasLoggedVectorDebug && vectorTile.layers.isNotEmpty()) {
                                hasLoggedVectorDebug = true
                                logVectorSchemaIntrospection(targetZoom, targetX, targetY, vectorTile)
                            }

                            vectorRasterizer.rasterize(
                                tile = vectorTile,
                                zoom = zoom,
                                parentZoom = targetZoom,
                                offsetX = offsetX,
                                offsetY = offsetY
                            )
                        } else {
                            BitmapFactory.decodeByteArray(blob, 0, blob.size)
                        }
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.w("MbtilesTileSource", "Error querying tile Z=$zoom X=$x Y=$y (targetZ=$targetZoom targetX=$targetX TMS=$tmsRow): ${e.message}")
            null
        }
    }

    private fun logVectorSchemaIntrospection(
        zoom: Int,
        x: Int,
        y: Int,
        tile: com.example.map.vector.VectorTile
    ) {
        val sb = StringBuilder()
        sb.appendLine("=== VectorTileDebug: Introspection for Tile Z=$zoom X=$x Y=$y ===")
        sb.appendLine("Total Layers: ${tile.layers.size}")
        for (layer in tile.layers) {
            val sampleFeature = layer.features.firstOrNull()
            val sampleKeys = sampleFeature?.attributes?.keys?.take(10)?.joinToString(", ") ?: "none"
            val sampleVals = sampleFeature?.attributes?.entries?.take(5)?.joinToString("; ") { "${it.key}=${it.value}" } ?: "none"
            sb.appendLine(
                " • Layer: '${layer.name}' (version=${layer.version}, extent=${layer.extent}, features=${layer.features.size}, " +
                        "sampleGeom=${sampleFeature?.geometryType}, keys=[$sampleKeys], sampleValues=[$sampleVals])"
            )
        }
        sb.appendLine("==================================================================")
        Log.i("VectorTileDebug", sb.toString())
    }

    /**
     * Parses the center coordinate (lon, lat, optional zoom) if specified in metadata.
     */
    fun getCenterPoint(): Pair<GeoPoint, Double?>? {
        val centerStr = metadata.center ?: return null
        val parts = centerStr.split(",")
        if (parts.size >= 2) {
            val lon = parts[0].trim().toDoubleOrNull() ?: return null
            val lat = parts[1].trim().toDoubleOrNull() ?: return null
            val zoom = if (parts.size >= 3) parts[2].trim().toDoubleOrNull() else null
            return Pair(GeoPoint(lat, lon), zoom)
        }
        return null
    }

    /**
     * Parses bounds (minLon, minLat, maxLon, maxLat) if specified in metadata.
     */
    fun getBounds(): List<Double>? {
        val boundsStr = metadata.bounds ?: return null
        val parts = boundsStr.split(",")
        if (parts.size == 4) {
            val vals = parts.mapNotNull { it.trim().toDoubleOrNull() }
            if (vals.size == 4) return vals
        }
        return null
    }

    override fun close() {
        try {
            if (db?.isOpen == true) {
                db?.close()
            }
        } catch (_: Exception) {}
        db = null
    }

    companion object {
        /**
         * Validates and creates an MbtilesTileSource instance from a file.
         * Returns null if file is not a valid MBTiles SQLite database.
         */
        fun create(file: File): MbtilesTileSource? {
            if (!file.exists() || file.length() == 0L) return null

            var database: SQLiteDatabase? = null
            return try {
                database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                val metaMap = mutableMapOf<String, String>()

                // Read metadata table
                try {
                    database.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
                        val nameIndex = cursor.getColumnIndex("name")
                        val valueIndex = cursor.getColumnIndex("value")
                        while (cursor.moveToNext()) {
                            if (nameIndex >= 0 && valueIndex >= 0) {
                                val key = cursor.getString(nameIndex)?.lowercase()
                                val value = cursor.getString(valueIndex)
                                if (key != null && value != null) {
                                    metaMap[key] = value
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MbtilesTileSource", "No standard 'metadata' table found in ${file.name}: ${e.message}")
                }

                // Verify that 'tiles' table or view exists
                var tilesTableFound = false
                try {
                    database.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type IN ('table', 'view') AND name = 'tiles'",
                        null
                    ).use { cursor ->
                        tilesTableFound = cursor.count > 0
                    }
                } catch (e: Exception) {
                    Log.w("MbtilesTileSource", "Error checking for 'tiles' table: ${e.message}")
                }

                if (!tilesTableFound) {
                    Log.w("MbtilesTileSource", "Database does not contain required 'tiles' table/view")
                    return null
                }

                val name = metaMap["name"]?.ifBlank { file.nameWithoutExtension } ?: file.nameWithoutExtension
                val format = metaMap["format"] ?: "png"
                val minZoom = metaMap["minzoom"]?.toIntOrNull() ?: 0
                val maxZoom = metaMap["maxzoom"]?.toIntOrNull() ?: 18
                val bounds = metaMap["bounds"]
                val center = metaMap["center"]
                val desc = metaMap["description"]

                val metadata = MbtilesMetadata(
                    name = name,
                    format = format,
                    minZoom = minZoom.coerceIn(0, 24),
                    maxZoom = maxZoom.coerceIn(0, 24),
                    bounds = bounds,
                    center = center,
                    description = desc
                )

                database.close()
                database = null

                MbtilesTileSource(file, metadata)
            } catch (e: Exception) {
                Log.e("MbtilesTileSource", "Failed to parse MBTiles: ${e.message}")
                null
            } finally {
                try {
                    database?.close()
                } catch (_: Exception) {}
            }
        }
    }
}
