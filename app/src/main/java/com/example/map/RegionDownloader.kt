package com.example.map

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Geographic rectangle selected for download.
 */
data class RegionBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

data class DownloadEstimate(
    val tileCount: Int,
    val approxBytes: Long
) {
    val approxMegabytes: Double get() = approxBytes / 1024.0 / 1024.0
}

data class DownloadProgress(
    val done: Int,
    val total: Int,
    val downloaded: Int,
    val skipped: Int,
    val failed: Int,
    val currentLayer: String
)

/**
 * Downloads a chosen map region tile by tile, deliberately slowly.
 *
 * Tile providers ban clients that pull large areas quickly — this project has already been
 * served "Access blocked" placeholders by OSM for exactly that. So every request is spaced out,
 * consecutive failures abort the job, and already-stored tiles are skipped rather than re-fetched.
 * Slower, but it finishes instead of getting the device blocked halfway through.
 */
object RegionDownloader {

    private const val TAG = "RegionDownloader"

    /** Average weight of a raster tile, used only for the pre-download size estimate. */
    private const val AVERAGE_TILE_BYTES = 25_000L

    /** Pause between requests to one provider. */
    const val DEFAULT_DELAY_MS = 700L

    /** Longer pause inserted periodically so the request pattern stays unlike a scraper. */
    const val LONG_PAUSE_EVERY = 200
    const val LONG_PAUSE_MS = 5_000L

    /** Abort after this many consecutive failures: almost always means we are being blocked. */
    const val MAX_CONSECUTIVE_FAILURES = 8

    fun lonToTileX(lon: Double, zoom: Int): Int =
        floor((lon + 180.0) / 360.0 * 2.0.pow(zoom)).toInt()

    fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = lat * PI / 180.0
        return floor((1.0 - asinh(tan(latRad)) / PI) / 2.0 * 2.0.pow(zoom)).toInt()
    }

    fun tileXToLon(x: Int, zoom: Int): Double = x / 2.0.pow(zoom) * 360.0 - 180.0

    fun tileYToLat(y: Int, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / 2.0.pow(zoom)
        return 180.0 / PI * atan(sinh(n))
    }

    /** Tiles covering [bounds] at [zoom]. */
    fun tilesForZoom(bounds: RegionBounds, zoom: Int): List<TileCoordinate> {
        val xStart = lonToTileX(bounds.minLon, zoom)
        val xEnd = lonToTileX(bounds.maxLon, zoom)
        // Y grows southward, so maxLat gives the smaller index.
        val yStart = latToTileY(bounds.maxLat, zoom)
        val yEnd = latToTileY(bounds.minLat, zoom)

        val tiles = ArrayList<TileCoordinate>()
        for (x in minOf(xStart, xEnd)..maxOf(xStart, xEnd)) {
            for (y in minOf(yStart, yEnd)..maxOf(yStart, yEnd)) {
                tiles.add(TileCoordinate(x, y, zoom))
            }
        }
        return tiles
    }

    fun estimate(bounds: RegionBounds, minZoom: Int, maxZoom: Int, layerCount: Int): DownloadEstimate {
        var count = 0
        for (z in minZoom..maxZoom) {
            count += tilesForZoom(bounds, z).size
        }
        val total = count * layerCount
        return DownloadEstimate(total, total * AVERAGE_TILE_BYTES)
    }

    /**
     * Result of a download run.
     */
    data class Result(
        val downloaded: Int,
        val skipped: Int,
        val failed: Int,
        val abortedByProvider: Boolean,
        val cancelled: Boolean
    )

    /**
     * Fetches every tile of [bounds] across [zoomRange] for each of [sources].
     *
     * @param isCancelled polled between tiles so the user can stop a long job.
     */
    suspend fun download(
        baseDir: File,
        bounds: RegionBounds,
        minZoom: Int,
        maxZoom: Int,
        sources: List<TileSource>,
        delayMs: Long = DEFAULT_DELAY_MS,
        isCancelled: () -> Boolean = { false },
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        var downloaded = 0
        var skipped = 0
        var failed = 0
        var consecutiveFailures = 0
        var abortedByProvider = false
        var processed = 0

        val plan = ArrayList<Pair<TileSource, TileCoordinate>>()
        for (source in sources) {
            for (z in minZoom..maxZoom) {
                if (z < source.minZoom || z > source.maxZoom) continue
                for (tile in tilesForZoom(bounds, z)) {
                    plan.add(source to tile)
                }
            }
        }

        val total = plan.size

        for ((source, tile) in plan) {
            if (isCancelled()) {
                return@withContext Result(downloaded, skipped, failed, abortedByProvider, true)
            }

            processed++

            val target = File(baseDir, "${source.id}/${tile.zoom}/${tile.x}/${tile.y}.png")
            if (target.exists()) {
                skipped++
                if (processed % 25 == 0) {
                    onProgress(DownloadProgress(processed, total, downloaded, skipped, failed, source.name))
                }
                continue
            }

            val ok = fetchTile(source, tile, target)
            if (ok) {
                downloaded++
                consecutiveFailures = 0
            } else {
                failed++
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    // Treat a run of failures as the provider refusing us and stop, rather than
                    // hammering it until the address is banned outright.
                    Log.w(TAG, "Aborting: $consecutiveFailures consecutive failures")
                    abortedByProvider = true
                    break
                }
            }

            onProgress(DownloadProgress(processed, total, downloaded, skipped, failed, source.name))

            delay(delayMs)
            if (processed % LONG_PAUSE_EVERY == 0) {
                delay(LONG_PAUSE_MS)
            }
        }

        Result(downloaded, skipped, failed, abortedByProvider, false)
    }

    private fun fetchTile(source: TileSource, tile: TileCoordinate, target: File): Boolean {
        return try {
            val url = URL(source.getTileUrl(tile))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", TileManager.TILE_USER_AGENT)
                setRequestProperty("Referer", TileManager.TILE_REFERER)
            }

            // Providers may answer 200 with a "blocked" placeholder image; treat it as a failure
            // so the consecutive-failure guard can stop the job.
            if (!connection.getHeaderField("x-blocked").isNullOrBlank()) return false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false

            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size < 1024) return false

            // Validate the image WITHOUT allocating it. Fully decoding every tile meant a fresh
            // 256x256 bitmap (~256 KB) per request thousands of times over, which pushed the app
            // into out-of-memory territory during long region downloads.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return false

            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Tile fetch failed ${tile.key}: ${e.message}")
            false
        }
    }
}
