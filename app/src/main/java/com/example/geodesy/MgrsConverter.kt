package com.example.geodesy

import java.util.Locale
import kotlin.math.*

/**
 * MGRS (Military Grid Reference System) coordinate representation.
 */
data class MgrsPoint(
    val utmZone: Int,
    val latitudeBand: Char,
    val square100k: String, // 2 letters, e.g. "UA"
    val eastingMeters: Int, // 0..99999
    val northingMeters: Int, // 0..99999
    val precisionDigits: Int = 5 // 5 = 1m (10 digits total), 4 = 10m (8 digits total)
) {
    /**
     * Formats MGRS as standard string with spaces: "36U UA 08803 89139"
     */
    fun formatWithSpaces(digits: Int = precisionDigits): String {
        val div = 10.0.pow(5 - digits).toInt()
        val e = eastingMeters / div
        val n = northingMeters / div
        val fmt = "%0${digits}d"
        return String.format(Locale.US, "%02d%c %s $fmt $fmt", utmZone, latitudeBand, square100k, e, n)
    }

    /**
     * Formats MGRS as compact string: "36UUA0880389139"
     */
    fun formatCompact(digits: Int = precisionDigits): String {
        val div = 10.0.pow(5 - digits).toInt()
        val e = eastingMeters / div
        val n = northingMeters / div
        val fmt = "%0${digits}d"
        return String.format(Locale.US, "%02d%c%s$fmt$fmt", utmZone, latitudeBand, square100k, e, n)
    }
}

/**
 * High-precision MGRS / UTM converter for WGS84 datum.
 * Follows NGA TM 8358.1 military specification.
 */
object MgrsConverter {

    private val ELLIPSOID = Ellipsoid.WGS84
    private val a = ELLIPSOID.a
    private val e2 = ELLIPSOID.e2
    private val ePrime2 = ELLIPSOID.ePrime2
    private const val K0 = 0.9996 // UTM scale factor

    // Meridian arc coefficients for WGS84
    private val e4 = e2 * e2
    private val e6 = e4 * e2
    private val e8 = e6 * e2

    private val A0 = 1.0 - (e2 / 4.0) - (3.0 * e4 / 64.0) - (5.0 * e6 / 256.0) - (175.0 * e8 / 16384.0)
    private val A2 = (3.0 * e2 / 8.0) + (3.0 * e4 / 32.0) + (45.0 * e6 / 1024.0) + (105.0 * e8 / 4096.0)
    private val A4 = (15.0 * e4 / 256.0) + (45.0 * e6 / 1024.0) + (525.0 * e8 / 16384.0)
    private val A6 = (35.0 * e6 / 3072.0) + (175.0 * e8 / 12288.0)
    private val A8 = 315.0 * e8 / 131072.0

    private const val LATITUDE_BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private const val SET_ORIGIN_COLUMN_LETTERS = "AJSAJS"
    private const val SET_ORIGIN_ROW_LETTERS = "AFAFAF"

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ" // 24 letters (no I, O)

    /**
     * Calculates UTM Zone from Longitude and Latitude (including Norway/Svalbard special zones).
     */
    fun getUtmZone(latDeg: Double, lonDeg: Double): Int {
        var lon = lonDeg
        while (lon < -180.0) lon += 360.0
        while (lon >= 180.0) lon -= 360.0

        // Special zone for southwestern Norway
        if (latDeg in 56.0..64.0 && lon in 3.0..12.0) return 32

        // Special zones for Svalbard
        if (latDeg in 72.0..84.0) {
            if (lon in 0.0..9.0) return 31
            if (lon in 9.0..21.0) return 33
            if (lon in 21.0..33.0) return 35
            if (lon in 33.0..42.0) return 37
        }

        return ((lon + 180.0) / 6.0).toInt() + 1
    }

    fun getLatitudeBand(latDeg: Double): Char {
        if (latDeg < -80.0 || latDeg > 84.0) return 'Z'
        val index = ((latDeg + 80.0) / 8.0).toInt().coerceIn(0, 19)
        return LATITUDE_BANDS[index]
    }

    private fun meridianArcLength(bRad: Double): Double {
        return a * (A0 * bRad -
                A2 * sin(2.0 * bRad) +
                A4 * sin(4.0 * bRad) -
                A6 * sin(6.0 * bRad) +
                A8 * sin(8.0 * bRad))
    }

    /**
     * Converts WGS84 (lat, lon) to UTM (Easting, Northing, Zone, Hemisphere).
     */
    fun latLonToUtm(latDeg: Double, lonDeg: Double): DoubleArray {
        val zone = getUtmZone(latDeg, lonDeg)
        val l0Deg = (zone * 6.0) - 183.0

        val bRad = Math.toRadians(latDeg)
        val lRad = Math.toRadians(lonDeg - l0Deg)

        val sinB = sin(bRad)
        val cosB = cos(bRad)
        val t = tan(bRad)
        val t2 = t * t
        val t4 = t2 * t2

        val n = a / sqrt(1.0 - e2 * sinB * sinB)
        val eta2 = ePrime2 * cosB * cosB

        val x0 = meridianArcLength(bRad)

        val l2 = lRad * lRad
        val l3 = l2 * lRad
        val l4 = l2 * l2
        val l5 = l4 * lRad
        val l6 = l3 * l3

        // Northing
        var north = K0 * (x0 +
                n * sinB * cosB * (l2 / 2.0) +
                n * sinB * (cosB.pow(3)) * (5.0 - t2 + 9.0 * eta2 + 4.0 * eta2 * eta2) * (l4 / 24.0) +
                n * sinB * (cosB.pow(5)) * (61.0 - 58.0 * t2 + t4 + 270.0 * eta2 - 330.0 * t2 * eta2) * (l6 / 720.0))

        if (latDeg < 0) {
            north += 10_000_000.0 // False northing for southern hemisphere
        }

        // Easting
        val east = 500_000.0 + K0 * (n * cosB * lRad +
                n * (cosB.pow(3)) * (1.0 - t2 + eta2) * (l3 / 6.0) +
                n * (cosB.pow(5)) * (5.0 - 18.0 * t2 + t4 + 14.0 * eta2 - 58.0 * t2 * eta2) * (l5 / 120.0))

        return doubleArrayOf(east, north, zone.toDouble())
    }

    /**
     * Converts WGS84 (lat, lon) to MGRS.
     */
    fun forward(latDeg: Double, lonDeg: Double, precisionDigits: Int = 5): MgrsPoint {
        val utm = latLonToUtm(latDeg, lonDeg)
        val easting = utm[0]
        val northing = utm[1]
        val zone = utm[2].toInt()
        val latBand = getLatitudeBand(latDeg)

        // 100k column letter
        val set = ((zone - 1) % 6) + 1
        val colOriginChar = SET_ORIGIN_COLUMN_LETTERS[set - 1]
        val colOriginIndex = ALPHABET.indexOf(colOriginChar)

        val col100k = (easting / 100_000.0).toInt()
        val colIndex = (colOriginIndex + col100k - 1) % ALPHABET.length
        val colLetter = ALPHABET[colIndex]

        // 100k row letter
        val rowOriginChar = SET_ORIGIN_ROW_LETTERS[set - 1]
        val rowOriginIndex = "ABCDEFGHJKLMNPQRSTUV".indexOf(rowOriginChar) // 20 letters
        val rowLetters20 = "ABCDEFGHJKLMNPQRSTUV"

        val row100k = (northing / 100_000.0).toInt()
        val rowIndex = (rowOriginIndex + row100k) % 20
        val rowLetter = rowLetters20[rowIndex]

        val square100k = "$colLetter$rowLetter"

        val remEasting = (easting % 100_000.0).toInt().coerceIn(0, 99999)
        val remNorthing = (northing % 100_000.0).toInt().coerceIn(0, 99999)

        return MgrsPoint(
            utmZone = zone,
            latitudeBand = latBand,
            square100k = square100k,
            eastingMeters = remEasting,
            northingMeters = remNorthing,
            precisionDigits = precisionDigits
        )
    }

    /**
     * Parses standard MGRS string (e.g. "36U UA 08803 89139" or "36UUA0880389139") to lat, lon.
     */
    fun inverse(mgrsStr: String): Pair<Double, Double> {
        val clean = mgrsStr.replace("\\s+".toRegex(), "").uppercase(Locale.US)
        if (clean.length < 5) throw IllegalArgumentException("Invalid MGRS string: $mgrsStr")

        var idx = 0
        var zoneStr = ""
        while (idx < clean.length && clean[idx].isDigit()) {
            zoneStr += clean[idx]
            idx++
        }
        val zone = zoneStr.toIntOrNull() ?: throw IllegalArgumentException("MGRS missing UTM zone")
        val latBand = clean[idx]
        idx++
        val colLetter = clean[idx]
        idx++
        val rowLetter = clean[idx]
        idx++

        val remainder = clean.substring(idx)
        val half = remainder.length / 2
        val eastPart = remainder.substring(0, half)
        val northPart = remainder.substring(half)

        val eScale = 10.0.pow(5 - eastPart.length)
        val nScale = 10.0.pow(5 - northPart.length)

        val eMeters = (eastPart.toIntOrNull() ?: 0) * eScale + (eScale / 2.0)
        val nMeters = (northPart.toIntOrNull() ?: 0) * nScale + (nScale / 2.0)

        // Find 100k grid square easting/northing
        val set = ((zone - 1) % 6) + 1
        val colOriginChar = SET_ORIGIN_COLUMN_LETTERS[set - 1]
        val colOriginIndex = ALPHABET.indexOf(colOriginChar)
        val colLetterIdx = ALPHABET.indexOf(colLetter)

        var colOffset = (colLetterIdx - colOriginIndex)
        while (colOffset < 0) colOffset += ALPHABET.length
        val col100kEasting = (colOffset + 1) * 100_000.0

        val rowLetters20 = "ABCDEFGHJKLMNPQRSTUV"
        val rowOriginChar = SET_ORIGIN_ROW_LETTERS[set - 1]
        val rowOriginIndex = rowLetters20.indexOf(rowOriginChar)
        val rowLetterIdx = rowLetters20.indexOf(rowLetter)

        var rowOffset = (rowLetterIdx - rowOriginIndex)
        while (rowOffset < 0) rowOffset += 20
        var row100kNorthing = rowOffset * 100_000.0

        // Approximate lat of the band to solve 2,000,000 m cycle
        val bandIdx = LATITUDE_BANDS.indexOf(latBand)
        val approxLat = -80.0 + bandIdx * 8.0 + 4.0
        val approxUtm = latLonToUtm(approxLat, (zone * 6.0) - 183.0)
        val approxNorth = approxUtm[1]

        while (row100kNorthing < approxNorth - 1_000_000.0) {
            row100kNorthing += 2_000_000.0
        }
        while (row100kNorthing > approxNorth + 1_000_000.0) {
            row100kNorthing -= 2_000_000.0
        }

        val totalEasting = col100kEasting + eMeters
        val totalNorthing = row100kNorthing + nMeters

        return utmToLatLon(totalEasting, totalNorthing, zone, latBand >= 'N')
    }

    /**
     * Converts UTM coordinates to WGS84 (lat, lon).
     */
    fun utmToLatLon(easting: Double, northing: Double, zone: Int, isNorth: Boolean): Pair<Double, Double> {
        val l0Deg = (zone * 6.0) - 183.0
        val yRel = easting - 500_000.0
        val x = if (isNorth) northing else northing - 10_000_000.0

        // Footprint latitude B1
        var b1 = (x / K0) / (a * A0)
        for (i in 0 until 6) {
            val f = meridianArcLength(b1) - (x / K0)
            val sinB = sin(b1)
            val m = a * (1.0 - e2) / (1.0 - e2 * sinB * sinB).pow(1.5)
            val delta = f / m
            b1 -= delta
            if (abs(delta) < 1e-12) break
        }

        val sinB1 = sin(b1)
        val cosB1 = cos(b1)
        val t1 = tan(b1)
        val t1_2 = t1 * t1
        val t1_4 = t1_2 * t1_2

        val n1 = a / sqrt(1.0 - e2 * sinB1 * sinB1)
        val m1 = a * (1.0 - e2) / (1.0 - e2 * sinB1 * sinB1).pow(1.5)
        val eta1_2 = ePrime2 * cosB1 * cosB1

        val yScaled = yRel / K0
        val y2 = yScaled * yScaled
        val y3 = y2 * yScaled
        val y4 = y2 * y2
        val y5 = y4 * yScaled
        val y6 = y3 * y3

        val bRad = b1 -
                (t1 / m1) * (y2 / (2.0 * n1)) +
                (t1 / m1) * (5.0 + 3.0 * t1_2 + eta1_2 - 9.0 * eta1_2 * t1_2) * (y4 / (24.0 * n1.pow(3))) -
                (t1 / m1) * (61.0 + 90.0 * t1_2 + 45.0 * t1_4) * (y6 / (720.0 * n1.pow(5)))

        val lRad = (1.0 / (n1 * cosB1)) * yScaled -
                (1.0 / (n1 * cosB1)) * (1.0 + 2.0 * t1_2 + eta1_2) * (y3 / (6.0 * n1 * n1)) +
                (1.0 / (n1 * cosB1)) * (5.0 + 28.0 * t1_2 + 24.0 * t1_4 + 6.0 * eta1_2 + 8.0 * eta1_2 * t1_2) * (y5 / (120.0 * n1.pow(4)))

        val latDeg = Math.toDegrees(bRad)
        val lonDeg = l0Deg + Math.toDegrees(lRad)

        return Pair(latDeg, lonDeg)
    }
}
