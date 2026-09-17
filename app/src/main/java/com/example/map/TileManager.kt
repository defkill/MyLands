package com.example.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * High-performance TileManager with two-tier cache (Memory LRU + Local Disk)
 * and support for SAS.Planet XYZ tile caches and .orntpack / .zip offline archives.
 */
class TileManager(private val context: Context) {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8 // 1/8th of available memory

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val fallbackCache = LruCache<String, Bitmap>(64)

    companion object {
        /**
         * Must identify this specific application. OSM blocks generic/faked agents.
         * Change the URL below to your own project page if you fork this.
         */
        const val TILE_USER_AGENT = "MyLands/1.0 (+https://github.com/defkill/MyLands)"
        const val TILE_REFERER = "https://github.com/defkill/MyLands"
        private const val TAG = "TileManager"
    }

    /**
     * Tiles live in the app's files directory, NOT in cacheDir.
     *
     * Android is free to wipe cacheDir whenever storage runs low. Losing downloaded maps that
     * way would be discovered in the field, with no connection to re-download them — the exact
     * situation this app exists for. filesDir is only removed when the user uninstalls or
     * clears app data.
     */
    val baseCacheDir = File(context.filesDir, "map_tiles")

    /** Previous location; kept only to move existing tiles over once. */
    private val legacyCacheDir = File(context.cacheDir, "map_tiles")

    private var offlineZipFile: ZipFile? = null
    private var activeMbtilesSource: MbtilesTileSource? = null

    val availableOnlineSources: List<TileSource> = TileSource.ALL

    init {
        if (!baseCacheDir.exists()) {
            baseCacheDir.mkdirs()
        }
        migrateLegacyCacheIfNeeded()
    }

    /**
     * Moves tiles downloaded by earlier versions out of cacheDir, so nobody loses maps they
     * already have. Runs once: the old directory is removed afterwards.
     */
    private fun migrateLegacyCacheIfNeeded() {
        try {
            if (!legacyCacheDir.exists()) return

            legacyCacheDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val target = File(baseCacheDir, file.relativeTo(legacyCacheDir).path)
                    if (!target.exists()) {
                        target.parentFile?.mkdirs()
                        if (!file.renameTo(target)) {
                            file.copyTo(target, overwrite = false)
                        }
                    }
                }
            legacyCacheDir.deleteRecursively()
            Log.d(TAG, "Migrated tile storage from cacheDir to filesDir")
        } catch (e: Exception) {
            Log.e(TAG, "Tile storage migration failed", e)
        }
    }

    /**
     * Attaches an offline .orntpack or .zip tile package read-only.
     *
     * Kept for compatibility; [importOfflinePackage] is preferred because merging lets the
     * imported maps grow with newly downloaded tiles.
     */
    fun attachOfflinePackage(file: File): Boolean {
        return try {
            offlineZipFile?.close()
            offlineZipFile = ZipFile(file)
            true
        } catch (e: Exception) {
            false
        }
    }

    data class ImportProgress(val done: Int, val total: Int)

    /**
     * Merges an .orntpack into local tile storage instead of reading it as a sealed archive.
     *
     * Why merge rather than attach: an attached package is read-only and separate from tiles
     * fetched online, so "pack the cache" produced a file WITHOUT the imported content and the
     * map could never grow. After merging, everything lives in one place — imported tiles and
     * newly downloaded ones alike — so each export is a superset of what came before and a
     * package can be passed around and enlarged by each person in turn.
     *
     * Entry paths keep their {source}/{z}/{x}/{y} layout, so tiles land back in the layer they
     * came from and layer switching keeps working untouched.
     *
     * Existing tiles are never overwritten, which makes re-importing the same file cheap.
     *
     * @return number of tiles added.
     */
    suspend fun importOfflinePackage(
        file: File,
        onProgress: ((ImportProgress) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        var added = 0
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList().filter { entry ->
                !entry.isDirectory &&
                    (entry.name.endsWith(".png") || entry.name.endsWith(".jpg"))
            }
            val total = entries.size

            entries.forEachIndexed { index, entry ->
                // Reject paths that would escape the tile directory.
                val safeName = entry.name.replace("\\", "/").trimStart('/')
                if (safeName.contains("..")) return@forEachIndexed

                val target = File(baseCacheDir, safeName)
                if (!target.exists()) {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    added++
                }

                if (index % 200 == 0 || index == total - 1) {
                    onProgress?.invoke(ImportProgress(index + 1, total))
                }
            }
        }
        Log.d(TAG, "Imported $added new tiles from ${file.name}")
        clearMemoryCache()
        added
    }

    /**
     * Attaches an offline .mbtiles file (direct SQLite reader).
     */
    fun attachMbtiles(file: File): MbtilesTileSource? {
        return try {
            val source = MbtilesTileSource.create(file) ?: return null
            activeMbtilesSource?.close()
            activeMbtilesSource = source
            clearMemoryCache()
            source
        } catch (e: Exception) {
            null
        }
    }

    fun getActiveMbtilesSource(): MbtilesTileSource? = activeMbtilesSource

    fun hasOfflinePackage(): Boolean = offlineZipFile != null

    fun getCachedBitmap(sourceId: String, tile: TileCoordinate): Bitmap? {
        val cacheKey = "$sourceId/${tile.key}"
        return memoryCache.get(cacheKey)
    }

    /**
     * Loads tile bitmap asynchronously:
     * Memory -> MBTiles (direct SQLite) -> Offline Package (.orntpack) -> Disk Cache -> Network.
     */
    suspend fun getTileBitmap(source: TileSource, tile: TileCoordinate): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${source.id}/${tile.key}"

        // 1. Memory Cache
        memoryCache.get(cacheKey)?.let { return@withContext it }

        // 2. Direct MBTiles SQLite Source
        if (source is MbtilesTileSource) {
            val bmp = source.getTileBitmap(tile.zoom, tile.x, tile.y)
            if (bmp != null) {
                memoryCache.put(cacheKey, bmp)
                return@withContext bmp
            }
            return@withContext null
        } else if (activeMbtilesSource != null && source.id == activeMbtilesSource?.id) {
            val bmp = activeMbtilesSource?.getTileBitmap(tile.zoom, tile.x, tile.y)
            if (bmp != null) {
                memoryCache.put(cacheKey, bmp)
                return@withContext bmp
            }
            return@withContext null
        } else if (source.type == MapTileType.MBTILES) {
            // Source is MBTILES but no matching instance found
            return@withContext null
        }

        // 3. Offline Package (.orntpack or .zip)
        offlineZipFile?.let { zip ->
            val pathsToTry = listOf(
                "${tile.zoom}/${tile.x}/${tile.y}.png",
                "${tile.zoom}/${tile.x}/${tile.y}.jpg",
                "${source.id}/${tile.zoom}/${tile.x}/${tile.y}.png",
                "tiles/${tile.zoom}/${tile.x}/${tile.y}.png"
            )
            for (path in pathsToTry) {
                val entry = zip.getEntry(path)
                if (entry != null) {
                    try {
                        zip.getInputStream(entry).use { stream ->
                            val bmp = BitmapFactory.decodeStream(stream)
                            if (bmp != null) {
                                memoryCache.put(cacheKey, bmp)
                                return@withContext bmp
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 4. Disk Cache
        val diskFile = File(baseCacheDir, "${source.id}/${tile.zoom}/${tile.x}/${tile.y}.png")
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bmp != null) {
                    memoryCache.put(cacheKey, bmp)
                    return@withContext bmp
                }
            } catch (_: Exception) {}
        }

        // 5. Remote Network Download
        val urlString = source.getTileUrl(tile)
        if (urlString.isBlank()) return@withContext null
        try {
            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                // Tile servers (notably OSM) require a specific, identifying User-Agent and a
                // Referer. Generic or missing headers are actively blocked with a 403 "Access
                // blocked" tile. Keep the contact URL real so operators can reach the author.
                setRequestProperty("User-Agent", TILE_USER_AGENT)
                setRequestProperty("Referer", TILE_REFERER)
            }

            // OSM serves "Access blocked" placeholder tiles with HTTP 200 plus an x-blocked
            // header. Without this check the placeholder image gets decoded and written to the
            // disk cache, so the map stays covered in block notices even after the cause is fixed.
            val blockedHeader = connection.getHeaderField("x-blocked")
            if (!blockedHeader.isNullOrBlank()) {
                return@withContext null
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { stream ->
                    val bytes = stream.readBytes()

                    // Tiny payloads are also never real 256x256 map tiles.
                    if (bytes.size < 1024) {
                        return@withContext null
                    }

                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        memoryCache.put(cacheKey, bmp)
                        // Save to disk asynchronously
                        try {
                            diskFile.parentFile?.mkdirs()
                            FileOutputStream(diskFile).use { it.write(bytes) }
                        } catch (_: Exception) {}
                        return@withContext bmp
                    }
                }
            }
        } catch (_: Exception) {
            // Network unavailable (offline)
        }

        null
    }

    /**
     * Creates a fallback grid tile with coordinates when completely offline and no cached tile.
     */
    fun createGridFallbackTile(tile: TileCoordinate): Bitmap {
        val cacheKey = "${tile.zoom}/${tile.x}/${tile.y}"
        fallbackCache.get(cacheKey)?.let { return it }

        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(24, 28, 32)) // Tactical dark

        val borderPaint = Paint().apply {
            color = Color.rgb(45, 55, 65)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(0f, 0f, 256f, 256f, borderPaint)

        val textPaint = Paint().apply {
            color = Color.rgb(120, 144, 156)
            textSize = 20f
            isAntiAlias = true
        }
        canvas.drawText("Z${tile.zoom}: ${tile.x}/${tile.y}", 20f, 40f, textPaint)
        fallbackCache.put(cacheKey, bmp)
        return bmp
    }

    /**
     * Wipes the on-disk tile cache for one source (or all sources when [sourceId] is null).
     * Needed after a provider starts returning "blocked"/placeholder tiles, because those were
     * previously written to disk and would otherwise be served from cache forever.
     */
    /**
     * Number of cached tiles and their total size on disk, so the UI can tell the user what
     * "pack the cache" will actually produce instead of leaving them guessing.
     */
    fun getCacheStats(): Pair<Int, Long> {
        return try {
            if (!baseCacheDir.exists()) return 0 to 0L
            var count = 0
            var bytes = 0L
            baseCacheDir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".png") || it.name.endsWith(".jpg")) }
                .forEach { count++; bytes += it.length() }
            count to bytes
        } catch (_: Exception) {
            0 to 0L
        }
    }

    fun clearDiskCache(sourceId: String? = null) {
        try {
            val target = if (sourceId == null) baseCacheDir else File(baseCacheDir, sourceId)
            if (target.exists()) {
                target.deleteRecursively()
            }
            baseCacheDir.mkdirs()
            clearMemoryCache()
        } catch (_: Exception) {}
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
        fallbackCache.evictAll()
    }

    /**
     * Packs all cached tiles on disk into a .orntpack (ZIP-based) container for sharing.
     * Returns the count of tiles successfully archived.
     */
    suspend fun packCacheToOrntpack(
        outputFile: File,
        sourceId: String? = null
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        val targetDir = if (sourceId != null) File(baseCacheDir, sourceId) else baseCacheDir
        if (!targetDir.exists()) return@withContext 0

        val zipOut = java.util.zip.ZipOutputStream(java.io.FileOutputStream(outputFile))
        try {
            targetDir.walkTopDown().filter { it.isFile && (it.name.endsWith(".png") || it.name.endsWith(".jpg")) }.forEach { file ->
                // Store path in standard format: {zoom}/{x}/{y}.png or {source}/{zoom}/{x}/{y}.png
                val relativePath = file.relativeTo(targetDir).path
                val entry = java.util.zip.ZipEntry(relativePath)
                zipOut.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
                count++
            }
        } finally {
            zipOut.close()
        }
        count
    }
}
