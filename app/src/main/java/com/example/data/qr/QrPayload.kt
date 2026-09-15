package com.example.data.qr

import com.example.model.GeoPoint
import java.io.ByteArrayOutputStream

/**
 * Compact binary payload for transferring a waypoint between devices by QR code, with every
 * radio switched off.
 *
 * Why not JSON: a QR code that must decode instantly from a scratched screen at an angle has to
 * stay small (low version, high error correction). JSON field names alone would blow that budget.
 *
 * Why not Deflate+Base64 on top: Base64 inflates by a third and coordinates are close to random,
 * so there is nothing to compress. Both ends are ours, so raw bytes in QR's binary mode win.
 *
 * Layout (single point, 0x01):
 *   [0]      protocol version
 *   [1]      payload type
 *   [2..5]   latitude  as int, degrees * 1e7   (4 bytes, ~1 cm resolution)
 *   [6..9]   longitude as int, degrees * 1e7
 *   [10..11] altitude in metres as signed short, or Short.MIN_VALUE when unknown
 *   [12]     name length in bytes
 *   [13..]   name, UTF-8
 *
 * Coordinates are fixed-point rather than Double: 8 bytes each would buy sub-micron precision
 * nobody needs and double the payload for no gain.
 */
object QrPayload {

    const val PROTOCOL_VERSION: Byte = 1
    const val TYPE_SINGLE_POINT: Byte = 0x01

    /** Names are capped so the code stays in a low QR version; Cyrillic costs 2 bytes a letter. */
    const val MAX_NAME_BYTES = 32

    private const val COORD_SCALE = 1e7
    private const val ALTITUDE_UNKNOWN = Short.MIN_VALUE

    data class DecodedPoint(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double?
    ) {
        fun toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude, altitudeMeters)
    }

    fun encodePoint(
        name: String,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?
    ): ByteArray {
        val out = ByteArrayOutputStream(64)
        out.write(PROTOCOL_VERSION.toInt())
        out.write(TYPE_SINGLE_POINT.toInt())

        writeInt(out, Math.round(latitude * COORD_SCALE).toInt())
        writeInt(out, Math.round(longitude * COORD_SCALE).toInt())

        val alt = when {
            altitudeMeters == null -> ALTITUDE_UNKNOWN
            altitudeMeters > Short.MAX_VALUE -> Short.MAX_VALUE
            altitudeMeters < (Short.MIN_VALUE + 1) -> (Short.MIN_VALUE + 1).toShort()
            else -> Math.round(altitudeMeters).toShort()
        }
        writeShort(out, alt)

        var nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_NAME_BYTES) {
            nameBytes = truncateUtf8(nameBytes, MAX_NAME_BYTES)
        }
        out.write(nameBytes.size)
        out.write(nameBytes)

        return out.toByteArray()
    }

    /**
     * @return the decoded point, or null when the bytes are not a payload we understand.
     */
    fun decode(bytes: ByteArray): DecodedPoint? {
        if (bytes.size < 13) return null
        if (bytes[0] != PROTOCOL_VERSION) return null
        if (bytes[1] != TYPE_SINGLE_POINT) return null

        val lat = readInt(bytes, 2) / COORD_SCALE
        val lon = readInt(bytes, 6) / COORD_SCALE
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null

        val rawAlt = readShort(bytes, 10)
        val altitude = if (rawAlt == ALTITUDE_UNKNOWN) null else rawAlt.toDouble()

        val nameLen = bytes[12].toInt() and 0xFF
        if (13 + nameLen > bytes.size) return null
        val name = String(bytes, 13, nameLen, Charsets.UTF_8)

        return DecodedPoint(
            name = name.ifBlank { "Точка с QR" },
            latitude = lat,
            longitude = lon,
            altitudeMeters = altitude
        )
    }

    /** Cuts UTF-8 bytes without splitting a character in half. */
    private fun truncateUtf8(bytes: ByteArray, limit: Int): ByteArray {
        var end = limit
        // Continuation bytes are 10xxxxxx; step back off them to a character boundary.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Short) {
        val v = value.toInt()
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun readInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)

    private fun readShort(b: ByteArray, offset: Int): Short =
        (((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)).toShort()
}
