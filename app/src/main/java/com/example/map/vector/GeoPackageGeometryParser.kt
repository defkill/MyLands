package com.example.map.vector

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GeoBoundingBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
)

data class DoublePoint(
    val lon: Double,
    val lat: Double
)

data class IndexedBoundingBox(
    val segmentIndex: Int,
    val bbox: GeoBoundingBox,
    val pointStart: Int = 0,
    val pointEnd: Int = -1
)

data class GeoFeature(
    val fid: Long,
    val geometryType: String, // "POINT", "LINESTRING", "POLYGON", "MULTILINESTRING", "MULTIPOLYGON", "MULTIPOINT"
    val rings: List<List<DoublePoint>>,
    val attributes: Map<String, Any?> = emptyMap()
)

/**
 * Parser for GeoPackage binary geometry encoding (OGC GeoPackage Standard, Section 2.1.3).
 *
 * GeoPackage Geometry Header:
 * - Bytes 0..1: Magic 'GP' (0x47, 0x50)
 * - Byte 2: Version (0)
 * - Byte 3: Flags
 *     - Bit 0: Byte order (0 = Big Endian / XDR, 1 = Little Endian / NDR)
 *     - Bits 1..3: Envelope indicator
 *         0 = No envelope (0 bytes)
 *         1 = Envelope [minX, maxX, minY, maxY] (32 bytes)
 *         2 = Envelope with Z [minX, maxX, minY, maxY, minZ, maxZ] (48 bytes)
 *         3 = Envelope with M [minX, maxX, minY, maxY, minM, maxM] (48 bytes)
 *         4 = Envelope with Z and M [minX, maxX, minY, maxY, minZ, maxZ, minM, maxM] (64 bytes)
 *     - Bit 4: Empty geometry indicator (1 = empty)
 *     - Bit 5: Extended geometry type (0 = standard, 1 = extended)
 * - Bytes 4..7: SRS ID (int32)
 * - Bytes 8..(8+envelopeSize-1): Envelope bytes
 * - Following bytes: Standard WKB (Well-Known Binary) payload
 */
object GeoPackageGeometryParser {

    private const val MAGIC_0 = 0x47.toByte() // 'G'
    private const val MAGIC_1 = 0x50.toByte() // 'P'

    /**
     * Extracts bounding boxes for indexing, segmenting long LINESTRING / MULTILINESTRING geometries
     * (max [maxPointsPerSegment] points per segment with 1 point overlap) to prevent pathological
     * oversized bounding boxes in spatial index.
     */
    fun extractSegmentedBoundingBoxes(
        geomBytes: ByteArray,
        maxPointsPerSegment: Int = 50
    ): List<IndexedBoundingBox> {
        if (geomBytes.size < 8) return emptyList()
        if (geomBytes[0] != MAGIC_0 || geomBytes[1] != MAGIC_1) return emptyList()

        val flags = geomBytes[3].toInt()
        val isLittleEndian = (flags and 0x01) == 1
        val envelopeIndicator = (flags and 0x0E) shr 1
        val isEmpty = (flags and 0x10) != 0
        if (isEmpty) return emptyList()

        val wkbOffset = 8 + getEnvelopeByteLength(envelopeIndicator)
        if (wkbOffset >= geomBytes.size) return emptyList()

        try {
            val headerOrder = if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val buffer = ByteBuffer.wrap(geomBytes, wkbOffset, geomBytes.size - wkbOffset)
            if (buffer.remaining() < 5) return emptyList()

            val byteOrderByte = buffer.get()
            val order = if (byteOrderByte.toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            buffer.order(order)

            val rawType = buffer.int
            val baseType = rawType % 1000

            when (baseType) {
                2 -> { // LINESTRING
                    val numPoints = buffer.int
                    if (numPoints <= 0 || buffer.remaining() < numPoints * 16) return emptyList()

                    if (numPoints <= maxPointsPerSegment) {
                        val bbox = computePointsBbox(buffer, numPoints)
                        return if (bbox != null) listOf(IndexedBoundingBox(0, bbox, 0, -1)) else emptyList()
                    } else {
                        val points = ArrayList<DoublePoint>(numPoints)
                        for (i in 0 until numPoints) {
                            points.add(DoublePoint(buffer.double, buffer.double))
                        }
                        val result = ArrayList<IndexedBoundingBox>()
                        var segIdx = 0
                        var start = 0
                        while (start < numPoints) {
                            val end = (start + maxPointsPerSegment - 1).coerceAtMost(numPoints - 1)
                            val segPts = points.subList(start, end + 1)
                            val bbox = computeBbox(segPts)
                            if (bbox != null) {
                                result.add(IndexedBoundingBox(segIdx++, bbox, start, end))
                            }
                            if (end >= numPoints - 1) break
                            start += (maxPointsPerSegment - 1)
                        }
                        return result
                    }
                }
                5 -> { // MULTILINESTRING
                    val numGeoms = buffer.int
                    if (numGeoms <= 0) return emptyList()
                    val result = mutableListOf<IndexedBoundingBox>()
                    var segCounter = 0
                    for (g in 0 until numGeoms) {
                        if (buffer.remaining() < 5) break
                        val subOrder = if (buffer.get().toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                        buffer.order(subOrder)
                        val subType = buffer.int % 1000
                        if (subType == 2) {
                            val numPoints = buffer.int
                            if (numPoints > 0 && buffer.remaining() >= numPoints * 16) {
                                val bbox = computePointsBbox(buffer, numPoints)
                                if (bbox != null) {
                                    result.add(IndexedBoundingBox((segCounter++).coerceAtMost(0xFFFF), bbox, 0, -1))
                                }
                            }
                        }
                    }
                    return result
                }
                else -> {
                    val bbox = extractBoundingBox(geomBytes)
                    return if (bbox != null) listOf(IndexedBoundingBox(0, bbox, 0, -1)) else emptyList()
                }
            }
        } catch (_: Throwable) {
            val bbox = extractBoundingBox(geomBytes)
            return if (bbox != null) listOf(IndexedBoundingBox(0, bbox, 0, -1)) else emptyList()
        }
    }

    /**
     * Reads only the specified point range [pointStart..pointEnd] directly from WKB LINESTRING,
     * skipping preceeding points without creating intermediate DoublePoint objects.
     */
    fun parsePointRange(
        fid: Long,
        geomBytes: ByteArray,
        pointStart: Int,
        pointEnd: Int,
        attributes: Map<String, Any?> = emptyMap()
    ): GeoFeature? {
        if (geomBytes.size < 8) return null
        if (geomBytes[0] != MAGIC_0 || geomBytes[1] != MAGIC_1) return null

        val flags = geomBytes[3].toInt()
        val isLittleEndian = (flags and 0x01) == 1
        val envelopeIndicator = (flags and 0x0E) shr 1
        val isEmpty = (flags and 0x10) != 0
        if (isEmpty) return null

        val wkbOffset = 8 + getEnvelopeByteLength(envelopeIndicator)
        if (wkbOffset >= geomBytes.size) return null

        try {
            val buffer = ByteBuffer.wrap(geomBytes, wkbOffset, geomBytes.size - wkbOffset)
            if (buffer.remaining() < 5) return null

            val byteOrderByte = buffer.get()
            val order = if (byteOrderByte.toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            buffer.order(order)

            val rawType = buffer.int
            val baseType = rawType % 1000

            if (baseType == 2) { // LINESTRING
                val numPoints = buffer.int
                if (numPoints <= 0 || pointStart >= numPoints) return null
                val actualEnd = pointEnd.coerceAtMost(numPoints - 1)
                if (actualEnd < pointStart) return null

                val stride = when (rawType / 1000) {
                    1, 2 -> 24
                    3 -> 32
                    else -> 16
                }

                if (buffer.remaining() < numPoints * stride) return null

                val pointCount = actualEnd - pointStart + 1
                // Jump straight to pointStart
                val startPos = buffer.position() + pointStart * stride
                buffer.position(startPos)

                val points = ArrayList<DoublePoint>(pointCount)
                for (i in 0 until pointCount) {
                    val lon = buffer.double
                    val lat = buffer.double
                    if (stride > 16) {
                        buffer.position(buffer.position() + (stride - 16))
                    }
                    points.add(DoublePoint(lon, lat))
                }

                return GeoFeature(
                    fid = fid,
                    geometryType = "LINESTRING",
                    rings = listOf(points),
                    attributes = attributes
                )
            } else {
                return parse(fid, geomBytes, attributes)
            }
        } catch (_: Throwable) {
            return null
        }
    }

    private fun computePointsBbox(buffer: ByteBuffer, numPoints: Int): GeoBoundingBox? {
        if (numPoints <= 0) return null
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until numPoints) {
            val x = buffer.double
            val y = buffer.double
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return if (minX.isFinite()) GeoBoundingBox(minX, minY, maxX, maxY) else null
    }

    private fun computeBbox(points: List<DoublePoint>): GeoBoundingBox? {
        if (points.isEmpty()) return null
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (pt in points) {
            if (pt.lon < minX) minX = pt.lon
            if (pt.lon > maxX) maxX = pt.lon
            if (pt.lat < minY) minY = pt.lat
            if (pt.lat > maxY) maxY = pt.lat
        }
        return if (minX.isFinite()) GeoBoundingBox(minX, minY, maxX, maxY) else null
    }

    /**
     * Fast extraction of BoundingBox without fully decoding entire WKB geometry.
     * Uses header envelope if present, otherwise falls back to quick WKB parsing.
     */
    fun extractBoundingBox(geomBytes: ByteArray): GeoBoundingBox? {
        if (geomBytes.size < 8) return null
        if (geomBytes[0] != MAGIC_0 || geomBytes[1] != MAGIC_1) return null

        val flags = geomBytes[3].toInt()
        val isLittleEndian = (flags and 0x01) == 1
        val envelopeIndicator = (flags and 0x0E) shr 1
        val isEmpty = (flags and 0x10) != 0
        if (isEmpty) return null

        val headerOrder = if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buffer = ByteBuffer.wrap(geomBytes).order(headerOrder)
        buffer.position(8) // Skip magic, version, flags, srs_id

        if (envelopeIndicator in 1..4 && buffer.remaining() >= 32) {
            val minX = buffer.double
            val maxX = buffer.double
            val minY = buffer.double
            val maxY = buffer.double
            return GeoBoundingBox(minX = minX, minY = minY, maxX = maxX, maxY = maxY)
        }

        // If no envelope in header, parse WKB to compute bounding box
        val wkbOffset = 8 + getEnvelopeByteLength(envelopeIndicator)
        if (wkbOffset >= geomBytes.size) return null
        return parse(0L, geomBytes)?.let { feature ->
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var hasPoints = false

            for (ring in feature.rings) {
                for (pt in ring) {
                    hasPoints = true
                    if (pt.lon < minX) minX = pt.lon
                    if (pt.lon > maxX) maxX = pt.lon
                    if (pt.lat < minY) minY = pt.lat
                    if (pt.lat > maxY) maxY = pt.lat
                }
            }
            if (hasPoints) GeoBoundingBox(minX, minY, maxX, maxY) else null
        }
    }

    private fun getEnvelopeByteLength(indicator: Int): Int = when (indicator) {
        1 -> 32
        2 -> 48
        3 -> 48
        4 -> 64
        else -> 0
    }

    /**
     * Parses a complete GeoPackage geometry blob into a [GeoFeature].
     */
    fun parse(
        fid: Long,
        geomBytes: ByteArray,
        attributes: Map<String, Any?> = emptyMap()
    ): GeoFeature? {
        try {
            if (geomBytes.size < 8) return null
            if (geomBytes[0] != MAGIC_0 || geomBytes[1] != MAGIC_1) return null

            val flags = geomBytes[3].toInt()
            val envelopeIndicator = (flags and 0x0E) shr 1
            val isEmpty = (flags and 0x10) != 0
            if (isEmpty) return null

            val wkbOffset = 8 + getEnvelopeByteLength(envelopeIndicator)
            if (wkbOffset >= geomBytes.size) return null

            val buffer = ByteBuffer.wrap(geomBytes, wkbOffset, geomBytes.size - wkbOffset)
            return parseWkb(fid, buffer, attributes)
        } catch (_: Throwable) {
            return null
        }
    }

    private fun parseWkb(
        fid: Long,
        buffer: ByteBuffer,
        attributes: Map<String, Any?>
    ): GeoFeature? {
        if (buffer.remaining() < 5) return null

        val byteOrderByte = buffer.get()
        val order = if (byteOrderByte.toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        buffer.order(order)

        val rawType = buffer.int
        // Standard WKB types: 1=Point, 2=LineString, 3=Polygon, 4=MultiPoint, 5=MultiLineString, 6=MultiPolygon, 7=GeometryCollection
        // EWKB / ISO flags may add 1000 for Z or 2000 for M or 3000 for ZM, or 0x80000000 / 0x40000000
        val baseType = rawType % 1000

        return when (baseType) {
            1 -> { // POINT
                if (buffer.remaining() < 16) return null
                val x = buffer.double
                val y = buffer.double
                GeoFeature(
                    fid = fid,
                    geometryType = "POINT",
                    rings = listOf(listOf(DoublePoint(x, y))),
                    attributes = attributes
                )
            }
            2 -> { // LINESTRING
                val numPoints = buffer.int
                if (numPoints <= 0 || buffer.remaining() < numPoints * 16) return null
                val points = ArrayList<DoublePoint>(numPoints)
                for (i in 0 until numPoints) {
                    points.add(DoublePoint(buffer.double, buffer.double))
                }
                GeoFeature(
                    fid = fid,
                    geometryType = "LINESTRING",
                    rings = listOf(points),
                    attributes = attributes
                )
            }
            3 -> { // POLYGON
                val numRings = buffer.int
                if (numRings <= 0) return null
                val rings = ArrayList<List<DoublePoint>>(numRings)
                for (r in 0 until numRings) {
                    val numPoints = buffer.int
                    if (numPoints <= 0 || buffer.remaining() < numPoints * 16) return null
                    val points = ArrayList<DoublePoint>(numPoints)
                    for (p in 0 until numPoints) {
                        points.add(DoublePoint(buffer.double, buffer.double))
                    }
                    rings.add(points)
                }
                GeoFeature(
                    fid = fid,
                    geometryType = "POLYGON",
                    rings = rings,
                    attributes = attributes
                )
            }
            4 -> { // MULTIPOINT
                val numGeoms = buffer.int
                if (numGeoms <= 0) return null
                val points = ArrayList<DoublePoint>(numGeoms)
                for (g in 0 until numGeoms) {
                    if (buffer.remaining() < 5) break
                    val subOrder = if (buffer.get().toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                    buffer.order(subOrder)
                    val subType = buffer.int % 1000
                    if (subType == 1 && buffer.remaining() >= 16) {
                        points.add(DoublePoint(buffer.double, buffer.double))
                    }
                }
                GeoFeature(
                    fid = fid,
                    geometryType = "MULTIPOINT",
                    rings = listOf(points),
                    attributes = attributes
                )
            }
            5 -> { // MULTILINESTRING
                val numGeoms = buffer.int
                if (numGeoms <= 0) return null
                val allLines = ArrayList<List<DoublePoint>>(numGeoms)
                for (g in 0 until numGeoms) {
                    if (buffer.remaining() < 5) break
                    val subOrder = if (buffer.get().toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                    buffer.order(subOrder)
                    val subType = buffer.int % 1000
                    if (subType == 2) {
                        val numPoints = buffer.int
                        if (numPoints > 0 && buffer.remaining() >= numPoints * 16) {
                            val line = ArrayList<DoublePoint>(numPoints)
                            for (p in 0 until numPoints) {
                                line.add(DoublePoint(buffer.double, buffer.double))
                            }
                            allLines.add(line)
                        }
                    }
                }
                GeoFeature(
                    fid = fid,
                    geometryType = "MULTILINESTRING",
                    rings = allLines,
                    attributes = attributes
                )
            }
            6 -> { // MULTIPOLYGON
                val numGeoms = buffer.int
                if (numGeoms <= 0) return null
                val allRings = ArrayList<List<DoublePoint>>()
                for (g in 0 until numGeoms) {
                    if (buffer.remaining() < 5) break
                    val subOrder = if (buffer.get().toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                    buffer.order(subOrder)
                    val subType = buffer.int % 1000
                    if (subType == 3) {
                        val numRings = buffer.int
                        for (r in 0 until numRings) {
                            val numPoints = buffer.int
                            if (numPoints > 0 && buffer.remaining() >= numPoints * 16) {
                                val ring = ArrayList<DoublePoint>(numPoints)
                                for (p in 0 until numPoints) {
                                    ring.add(DoublePoint(buffer.double, buffer.double))
                                }
                                allRings.add(ring)
                            }
                        }
                    }
                }
                GeoFeature(
                    fid = fid,
                    geometryType = "MULTIPOLYGON",
                    rings = allRings,
                    attributes = attributes
                )
            }
            else -> null
        }
    }
}
