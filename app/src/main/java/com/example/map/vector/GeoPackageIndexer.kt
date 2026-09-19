package com.example.map.vector

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SpatialSegment(
    val segmentId: Long,
    val fid: Long,
    val pointStart: Int = 0,
    val pointEnd: Int = -1
)

/**
 * Builds a dedicated SQLite R-Tree spatial index for GeoPackage feature layers.
 *
 * Runs once upon file import, creating a lightweight sidecar database
 * `<filename>.spatialindex` containing `rtree_<layer_name>` virtual tables and
 * `segments_<layer_name>` mapping tables for segmented linestrings.
 * This turns spatial viewport queries from O(N) full-table scans into O(log N) indexed lookups.
 */
object GeoPackageIndexer {

    private const val TAG = "GeoPackageIndexer"
    private const val BATCH_SIZE = 1000
    const val CURRENT_INDEX_SCHEMA_VERSION = 3

    /**
     * Checks if the SQLite spatial index has the expected schema version (v3 with segments table).
     */
    fun isIndexCompatible(indexDb: SQLiteDatabase): Boolean {
        return try {
            if (!indexDb.isOpen) return false
            indexDb.rawQuery("SELECT value FROM index_metadata WHERE key = 'version'", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0)?.toIntOrNull() == CURRENT_INDEX_SCHEMA_VERSION
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if the spatial index file exists and is compatible.
     */
    fun isIndexFileCompatible(indexFile: File): Boolean {
        if (!indexFile.exists() || indexFile.length() < 1024) return false
        return try {
            SQLiteDatabase.openDatabase(indexFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                isIndexCompatible(db)
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun buildSpatialIndex(
        sourceGpkg: File,
        onProgress: (layer: String, current: Int, total: Int) -> Unit = { _, _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val indexFile = File(sourceGpkg.parentFile, "${sourceGpkg.nameWithoutExtension}.spatialindex")
        if (indexFile.exists()) {
            indexFile.delete()
        }

        val indexDb = SQLiteDatabase.openOrCreateDatabase(indexFile, null)
        try {
            indexDb.execSQL("PRAGMA journal_mode = WAL")
            indexDb.execSQL("PRAGMA synchronous = NORMAL")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply PRAGMA optimizations: ${e.message}")
        }
        val sourceDb = SQLiteDatabase.openDatabase(sourceGpkg.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

        try {
            // Find geometry column mappings per layer from gpkg_geometry_columns
            val geomColumns = mutableMapOf<String, String>()
            try {
                sourceDb.rawQuery("SELECT table_name, column_name FROM gpkg_geometry_columns", null).use { cursor ->
                    val tblCol = cursor.getColumnIndex("table_name")
                    val geomCol = cursor.getColumnIndex("column_name")
                    while (cursor.moveToNext()) {
                        val tbl = cursor.getString(tblCol)
                        val col = cursor.getString(geomCol)
                        if (tbl != null && col != null) {
                            geomColumns[tbl] = col
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query gpkg_geometry_columns: ${e.message}")
            }

            // Create index metadata table
            indexDb.execSQL("CREATE TABLE IF NOT EXISTS index_metadata (key TEXT PRIMARY KEY, value TEXT)")
            indexDb.execSQL("CREATE TABLE IF NOT EXISTS indexed_layers (layer_name TEXT PRIMARY KEY, geom_column TEXT, count INTEGER)")

            // Query feature layers from gpkg_contents
            val featureLayers = mutableListOf<String>()
            sourceDb.rawQuery("SELECT table_name FROM gpkg_contents WHERE data_type = 'features'", null).use { cursor ->
                val tblCol = cursor.getColumnIndex("table_name")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(tblCol)
                    if (!name.isNullOrBlank()) {
                        featureLayers.add(name)
                    }
                }
            }

            var anyBtreeFallback = false
            for (layerName in featureLayers) {
                val geomCol = geomColumns[layerName] ?: "geom"
                var useRtree = true
                try {
                    indexDb.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS rtree_$layerName USING rtree(id, minX, maxX, minY, maxY)")
                } catch (e: Exception) {
                    Log.w(TAG, "R-Tree module unavailable (${e.message}), using B-Tree spatial table fallback for $layerName")
                    useRtree = false
                    anyBtreeFallback = true
                    indexDb.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS btree_$layerName (
                            id INTEGER PRIMARY KEY,
                            minX REAL,
                            maxX REAL,
                            minY REAL,
                            maxY REAL
                        )
                        """.trimIndent()
                    )
                    indexDb.execSQL("CREATE INDEX IF NOT EXISTS idx_${layerName}_spatial ON btree_$layerName(minX, maxX, minY, maxY)")
                }

                indexDb.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS segments_$layerName (
                        segment_id INTEGER PRIMARY KEY,
                        fid INTEGER,
                        point_start INTEGER,
                        point_end INTEGER
                    )
                    """.trimIndent()
                )

                var layerTotal = 0
                try {
                    sourceDb.rawQuery("SELECT COUNT(*) FROM \"$layerName\"", null).use { c ->
                        if (c.moveToFirst()) layerTotal = c.getInt(0)
                    }
                } catch (_: Exception) {}

                var processedCount = 0
                var segmentCounter = 1L
                indexDb.beginTransaction()
                try {
                    val insertSql = if (useRtree) {
                        "INSERT OR REPLACE INTO rtree_$layerName (id, minX, maxX, minY, maxY) VALUES (?, ?, ?, ?, ?)"
                    } else {
                        "INSERT OR REPLACE INTO btree_$layerName (id, minX, maxX, minY, maxY) VALUES (?, ?, ?, ?, ?)"
                    }
                    val insertStmt = indexDb.compileStatement(insertSql)
                    val insertSegSql = "INSERT OR REPLACE INTO segments_$layerName (segment_id, fid, point_start, point_end) VALUES (?, ?, ?, ?)"
                    val insertSegStmt = indexDb.compileStatement(insertSegSql)

                    sourceDb.rawQuery("SELECT rowid, \"$geomCol\" FROM \"$layerName\"", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val fid = cursor.getLong(0)
                            val geomBytes = cursor.getBlob(1)
                            if (geomBytes != null && geomBytes.size >= 8) {
                                val bboxes = GeoPackageGeometryParser.extractSegmentedBoundingBoxes(geomBytes, maxPointsPerSegment = 50)
                                for (item in bboxes) {
                                    val segId = segmentCounter++
                                    insertStmt.bindLong(1, segId)
                                    insertStmt.bindDouble(2, item.bbox.minX)
                                    insertStmt.bindDouble(3, item.bbox.maxX)
                                    insertStmt.bindDouble(4, item.bbox.minY)
                                    insertStmt.bindDouble(5, item.bbox.maxY)
                                    insertStmt.executeInsert()
                                    insertStmt.clearBindings()

                                    insertSegStmt.bindLong(1, segId)
                                    insertSegStmt.bindLong(2, fid)
                                    insertSegStmt.bindLong(3, item.pointStart.toLong())
                                    insertSegStmt.bindLong(4, item.pointEnd.toLong())
                                    insertSegStmt.executeInsert()
                                    insertSegStmt.clearBindings()
                                }
                            }

                            processedCount++
                            if (processedCount % BATCH_SIZE == 0) {
                                indexDb.setTransactionSuccessful()
                                indexDb.endTransaction()
                                onProgress(layerName, processedCount, layerTotal)
                                indexDb.beginTransaction()
                            }
                        }
                    }

                    insertStmt.close()
                    insertSegStmt.close()
                    indexDb.setTransactionSuccessful()
                } finally {
                    indexDb.endTransaction()
                }

                val indexType = if (useRtree) "rtree" else "btree"
                indexDb.execSQL(
                    "INSERT OR REPLACE INTO indexed_layers (layer_name, geom_column, count) VALUES (?, ?, ?)",
                    arrayOf(layerName, geomCol, processedCount.toString())
                )
                indexDb.execSQL(
                    "INSERT OR REPLACE INTO index_metadata (key, value) VALUES (?, ?)",
                    arrayOf("type_$layerName", indexType)
                )
                onProgress(layerName, processedCount, layerTotal)
            }

            if (anyBtreeFallback) {
                Log.w(TAG, "ВНИМАНИЕ: R-Tree недоступен на этом устройстве, использован менее эффективный B-Tree fallback. Запросы к карте будут медленнее.")
            } else {
                Log.i(TAG, "Используется высокопроизводительный пространственный индекс R-Tree с сегментацией длинных объектов.")
            }

            indexDb.execSQL("INSERT OR REPLACE INTO index_metadata (key, value) VALUES ('has_btree_fallback', ?)", arrayOf(anyBtreeFallback.toString()))
            indexDb.execSQL("INSERT OR REPLACE INTO index_metadata (key, value) VALUES ('version', '3')")
            indexDb.execSQL("INSERT OR REPLACE INTO index_metadata (key, value) VALUES ('source_file', ?)", arrayOf(sourceGpkg.name))

            Log.i(TAG, "Spatial index built successfully: ${indexFile.absolutePath} (${indexFile.length()} bytes)")
            indexFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed building spatial index: ${e.message}", e)
            try { indexDb.close() } catch (_: Exception) {}
            indexFile.delete()
            throw e
        } finally {
            try { sourceDb.close() } catch (_: Exception) {}
            try { indexDb.close() } catch (_: Exception) {}
        }
    }

    /**
     * Queries spatial segments intersecting the bounding box with exact pointStart / pointEnd limits.
     */
    fun querySpatialSegments(
        indexDb: SQLiteDatabase,
        layerName: String,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double
    ): List<SpatialSegment> {
        val result = mutableListOf<SpatialSegment>()
        val rtreeSql = """
            SELECT s.segment_id, s.fid, s.point_start, s.point_end
            FROM rtree_$layerName r
            JOIN segments_$layerName s ON r.id = s.segment_id
            WHERE r.minX <= ? AND r.maxX >= ? AND r.minY <= ? AND r.maxY >= ?
        """.trimIndent()
        val btreeSql = """
            SELECT s.segment_id, s.fid, s.point_start, s.point_end
            FROM btree_$layerName r
            JOIN segments_$layerName s ON r.id = s.segment_id
            WHERE r.minX <= ? AND r.maxX >= ? AND r.minY <= ? AND r.maxY >= ?
        """.trimIndent()

        try {
            indexDb.rawQuery(rtreeSql, arrayOf(maxX.toString(), minX.toString(), maxY.toString(), minY.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(
                        SpatialSegment(
                            segmentId = cursor.getLong(0),
                            fid = cursor.getLong(1),
                            pointStart = cursor.getInt(2),
                            pointEnd = cursor.getInt(3)
                        )
                    )
                }
            }
            return result
        } catch (_: Exception) {}

        try {
            indexDb.rawQuery(btreeSql, arrayOf(maxX.toString(), minX.toString(), maxY.toString(), minY.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(
                        SpatialSegment(
                            segmentId = cursor.getLong(0),
                            fid = cursor.getLong(1),
                            pointStart = cursor.getInt(2),
                            pointEnd = cursor.getInt(3)
                        )
                    )
                }
            }
            return result
        } catch (_: Exception) {}

        // Fallback for legacy indexes without segments table
        val fids = querySpatialIndex(indexDb, layerName, minX, maxX, minY, maxY)
        return fids.map { fid -> SpatialSegment(segmentId = fid, fid = fid, pointStart = 0, pointEnd = -1) }
    }

    /**
     * Spatial bounding box query that returns unique feature IDs.
     */
    fun querySpatialIndex(
        indexDb: SQLiteDatabase,
        layerName: String,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double
    ): List<Long> {
        val segments = querySpatialSegments(indexDb, layerName, minX, maxX, minY, maxY)
        if (segments.isNotEmpty()) {
            val fids = LinkedHashSet<Long>()
            for (seg in segments) {
                fids.add(seg.fid)
            }
            return fids.toList()
        }

        // Legacy direct query if segments query returned empty but index had raw IDs
        val result = LinkedHashSet<Long>()
        val rtreeSql = "SELECT id FROM rtree_$layerName WHERE minX <= ? AND maxX >= ? AND minY <= ? AND maxY >= ?"
        val btreeSql = "SELECT id FROM btree_$layerName WHERE minX <= ? AND maxX >= ? AND minY <= ? AND maxY >= ?"

        try {
            indexDb.rawQuery(rtreeSql, arrayOf(maxX.toString(), minX.toString(), maxY.toString(), minY.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val rawId = cursor.getLong(0)
                    val fid = if (rawId > 65535L) (rawId ushr 16) else rawId
                    result.add(fid)
                }
            }
            return result.toList()
        } catch (_: Exception) {}

        try {
            indexDb.rawQuery(btreeSql, arrayOf(maxX.toString(), minX.toString(), maxY.toString(), minY.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val rawId = cursor.getLong(0)
                    val fid = if (rawId > 65535L) (rawId ushr 16) else rawId
                    result.add(fid)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spatial index query failed for layer $layerName: ${e.message}")
        }
        return result.toList()
    }

    /**
     * Checks whether any layer in the spatial index is using the slower B-Tree fallback.
     */
    fun isUsingBtreeFallback(indexDb: SQLiteDatabase): Boolean {
        return try {
            indexDb.rawQuery("SELECT value FROM index_metadata WHERE key = 'has_btree_fallback'", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0) == "true"
                } else false
            }
        } catch (_: Exception) {
            false
        }
    }
}
