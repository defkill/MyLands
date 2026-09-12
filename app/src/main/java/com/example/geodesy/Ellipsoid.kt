package com.example.geodesy

import kotlin.math.sqrt

/**
 * Reference ellipsoids used in geodetic calculations.
 */
data class Ellipsoid(
    val a: Double, // Semi-major axis in meters
    val invF: Double // Inverse flattening
) {
    val f: Double = 1.0 / invF
    val b: Double = a * (1.0 - f)
    val e2: Double = 2.0 * f - f * f // First eccentricity squared
    val ePrime2: Double = e2 / (1.0 - e2) // Second eccentricity squared

    companion object {
        val WGS84 = Ellipsoid(
            a = 6378137.0,
            invF = 298.257223563
        )

        val KRASOVSKY_1940 = Ellipsoid(
            a = 6378245.0,
            invF = 298.3
        )
    }
}
