package com.example.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    private val baseCacheDir = File(context.cacheDir, "map_tiles")
    private var offlineZipFile: ZipFile? = null
    private var activeMbtilesSource: MbtilesTileSource? = null

    val availableOnlineSources: List<TileSource> = TileSource.ALL

    init {
        if (!baseCacheDir.exists()) {
            baseCacheDir.mkdirs()
        }
    }

    /**
     * Attaches an offline .orntpack or .zip tile package.
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
                setRequestProperty("User-Agent", "OrientirNavApp/1.0 (Android; Offline GIS Engine)")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { stream ->
                    val bytes = stream.readBytes()
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
        return bmp
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
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
