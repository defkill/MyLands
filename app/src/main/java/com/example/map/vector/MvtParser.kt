package com.example.map.vector

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Geometry types supported by Mapbox Vector Tile Specification 2.1.
 */
enum class GeometryType {
    UNKNOWN,
    POINT,
    LINESTRING,
    POLYGON
}

/**
 * 2D integer point in tile local coordinate space (0..extent).
 */
data class IntPoint(val x: Int, val y: Int)

/**
 * High-performance primitive buffer for building flat [x0, y0, x1, y1, ...] coordinate arrays
 * without object boxing or ArrayList overhead.
 */
class IntBuffer(initialCapacity: Int = 64) {
    var data = IntArray(initialCapacity)
        private set
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun addPoint(x: Int, y: Int) {
        if (size + 2 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 2))
        data[size++] = x
        data[size++] = y
    }

    fun isNotEmpty(): Boolean = size > 0

    fun toIntArray(): IntArray = data.copyOf(size)
}

/**
 * Parsed vector feature with decoded geometry as primitive [IntArray] rings/lines and key-value properties.
 */
data class VectorFeature(
    val id: Long = 0L,
    val geometryType: GeometryType,
    val attributes: Map<String, Any>,
    val geometry: List<IntArray>
)

/**
 * Parsed vector layer.
 */
data class VectorLayer(
    val name: String,
    val extent: Int = 4096,
    val version: Int = 2,
    val features: List<VectorFeature>
)

/**
 * Top-level parsed Mapbox Vector Tile container.
 */
data class VectorTile(
    val layers: List<VectorLayer>
)

/**
 * Pure Kotlin parser for Mapbox Vector Tile (MVT / Mapbox PBF) specification 2.1.
 * Self-contained without external protobuf or JTS libraries.
 */
object MvtParser {

    private const val WIRETYPE_VARINT = 0
    private const val WIRETYPE_FIXED64 = 1
    private const val WIRETYPE_LENGTH_DELIMITED = 2
    private const val WIRETYPE_FIXED32 = 5

    /**
     * Uncompresses gzip if present and parses byte array into a [VectorTile], or returns null on failure/oversized data.
     */
    fun parse(data: ByteArray): VectorTile? {
        if (data.isEmpty()) return VectorTile(emptyList())
        return try {
            val uncompressed = decompressGzipIfNeeded(data)
            if (uncompressed.isEmpty()) {
                return VectorTile(emptyList())
            }
            if (uncompressed.size > 20 * 1024 * 1024) {
                Log.w("MvtParser", "Tile too large after decompression: ${uncompressed.size} bytes, skipping")
                return null
            }
            val stream = ByteArrayInputStream(uncompressed)
            parseTile(stream, uncompressed.size)
        } catch (e: Throwable) {
            Log.e("MvtParser", "Failed to parse tile (${data.size} bytes): ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Decompresses GZIP byte stream if GZIP magic header (0x1F, 0x8B) is detected.
     * Enforces [maxAllowedBytes] limit during decompression to prevent GZIP bomb attacks.
     */
    fun decompressGzipIfNeeded(data: ByteArray, maxAllowedBytes: Int = 20 * 1024 * 1024): ByteArray {
        if (data.size < 2) return data
        val isGzip = (data[0].toInt() and 0xFF) == 0x1F && (data[1].toInt() and 0xFF) == 0x8B
        if (!isGzip) return data

        return try {
            val buffer = ByteArray(8192)
            val initialCapacity = (data.size.toLong() * 4).coerceAtMost(maxAllowedBytes.toLong()).toInt()
            val out = java.io.ByteArrayOutputStream(initialCapacity.coerceAtLeast(1024))
            var totalRead = 0
            GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
                while (true) {
                    val read = gzip.read(buffer)
                    if (read <= 0) break
                    totalRead += read
                    if (totalRead > maxAllowedBytes) {
                        try {
                            Log.w("MvtParser", "GZIP payload exceeded limit of $maxAllowedBytes bytes, aborting decompression")
                        } catch (_: Throwable) {}
                        return data.copyOf(0)
                    }
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            data
        }
    }

    private fun parseTile(stream: InputStream, length: Int): VectorTile {
        val layers = mutableListOf<VectorLayer>()
        val limiter = LimitedInputStream(stream, length.toLong())

        while (limiter.hasRemaining()) {
            val tag = readVarint32(limiter) ?: break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7

            if (fieldNumber == 3 && wireType == WIRETYPE_LENGTH_DELIMITED) {
                val layerLength = readVarint32(limiter) ?: break
                try {
                    val layer = parseLayer(limiter, layerLength)
                    layers.add(layer)
                } catch (_: Exception) {
                    // Skip corrupt layer gracefully
                }
            } else {
                skipField(limiter, wireType)
            }
        }
        return VectorTile(layers)
    }

    private fun parseLayer(stream: InputStream, length: Int): VectorLayer {
        val limiter = LimitedInputStream(stream, length.toLong())
        var name = ""
        var extent = 4096
        var version = 2
        val keys = mutableListOf<String>()
        val values = mutableListOf<Any>()
        val rawFeatures = mutableListOf<ByteArray>()

        while (limiter.hasRemaining()) {
            val tag = readVarint32(limiter) ?: break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7

            when (fieldNumber) {
                1 -> { // name (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        val strLen = readVarint32(limiter) ?: break
                        val bytes = limiter.readNBytesCompat(strLen)
                        name = String(bytes, Charsets.UTF_8)
                    } else skipField(limiter, wireType)
                }
                2 -> { // features (repeated Feature)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        val featLen = readVarint32(limiter) ?: break
                        val featBytes = limiter.readNBytesCompat(featLen)
                        rawFeatures.add(featBytes)
                    } else skipField(limiter, wireType)
                }
                3 -> { // keys (repeated string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        val strLen = readVarint32(limiter) ?: break
                        val bytes = limiter.readNBytesCompat(strLen)
                        keys.add(String(bytes, Charsets.UTF_8))
                    } else skipField(limiter, wireType)
                }
                4 -> { // values (repeated Value)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        val valLen = readVarint32(limiter) ?: break
                        val value = parseValue(limiter, valLen)
                        values.add(value)
                    } else skipField(limiter, wireType)
                }
                5 -> { // extent (uint32)
                    if (wireType == WIRETYPE_VARINT) {
                        extent = readVarint32(limiter) ?: 4096
                    } else skipField(limiter, wireType)
                }
                15 -> { // version (uint32)
                    if (wireType == WIRETYPE_VARINT) {
                        version = readVarint32(limiter) ?: 2
                    } else skipField(limiter, wireType)
                }
                else -> skipField(limiter, wireType)
            }
        }

        val features = mutableListOf<VectorFeature>()
        for (raw in rawFeatures) {
            try {
                val feat = parseFeature(raw, keys, values)
                if (feat != null) {
                    features.add(feat)
                }
            } catch (_: Exception) {
                // Ignore single corrupt feature
            }
        }

        return VectorLayer(name, extent, version, features)
    }

    private fun parseValue(stream: InputStream, length: Int): Any {
        val limiter = LimitedInputStream(stream, length.toLong())
        var result: Any = ""

        while (limiter.hasRemaining()) {
            val tag = readVarint32(limiter) ?: break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7

            when (fieldNumber) {
                1 -> { // string_value
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        val strLen = readVarint32(limiter) ?: break
                        val bytes = limiter.readNBytesCompat(strLen)
                        result = String(bytes, Charsets.UTF_8)
                    } else skipField(limiter, wireType)
                }
                2 -> { // float_value
                    if (wireType == WIRETYPE_FIXED32) {
                        val b = limiter.readNBytesCompat(4)
                        val bits = (b[0].toInt() and 0xFF) or
                                ((b[1].toInt() and 0xFF) shl 8) or
                                ((b[2].toInt() and 0xFF) shl 16) or
                                ((b[3].toInt() and 0xFF) shl 24)
                        result = Float.fromBits(bits)
                    } else skipField(limiter, wireType)
                }
                3 -> { // double_value
                    if (wireType == WIRETYPE_FIXED64) {
                        val b = limiter.readNBytesCompat(8)
                        var bits = 0L
                        for (i in 0 until 8) {
                            bits = bits or ((b[i].toLong() and 0xFFL) shl (i * 8))
                        }
                        result = Double.fromBits(bits)
                    } else skipField(limiter, wireType)
                }
                4 -> { // int_value
                    if (wireType == WIRETYPE_VARINT) {
                        result = readVarint64(limiter) ?: 0L
                    } else skipField(limiter, wireType)
                }
                5 -> { // uint_value
                    if (wireType == WIRETYPE_VARINT) {
                        result = readVarint64(limiter) ?: 0L
                    } else skipField(limiter, wireType)
                }
                6 -> { // sint_value (zigzag int64)
                    if (wireType == WIRETYPE_VARINT) {
                        val raw = readVarint64(limiter) ?: 0L
                        result = decodeZigzag64(raw)
                    } else skipField(limiter, wireType)
                }
                7 -> { // bool_value
                    if (wireType == WIRETYPE_VARINT) {
                        result = (readVarint32(limiter) ?: 0) != 0
                    } else skipField(limiter, wireType)
                }
                else -> skipField(limiter, wireType)
            }
        }
        return result
    }

    private fun parseFeature(
        bytes: ByteArray,
        keys: List<String>,
        values: List<Any>
    ): VectorFeature? {
        val limiter = LimitedInputStream(ByteArrayInputStream(bytes), bytes.size.toLong())
        var id = 0L
        var geomType = GeometryType.UNKNOWN
        val rawTags = mutableListOf<Int>()
        val rawGeometry = mutableListOf<Int>()

        while (limiter.hasRemaining()) {
            val tag = readVarint32(limiter) ?: break
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7

            when (fieldNumber) {
                1 -> { // id
                    if (wireType == WIRETYPE_VARINT) {
                        id = readVarint64(limiter) ?: 0L
                    } else skipField(limiter, wireType)
                }
                2 -> { // tags (packed repeated uint32)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        val len = readVarint32(limiter) ?: break
                        val tagLimiter = LimitedInputStream(limiter, len.toLong())
                        while (tagLimiter.hasRemaining()) {
                            val v = readVarint32(tagLimiter) ?: break
                            rawTags.add(v)
                        }
                    } else if (wireType == WIRETYPE_VARINT) {
                        readVarint32(limiter)?.let { rawTags.add(it) }
                    } else skipField(limiter, wireType)
                }
                3 -> { // type (GeomType enum)
                    if (wireType == WIRETYPE_VARINT) {
                        val t = readVarint32(limiter) ?: 0
                        geomType = when (t) {
                            1 -> GeometryType.POINT
                            2 -> GeometryType.LINESTRING
                            3 -> GeometryType.POLYGON
                            else -> GeometryType.UNKNOWN
                        }
                    } else skipField(limiter, wireType)
                }
                4 -> { // geometry (packed repeated uint32)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        val len = readVarint32(limiter) ?: break
                        val geomLimiter = LimitedInputStream(limiter, len.toLong())
                        while (geomLimiter.hasRemaining()) {
                            val v = readVarint32(geomLimiter) ?: break
                            rawGeometry.add(v)
                        }
                    } else if (wireType == WIRETYPE_VARINT) {
                        readVarint32(limiter)?.let { rawGeometry.add(it) }
                    } else skipField(limiter, wireType)
                }
                else -> skipField(limiter, wireType)
            }
        }

        // Decode attributes from tags key-value pairs
        val attributes = mutableMapOf<String, Any>()
        var i = 0
        while (i + 1 < rawTags.size) {
            val kIdx = rawTags[i]
            val vIdx = rawTags[i + 1]
            if (kIdx in keys.indices && vIdx in values.indices) {
                attributes[keys[kIdx]] = values[vIdx]
            }
            i += 2
        }

        val decodedGeometry = decodeGeometry(rawGeometry, geomType)
        return VectorFeature(id, geomType, attributes, decodedGeometry)
    }

    /**
     * Decodes MVT geometry command integer streams according to MVT 2.1 specs.
     * Commands:
     * 1 = MoveTo (count)
     * 2 = LineTo (count)
     * 7 = ClosePath (count = 1)
     */
    fun decodeGeometry(commands: List<Int>, geomType: GeometryType): List<IntArray> {
        val result = mutableListOf<IntArray>()
        var cursorX = 0
        var cursorY = 0
        var currentRing = IntBuffer(64)

        var index = 0
        while (index < commands.size) {
            val commandInt = commands[index++]
            val commandId = commandInt and 0x7
            val count = commandInt ushr 3

            when (commandId) {
                1 -> { // MoveTo
                    if (geomType == GeometryType.POINT) {
                        // For points, each MoveTo count is a distinct point in a single multipoint or list
                        for (c in 0 until count) {
                            if (index + 1 >= commands.size) break
                            val dx = decodeZigzag32(commands[index++])
                            val dy = decodeZigzag32(commands[index++])
                            cursorX += dx
                            cursorY += dy
                            currentRing.addPoint(cursorX, cursorY)
                        }
                    } else {
                        // For LineString and Polygon, MoveTo starts a new ring/path
                        if (currentRing.isNotEmpty()) {
                            result.add(currentRing.toIntArray())
                            currentRing = IntBuffer(64)
                        }
                        for (c in 0 until count) {
                            if (index + 1 >= commands.size) break
                            val dx = decodeZigzag32(commands[index++])
                            val dy = decodeZigzag32(commands[index++])
                            cursorX += dx
                            cursorY += dy
                            currentRing.addPoint(cursorX, cursorY)
                        }
                    }
                }
                2 -> { // LineTo
                    for (c in 0 until count) {
                        if (index + 1 >= commands.size) break
                        val dx = decodeZigzag32(commands[index++])
                        val dy = decodeZigzag32(commands[index++])
                        cursorX += dx
                        cursorY += dy
                        currentRing.addPoint(cursorX, cursorY)
                    }
                }
                7 -> { // ClosePath
                    if (currentRing.isNotEmpty()) {
                        // Explicitly close if polygon ring is not closed
                        if (currentRing.size >= 4) {
                            val firstX = currentRing.data[0]
                            val firstY = currentRing.data[1]
                            val lastX = currentRing.data[currentRing.size - 2]
                            val lastY = currentRing.data[currentRing.size - 1]
                            if (firstX != lastX || firstY != lastY) {
                                currentRing.addPoint(firstX, firstY)
                            }
                        }
                        result.add(currentRing.toIntArray())
                        currentRing = IntBuffer(64)
                    }
                }
                else -> {
                    // Unknown command, stop decoding to prevent desync
                    break
                }
            }
        }

        if (currentRing.isNotEmpty()) {
            result.add(currentRing.toIntArray())
        }

        return result
    }

    fun decodeZigzag32(n: Int): Int {
        return (n ushr 1) xor (-(n and 1))
    }

    fun encodeZigzag32(n: Int): Int {
        return (n shl 1) xor (n shr 31)
    }

    fun decodeZigzag64(n: Long): Long {
        return (n ushr 1) xor (-(n and 1L))
    }

    private fun readVarint32(stream: InputStream): Int? {
        var result = 0
        var shift = 0
        while (shift < 35) {
            val b = stream.read()
            if (b == -1) return if (shift == 0) null else result
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        return result
    }

    private fun readVarint64(stream: InputStream): Long? {
        var result = 0L
        var shift = 0
        while (shift < 70) {
            val b = stream.read()
            if (b == -1) return if (shift == 0) null else result
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        return result
    }

    private fun skipField(stream: InputStream, wireType: Int) {
        when (wireType) {
            WIRETYPE_VARINT -> {
                while (true) {
                    val b = stream.read()
                    if (b == -1 || (b and 0x80) == 0) break
                }
            }
            WIRETYPE_FIXED64 -> stream.skipCompat(8)
            WIRETYPE_LENGTH_DELIMITED -> {
                val len = readVarint32(stream) ?: return
                stream.skipCompat(len.toLong())
            }
            WIRETYPE_FIXED32 -> stream.skipCompat(4)
            else -> { /* Unknown wire type */ }
        }
    }

    private class LimitedInputStream(
        private val delegate: InputStream,
        private var bytesLeft: Long
    ) : InputStream() {
        fun hasRemaining(): Boolean = bytesLeft > 0

        override fun read(): Int {
            if (bytesLeft <= 0) return -1
            val b = delegate.read()
            if (b != -1) bytesLeft--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesLeft <= 0) return -1
            val toRead = minOf(len.toLong(), bytesLeft).toInt()
            val read = delegate.read(b, off, toRead)
            if (read != -1) bytesLeft -= read
            return read
        }
    }

    private fun InputStream.readNBytesCompat(length: Int): ByteArray {
        val result = ByteArray(length)
        var readTotal = 0
        while (readTotal < length) {
            val count = read(result, readTotal, length - readTotal)
            if (count == -1) break
            readTotal += count
        }
        return if (readTotal == length) result else result.copyOf(readTotal)
    }

    private fun InputStream.skipCompat(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
