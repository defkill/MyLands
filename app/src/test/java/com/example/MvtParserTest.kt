package com.example

import com.example.map.vector.GeometryType
import com.example.map.vector.MvtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class MvtParserTest {

    @Test
    fun testZigzagEncodingAndDecoding() {
        // Test 0, 1, -1, positive and negative integers
        val testValues = listOf(0, 1, -1, 2, -2, 100, -100, 4096, -4096, Int.MAX_VALUE / 2, Int.MIN_VALUE / 2)
        for (v in testValues) {
            val encoded = MvtParser.encodeZigzag32(v)
            val decoded = MvtParser.decodeZigzag32(encoded)
            assertEquals("Mismatch for $v", v, decoded)
        }

        // Test 64-bit decoding
        assertEquals(0L, MvtParser.decodeZigzag64(0L))
        assertEquals(-1L, MvtParser.decodeZigzag64(1L))
        assertEquals(1L, MvtParser.decodeZigzag64(2L))
        assertEquals(-2L, MvtParser.decodeZigzag64(3L))
    }

    @Test
    fun testDecodePointGeometry() {
        // Point at (25, 17)
        // Command MoveTo (id=1, count=1) -> (1 and 0x7) | (1 shl 3) = 9
        // dX = 25 -> zigzag(25) = 50
        // dY = 17 -> zigzag(17) = 34
        val commands = listOf(9, 50, 34)
        val decoded = MvtParser.decodeGeometry(commands, GeometryType.POINT)

        assertEquals(1, decoded.size)
        assertEquals(2, decoded[0].size) // [x, y]
        assertEquals(25, decoded[0][0])
        assertEquals(17, decoded[0][1])
    }

    @Test
    fun testDecodeLineStringGeometry() {
        // LineString from (10, 10) to (20, 30) to (25, 35)
        // MoveTo(count=1): command = 9, dX=10 (zigzag=20), dY=10 (zigzag=20)
        // LineTo(count=2): command = (2 and 0x7) | (2 shl 3) = 18
        //   p2: (20-10)=10 (zigzag=20), (30-10)=20 (zigzag=40)
        //   p3: (25-20)=5 (zigzag=10), (35-30)=5 (zigzag=10)
        val commands = listOf(9, 20, 20, 18, 20, 40, 10, 10)
        val decoded = MvtParser.decodeGeometry(commands, GeometryType.LINESTRING)

        assertEquals(1, decoded.size)
        assertEquals(6, decoded[0].size) // 3 points * 2 coords = 6 ints
        assertEquals(10, decoded[0][0])
        assertEquals(10, decoded[0][1])
        assertEquals(20, decoded[0][2])
        assertEquals(30, decoded[0][3])
        assertEquals(25, decoded[0][4])
        assertEquals(35, decoded[0][5])
    }

    @Test
    fun testDecodePolygonGeometry() {
        // Polygon: (0,0) -> (10, 0) -> (10, 10) -> (0, 10) -> ClosePath
        // MoveTo(count=1): 9, dX=0(0), dY=0(0)
        // LineTo(count=3): (2 | (3 shl 3)) = 26
        //   p1: dX=10(20), dY=0(0) -> (10, 0)
        //   p2: dX=0(0), dY=10(20) -> (10, 10)
        //   p3: dX=-10(19), dY=0(0) -> (0, 10)
        // ClosePath(count=1): (7 | (1 shl 3)) = 15
        val commands = listOf(9, 0, 0, 26, 20, 0, 0, 20, 19, 0, 15)
        val decoded = MvtParser.decodeGeometry(commands, GeometryType.POLYGON)

        assertEquals(1, decoded.size)
        val ring = decoded[0]
        assertEquals(10, ring.size) // Closed ring of 5 points (10 ints)
        assertEquals(0, ring[0])
        assertEquals(0, ring[1])
        assertEquals(10, ring[2])
        assertEquals(0, ring[3])
        assertEquals(10, ring[4])
        assertEquals(10, ring[5])
        assertEquals(0, ring[6])
        assertEquals(10, ring[7])
        assertEquals(0, ring[8])
        assertEquals(0, ring[9])
    }

    @Test
    fun testGarbageDataResilience() {
        // Empty bytes
        val emptyTile = MvtParser.parse(ByteArray(0))
        assertNotNull(emptyTile)
        assertTrue(emptyTile!!.layers.isEmpty())

        // Random garbage bytes
        val garbage = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xFF.toByte(), 0xFE.toByte())
        val garbageTile = MvtParser.parse(garbage)
        // Returns empty/fallback or null gracefully on garbage
        assertTrue(garbageTile == null || garbageTile.layers.isEmpty())

        // Truncated gzip header
        val truncGzip = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00)
        val truncTile = MvtParser.parse(truncGzip)
        assertTrue(truncTile == null || truncTile.layers.isEmpty())
    }

    @Test
    fun testGzipDecompression() {
        val original = "Hello Tactical Vector Tile".toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(original) }
        val compressed = baos.toByteArray()

        val decompressed = MvtParser.decompressGzipIfNeeded(compressed)
        assertEquals("Hello Tactical Vector Tile", String(decompressed, Charsets.UTF_8))
    }

    @Test
    fun testDecompressGzip_RejectsOversizedPayload() {
        // Compress 100 KB of zeros, and test with custom maxAllowedBytes = 1024
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(ByteArray(100 * 1024))
        }
        val bomb = baos.toByteArray()
        val result = MvtParser.decompressGzipIfNeeded(bomb, maxAllowedBytes = 1024)
        assertEquals("Payload exceeding limit should return empty array", 0, result.size)

        // Also test with payload exceeding default 20MB limit (21 MB of zeros compresses to ~21 KB)
        val baos21Mb = ByteArrayOutputStream()
        GZIPOutputStream(baos21Mb).use { gzip ->
            val zeroChunk = ByteArray(64 * 1024)
            repeat(330) { // 330 * 64KB ≈ 21 MB
                gzip.write(zeroChunk)
            }
        }
        val bomb21Mb = baos21Mb.toByteArray()
        val resultDefault = MvtParser.decompressGzipIfNeeded(bomb21Mb)
        assertEquals("Payload exceeding default 20MB limit should return empty array", 0, resultDefault.size)

        // Parse method should safely return empty VectorTile without throwing or crashing
        val parsedTile = MvtParser.parse(bomb21Mb)
        assertNotNull(parsedTile)
        assertTrue(parsedTile!!.layers.isEmpty())
    }
}
