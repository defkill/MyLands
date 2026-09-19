package com.example.map

import java.io.File
import java.io.InputStream

enum class OfflineMapFormat(val title: String, val extension: String) {
    MBTILES("Карта MBTiles (SQLite)", ".mbtiles"),
    GEOPACKAGE("Карта GeoPackage (.gpkg)", ".gpkg"),
    ORNTPACK("Офлайн-пакет .orntpack (ZIP)", ".orntpack"),
    UNKNOWN("Неизвестный формат", "")
}

object OfflineMapDetector {

    private val SQLITE_HEADER = "SQLite format 3".toByteArray(Charsets.US_ASCII)

    /**
     * Detects offline map format by file extension first, then validates or detects by file header magic bytes.
     */
    fun detect(fileName: String, headerBytes: ByteArray? = null): OfflineMapFormat {
        val lower = fileName.lowercase()
        if (lower.endsWith(".gpkg")) {
            return OfflineMapFormat.GEOPACKAGE
        }
        if (lower.endsWith(".mbtiles")) {
            return OfflineMapFormat.MBTILES
        }
        if (lower.endsWith(".orntpack") || lower.endsWith(".zip")) {
            return OfflineMapFormat.ORNTPACK
        }

        if (headerBytes != null && headerBytes.isNotEmpty()) {
            // SQLite header check
            if (headerBytes.size >= SQLITE_HEADER.size) {
                var matchesSqlite = true
                for (i in SQLITE_HEADER.indices) {
                    if (headerBytes[i] != SQLITE_HEADER[i]) {
                        matchesSqlite = false
                        break
                    }
                }
                if (matchesSqlite) {
                    // Check if gpkg by name or default to mbtiles
                    return if (lower.endsWith(".gpkg")) OfflineMapFormat.GEOPACKAGE else OfflineMapFormat.MBTILES
                }
            }

            // ZIP / .orntpack header check (0x50, 0x4B, 0x03, 0x04)
            if (headerBytes.size >= 4) {
                if (headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte()) {
                    return OfflineMapFormat.ORNTPACK
                }
            }
        }

        return OfflineMapFormat.UNKNOWN
    }

    /**
     * Inspects the first 64 bytes of an input stream to detect map format.
     */
    fun detectFromStream(fileName: String, stream: InputStream): Pair<OfflineMapFormat, ByteArray> {
        val header = ByteArray(64)
        val bytesRead = stream.read(header)
        val validHeader = if (bytesRead > 0) header.copyOf(bytesRead) else ByteArray(0)
        val format = detect(fileName, validHeader)
        return Pair(format, validHeader)
    }

    /**
     * Detects format from an existing File.
     */
    fun detectFromFile(file: File): OfflineMapFormat {
        if (!file.exists() || file.length() == 0L) return OfflineMapFormat.UNKNOWN
        val header = try {
            file.inputStream().use { stream ->
                val buf = ByteArray(64)
                val read = stream.read(buf)
                if (read > 0) buf.copyOf(read) else ByteArray(0)
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
        return detect(file.name, header)
    }
}
