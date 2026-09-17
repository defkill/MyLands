package com.example.geodesy

import kotlin.math.*

/**
 * Result of Gauss-Kruger projection.
 * [x] - Northing from equator in meters
 * [y] - Easting with false easting and zone prefix (e.g. 6324512.0)
 * [zone] - Gauss-Kruger zone number
 * [centralMeridianDeg] - Central meridian of the zone in degrees
 */
data class GaussKrugerPoint(
    val x: Double,
    val y: Double,
    val zone: Int,
    val centralMeridianDeg: Double,
    val zoneWidthDeg: Int = 6
)

/**
 * Implements high-precision Gauss-Kruger projection on Krasovsky ellipsoid
 * for standard 6-degree and 3-degree zones.
 */
object GaussKrugerConverter {

    private val ELLIPSOID = Ellipsoid.KRASOVSKY_1940
    private val a = ELLIPSOID.a
    private val e2 = ELLIPSOID.e2
    private val ePrime2 = ELLIPSOID.ePrime2

    // Meridian arc expansion coefficients for Krasovsky 1940
    private val e4 = e2 * e2
    private val e6 = e4 * e2
    private val e8 = e6 * e2

    private val A0 = 1.0 - (e2 / 4.0) - (3.0 * e4 / 64.0) - (5.0 * e6 / 256.0) - (175.0 * e8 / 16384.0)
    private val A2 = (3.0 * e2 / 8.0) + (3.0 * e4 / 32.0) + (45.0 * e6 / 1024.0) + (105.0 * e8 / 4096.0)
    private val A4 = (15.0 * e4 / 256.0) + (45.0 * e6 / 1024.0) + (525.0 * e8 / 16384.0)
    private val A6 = (35.0 * e6 / 3072.0) + (175.0 * e8 / 12288.0)
    private val A8 = 315.0 * e8 / 131072.0

    /**
     * Meridian arc length from equator to latitude B in radians.
     */
    fun meridianArcLength(bRad: Double): Double {
        return a * (A0 * bRad -
                A2 * sin(2.0 * bRad) +
                A4 * sin(4.0 * bRad) -
                A6 * sin(6.0 * bRad) +
                A8 * sin(8.0 * bRad))
    }

    /**
     * Determines 6-degree zone number from longitude in degrees.
     */
    fun getZone6(lonDeg: Double): Int {
        var lon = lonDeg
        while (lon < 0.0) lon += 360.0
        while (lon >= 360.0) lon -= 360.0
        return (lon / 6.0).toInt() + 1
    }

    /**
     * Central meridian of 6-degree zone.
     */
    fun getCentralMeridian6(zone: Int): Double {
        return (zone * 6.0) - 3.0
    }

    /**
     * Determines 3-degree zone number from longitude in degrees (used in USK-2000).
     */
    fun getZone3(lonDeg: Double): Int {
        var lon = lonDeg
        while (lon < 0.0) lon += 360.0
        while (lon >= 360.0) lon -= 360.0
        return ((lon - 1.5) / 3.0).toInt() + 1
    }

    /**
     * Central meridian of 3-degree zone.
     */
    fun getCentralMeridian3(zone: Int): Double {
        return zone * 3.0
    }

    /**
     * Projects geodetic coordinates (lat, lon) on Krasovsky ellipsoid to Gauss-Kruger.
     * @param latDeg Latitude in degrees
     * @param lonDeg Longitude in degrees
     * @param zoneOverride Optional zone override (if null, auto-calculated)
     * @param zoneWidth Zone width (6 or 3 degrees)
     */
    fun forward(
        latDeg: Double,
        lonDeg: Double,
        zoneOverride: Int? = null,
        zoneWidth: Int = 6
    ): GaussKrugerPoint {
        val zone = zoneOverride ?: if (zoneWidth == 3) getZone3(lonDeg) else getZone6(lonDeg)
        val l0Deg = if (zoneWidth == 3) getCentralMeridian3(zone) else getCentralMeridian6(zone)

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

        // Northing X
        val x = x0 +
                n * sinB * cosB * (l2 / 2.0) +
                n * sinB * (cosB.pow(3)) * (5.0 - t2 + 9.0 * eta2 + 4.0 * eta2 * eta2) * (l4 / 24.0) +
                n * sinB * (cosB.pow(5)) * (61.0 - 58.0 * t2 + t4 + 270.0 * eta2 - 330.0 * t2 * eta2) * (l6 / 720.0)

        // Easting y relative to central meridian
        val yRel = n * cosB * lRad +
                n * (cosB.pow(3)) * (1.0 - t2 + eta2) * (l3 / 6.0) +
                n * (cosB.pow(5)) * (5.0 - 18.0 * t2 + t4 + 14.0 * eta2 - 58.0 * t2 * eta2) * (l5 / 120.0)

        // Add 500,000 false easting and zone prefix
        val y = (zone * 1_000_000.0) + 500_000.0 + yRel

        return GaussKrugerPoint(
            x = x,
            y = y,
            zone = zone,
            centralMeridianDeg = l0Deg,
            zoneWidthDeg = zoneWidth
        )
    }

    /**
     * Converts Gauss-Kruger coordinates back to geodetic coordinates (lat, lon) on Krasovsky ellipsoid.
     */
    fun inverse(
        x: Double,
        y: Double,
        zoneInput: Int? = null,
        zoneWidth: Int = 6
    ): Pair<Double, Double> {
        val zone = if (zoneInput != null) {
            zoneInput
        } else if (y >= 1_000_000.0) {
            (y / 1_000_000.0).toInt()
        } else {
            throw IllegalArgumentException("GaussKruger inverse: zone is ambiguous — Y has no zone prefix and zoneInput was not provided")
        }
        val l0Deg = if (zoneWidth == 3) getCentralMeridian3(zone) else getCentralMeridian6(zone)

        val falseEasting = (zone * 1_000_000.0) + 500_000.0
        val yRel = if (y >= 1_000_000.0) y - falseEasting else y - 500_000.0

        // Find footprint latitude B1 such that meridianArcLength(B1) == x
        var b1 = x / (a * A0)
        for (i in 0 until 6) {
            val f = meridianArcLength(b1) - x
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

        val y2 = yRel * yRel
        val y3 = y2 * yRel
        val y4 = y2 * y2
        val y5 = y4 * yRel
        val y6 = y3 * y3

        val bRad = b1 -
                (t1 / m1) * (y2 / (2.0 * n1)) +
                (t1 / m1) * (5.0 + 3.0 * t1_2 + eta1_2 - 9.0 * eta1_2 * t1_2) * (y4 / (24.0 * n1.pow(3))) -
                (t1 / m1) * (61.0 + 90.0 * t1_2 + 45.0 * t1_4) * (y6 / (720.0 * n1.pow(5)))

        val lRad = (1.0 / (n1 * cosB1)) * yRel -
                (1.0 / (n1 * cosB1)) * (1.0 + 2.0 * t1_2 + eta1_2) * (y3 / (6.0 * n1 * n1)) +
                (1.0 / (n1 * cosB1)) * (5.0 + 28.0 * t1_2 + 24.0 * t1_4 + 6.0 * eta1_2 + 8.0 * eta1_2 * t1_2) * (y5 / (120.0 * n1.pow(4)))

        val latDeg = Math.toDegrees(bRad)
        val lonDeg = l0Deg + Math.toDegrees(lRad)

        return Pair(latDeg, lonDeg)
    }

    /**
     * Extracts zone from Y coordinate if it has the 7-digit standard format (e.g. 6324512 -> zone 6).
     */
    fun extractZone(y: Double): Int? {
        if (y >= 1_000_000.0) {
            val z = (y / 1_000_000.0).toInt()
            if (z in 1..60) return z
        }
        return null
    }
}
