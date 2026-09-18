package com.example.map.vector

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
 * Parsed vector feature with decoded geometry and key-value properties.
 */
data class VectorFeature(
    val id: Long = 0L,
    val geometryType: GeometryType,
    val attributes: Map<String, Any>,
    val geometry: List<List<IntPoint>>
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
     * Uncompresses gzip if present and parses byte array into a [VectorTile].
     */
    fun parse(data: ByteArray): VectorTile {
        if (data.isEmpty()) return VectorTile(emptyList())
        val uncompressed = decompressGzipIfNeeded(data)
        val stream = ByteArrayInputStream(uncompressed)
        return parseTile(stream, uncompressed.size)
    }

    /**
     * Decompresses GZIP byte stream if GZIP magic header (0x1F, 0x8B) is detected.
     */
    fun decompressGzipIfNeeded(data: ByteArray): ByteArray {
        if (data.size < 2) return data
        val isGzip = (data[0].toInt() and 0xFF) == 0x1F && (data[1].toInt() and 0xFF) == 0x8B
        if (!isGzip) return data

        return try {
            GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
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
    fun decodeGeometry(commands: List<Int>, geomType: GeometryType): List<List<IntPoint>> {
        val result = mutableListOf<List<IntPoint>>()
        var cursorX = 0
        var cursorY = 0
        var currentRing = mutableListOf<IntPoint>()

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
                            currentRing.add(IntPoint(cursorX, cursorY))
                        }
                    } else {
                        // For LineString and Polygon, MoveTo starts a new ring/path
                        if (currentRing.isNotEmpty()) {
                            result.add(currentRing)
                            currentRing = mutableListOf()
                        }
                        for (c in 0 until count) {
                            if (index + 1 >= commands.size) break
                            val dx = decodeZigzag32(commands[index++])
                            val dy = decodeZigzag32(commands[index++])
                            cursorX += dx
                            cursorY += dy
                            currentRing.add(IntPoint(cursorX, cursorY))
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
                        currentRing.add(IntPoint(cursorX, cursorY))
                    }
                }
                7 -> { // ClosePath
                    if (currentRing.isNotEmpty()) {
                        // Explicitly close if polygon ring is not closed
                        val first = currentRing.first()
                        if (currentRing.last() != first) {
                            currentRing.add(first)
                        }
                        result.add(currentRing)
                        currentRing = mutableListOf()
                    }
                }
                else -> {
                    // Unknown command, stop decoding to prevent desync
                    break
                }
            }
        }

        if (currentRing.isNotEmpty()) {
            result.add(currentRing)
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
