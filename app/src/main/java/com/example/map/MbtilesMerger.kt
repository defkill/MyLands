package com.example.map

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class MergeResult {
    data class Success(val file: File, val tileSourceCount: Int) : MergeResult()
    data class Error(val message: String) : MergeResult()
}

/**
 * Merges multiple MBTiles SQLite databases into a single MBTiles file.
 *
 * Uses SQLite ATTACH DATABASE for fast zero-copy SQL streaming (INSERT OR REPLACE),
 * ensuring large multi-gigabyte map files are merged efficiently without excessive memory allocations.
 */
object MbtilesMerger {

    private const val TAG = "MbtilesMerger"

    /**
     * Verifies SQLite database file integrity using PRAGMA quick_check.
     */
    fun verifySqliteIntegrity(file: File): Boolean {
        if (!file.exists() || file.length() < 100) return false
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            val isOk = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val result = cursor.getString(0)
                    result.equals("ok", ignoreCase = true)
                } else {
                    false
                }
            }
            isOk
        } catch (e: Throwable) {
            Log.w(TAG, "Integrity check failed for ${file.name}: ${e.message}")
            false
        } finally {
            try {
                db?.close()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Merges [sourceFiles] into a new file at [outputFile].
     *
     * Tiles are matched by (zoom_level, tile_column, tile_row). When the same tile
     * coordinate exists in multiple sources, the LAST source in the list wins
     * (later files overwrite earlier ones, giving the caller full priority control).
     */
    suspend fun merge(
        sourceFiles: List<File>,
        outputFile: File,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): MergeResult = withContext(Dispatchers.IO) {
        val existingSources = sourceFiles.filter { it.exists() && it.length() > 0 }
        if (existingSources.isEmpty()) {
            return@withContext MergeResult.Error("Нет файлов для слияния")
        }

        // Verify integrity of all source files
        for (src in existingSources) {
            if (!verifySqliteIntegrity(src)) {
                return@withContext MergeResult.Error("Файл '${src.name}' поврежден или не является корректной базой SQLite.")
            }
        }

        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.parentFile?.mkdirs()

        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(outputFile, null)

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

            val formats = mutableSetOf<String>()
            var mergedMinZoom: Int? = null
            var mergedMaxZoom: Int? = null
            var mergedBounds: String? = null
            var mergedCenter: String? = null

            existingSources.forEachIndexed { index, sourceFile ->
                val alias = "src$index"
                db.execSQL("ATTACH DATABASE ? AS $alias", arrayOf(sourceFile.absolutePath))
                try {
                    // Collect metadata from attached source
                    try {
                        db.rawQuery("SELECT name, value FROM $alias.metadata", null).use { cursor ->
                            val nameCol = cursor.getColumnIndex("name")
                            val valCol = cursor.getColumnIndex("value")
                            while (cursor.moveToNext()) {
                                val key = if (nameCol >= 0) cursor.getString(nameCol)?.lowercase() else null
                                val value = if (valCol >= 0) cursor.getString(valCol) else null
                                if (key != null && value != null) {
                                    when (key) {
                                        "format" -> formats.add(value.lowercase().trim())
                                        "minzoom" -> {
                                            val z = value.trim().toIntOrNull()
                                            if (z != null) {
                                                mergedMinZoom = minOf(mergedMinZoom ?: Int.MAX_VALUE, z)
                                            }
                                        }
                                        "maxzoom" -> {
                                            val z = value.trim().toIntOrNull()
                                            if (z != null) {
                                                mergedMaxZoom = maxOf(mergedMaxZoom ?: Int.MIN_VALUE, z)
                                            }
                                        }
                                        "bounds" -> if (mergedBounds == null) mergedBounds = value
                                        "center" -> if (mergedCenter == null) mergedCenter = value
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "No readable metadata table in ${sourceFile.name}: ${e.message}")
                    }

                    // Check if 'tiles' table exists in source
                    var hasTilesTable = false
                    try {
                        db.rawQuery(
                            "SELECT name FROM $alias.sqlite_master WHERE type IN ('table', 'view') AND name = 'tiles'",
                            null
                        ).use { c ->
                            hasTilesTable = c.count > 0
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed checking tiles table in ${sourceFile.name}: ${e.message}")
                    }

                    if (hasTilesTable) {
                        // INSERT OR REPLACE: later source in list overwrites earlier on matching coordinates
                        db.execSQL(
                            "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) " +
                            "SELECT zoom_level, tile_column, tile_row, tile_data FROM $alias.tiles"
                        )
                    }
                } finally {
                    try {
                        db.execSQL("DETACH DATABASE $alias")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed detaching $alias: ${e.message}")
                    }
                }

                onProgress(index + 1, existingSources.size)
            }

            // Validate format compatibility
            val hasVector = formats.any { it == "pbf" || it == "mvt" }
            val hasRaster = formats.any { it != "pbf" && it != "mvt" }

            if (hasVector && hasRaster) {
                db.close()
                db = null
                outputFile.delete()
                return@withContext MergeResult.Error(
                    "Нельзя слить растровые и векторные карты в один файл: обнаружены форматы ${formats.joinToString()}. " +
                    "Формат должен совпадать у всех выбранных источников."
                )
            }

            val finalFormat = formats.firstOrNull() ?: "png"
            db.execSQL("INSERT INTO metadata (name, value) VALUES ('format', ?)", arrayOf(finalFormat))
            db.execSQL("INSERT INTO metadata (name, value) VALUES ('minzoom', ?)", arrayOf((mergedMinZoom ?: 0).toString()))
            db.execSQL("INSERT INTO metadata (name, value) VALUES ('maxzoom', ?)", arrayOf((mergedMaxZoom ?: 18).toString()))
            db.execSQL("INSERT INTO metadata (name, value) VALUES ('name', ?)", arrayOf(outputFile.nameWithoutExtension))
            mergedBounds?.let { db.execSQL("INSERT INTO metadata (name, value) VALUES ('bounds', ?)", arrayOf(it)) }
            mergedCenter?.let { db.execSQL("INSERT INTO metadata (name, value) VALUES ('center', ?)", arrayOf(it)) }

            MergeResult.Success(outputFile, tileSourceCount = existingSources.size)
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed: ${e.message}", e)
            try {
                db?.close()
            } catch (_: Exception) {}
            db = null
            outputFile.delete()
            MergeResult.Error(e.localizedMessage ?: (e.message ?: "Ошибка слияния баз данных"))
        } finally {
            try {
                db?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Merges [sourceFiles] incrementally INTO an existing [targetFile] in-place.
     * If [targetFile] does not exist yet, creates it.
     */
    suspend fun mergeInto(
        targetFile: File,
        sourceFiles: List<File>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): MergeResult = withContext(Dispatchers.IO) {
        val existingSources = sourceFiles.filter { it.exists() && it.length() > 0 && it.absolutePath != targetFile.absolutePath }
        if (existingSources.isEmpty()) {
            return@withContext if (targetFile.exists()) {
                MergeResult.Success(targetFile, 0)
            } else {
                MergeResult.Error("Нет исходных файлов для слияния")
            }
        }

        if (!targetFile.exists()) {
            return@withContext merge(existingSources, targetFile, onProgress)
        }

        if (!verifySqliteIntegrity(targetFile)) {
            return@withContext MergeResult.Error("Целевой файл '${targetFile.name}' поврежден.")
        }

        for (src in existingSources) {
            if (!verifySqliteIntegrity(src)) {
                return@withContext MergeResult.Error("Исходный файл '${src.name}' поврежден.")
            }
        }

        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(targetFile, null)

            // Ensure tables exist
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tiles (
                    zoom_level INTEGER,
                    tile_column INTEGER,
                    tile_row INTEGER,
                    tile_data BLOB,
                    PRIMARY KEY (zoom_level, tile_column, tile_row)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")

            existingSources.forEachIndexed { index, sourceFile ->
                val alias = "inc$index"
                db.execSQL("ATTACH DATABASE ? AS $alias", arrayOf(sourceFile.absolutePath))
                try {
                    var hasTiles = false
                    try {
                        db.rawQuery(
                            "SELECT name FROM $alias.sqlite_master WHERE type IN ('table', 'view') AND name = 'tiles'",
                            null
                        ).use { c -> hasTiles = c.count > 0 }
                    } catch (_: Throwable) {}

                    if (hasTiles) {
                        db.execSQL(
                            "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) " +
                            "SELECT zoom_level, tile_column, tile_row, tile_data FROM $alias.tiles"
                        )
                    }
                } finally {
                    try {
                        db.execSQL("DETACH DATABASE $alias")
                    } catch (_: Throwable) {}
                }
                onProgress(index + 1, existingSources.size)
            }

            MergeResult.Success(targetFile, tileSourceCount = existingSources.size)
        } catch (e: Exception) {
            Log.e(TAG, "Incremental merge failed: ${e.message}", e)
            MergeResult.Error(e.localizedMessage ?: (e.message ?: "Ошибка инкрементального слияния"))
        } finally {
            try {
                db?.close()
            } catch (_: Exception) {}
        }
    }
}

