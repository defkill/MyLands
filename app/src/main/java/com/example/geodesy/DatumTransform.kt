package com.example.geodesy

import kotlin.math.*

/**
 * Spatial coordinate in geocentric Cartesian (ECEF) XYZ system.
 */
data class EcefPoint(val x: Double, val y: Double, val z: Double)

/**
 * Helmert 7-parameter datum transformation.
 */
data class HelmertParameters(
    val dx: Double, // Translation X in meters
    val dy: Double, // Translation Y in meters
    val dz: Double, // Translation Z in meters
    val rxArcSec: Double, // Rotation X in arc-seconds
    val ryArcSec: Double, // Rotation Y in arc-seconds
    val rzArcSec: Double, // Rotation Z in arc-seconds
    val scalePpm: Double // Scale factor in parts per million (10^-6)
) {
    companion object {
        val SEC_TO_RAD = Math.PI / (180.0 * 3600.0)

        // WGS-84 -> SK-42 (ГОСТ 32453-2013 / ГОСТ Р 51794-2008)
        val WGS84_TO_SK42 = HelmertParameters(
            dx = 23.57,
            dy = -140.95,
            dz = -79.80,
            rxArcSec = 0.0,
            ryArcSec = -0.35,
            rzArcSec = -0.79,
            scalePpm = -0.22
        )

        // WGS-84 -> USK-2000 (Постанова Кабінету Міністрів України № 1259)
        val WGS84_TO_USK2000 = HelmertParameters(
            dx = 24.37,
            dy = -131.21,
            dz = -97.98,
            rxArcSec = 0.0,
            ryArcSec = -0.34,
            rzArcSec = -0.79,
            scalePpm = -0.22
        )
    }

    val rxRad: Double = rxArcSec * SEC_TO_RAD
    val ryRad: Double = ryArcSec * SEC_TO_RAD
    val rzRad: Double = rzArcSec * SEC_TO_RAD
    val scaleFactor: Double = 1.0 + (scalePpm * 1e-6)

    fun transform(p: EcefPoint): EcefPoint {
        val x = dx + scaleFactor * (p.x - rzRad * p.y + ryRad * p.z)
        val y = dy + scaleFactor * (rzRad * p.x + p.y - rxRad * p.z)
        val z = dz + scaleFactor * (-ryRad * p.x + rxRad * p.y + p.z)
        return EcefPoint(x, y, z)
    }

    fun inverseTransform(p: EcefPoint): EcefPoint {
        val invScale = 1.0 / scaleFactor
        val px = (p.x - dx) * invScale
        val py = (p.y - dy) * invScale
        val pz = (p.z - dz) * invScale

        val x = px + rzRad * py - ryRad * pz
        val y = -rzRad * px + py + rxRad * pz
        val z = ryRad * px - rxRad * py + pz
        return EcefPoint(x, y, z)
    }
}

/**
 * Converts between geodetic (lat, lon, h) and Cartesian ECEF (X, Y, Z) coordinates.
 */
object DatumTransform {

    fun geodeticToEcef(latDeg: Double, lonDeg: Double, hMeters: Double, ellipsoid: Ellipsoid): EcefPoint {
        val latRad = Math.toRadians(latDeg)
        val lonRad = Math.toRadians(lonDeg)

        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val sinLon = sin(lonRad)
        val cosLon = cos(lonRad)

        val n = ellipsoid.a / sqrt(1.0 - ellipsoid.e2 * sinLat * sinLat)

        val x = (n + hMeters) * cosLat * cosLon
        val y = (n + hMeters) * cosLat * sinLon
        val z = (n * (1.0 - ellipsoid.e2) + hMeters) * sinLat

        return EcefPoint(x, y, z)
    }

    fun ecefToGeodetic(p: EcefPoint, ellipsoid: Ellipsoid): Triple<Double, Double, Double> {
        val lonRad = atan2(p.y, p.x)
        val d = sqrt(p.x * p.x + p.y * p.y)

        if (d < 1e-6) {
            val latRad = if (p.z >= 0) Math.PI / 2.0 else -Math.PI / 2.0
            val h = abs(p.z) - ellipsoid.b
            return Triple(Math.toDegrees(latRad), Math.toDegrees(lonRad), h)
        }

        // Bowring's closed-form method
        val theta = atan2(p.z * ellipsoid.a, d * ellipsoid.b)
        val sinTheta = sin(theta)
        val cosTheta = cos(theta)

        val latRad = atan2(
            p.z + ellipsoid.ePrime2 * ellipsoid.b * sinTheta * sinTheta * sinTheta,
            d - ellipsoid.e2 * ellipsoid.a * cosTheta * cosTheta * cosTheta
        )

        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val n = ellipsoid.a / sqrt(1.0 - ellipsoid.e2 * sinLat * sinLat)
        val h = (d / cosLat) - n

        return Triple(Math.toDegrees(latRad), Math.toDegrees(lonRad), h)
    }

    /**
     * Converts WGS84 coordinates to Krasovsky-based SK-42 geodetic coordinates.
     */
    fun wgs84ToSk42(lat: Double, lon: Double, h: Double = 0.0): Triple<Double, Double, Double> {
        val ecefWgs = geodeticToEcef(lat, lon, h, Ellipsoid.WGS84)
        val ecefSk42 = HelmertParameters.WGS84_TO_SK42.transform(ecefWgs)
        return ecefToGeodetic(ecefSk42, Ellipsoid.KRASOVSKY_1940)
    }

    /**
     * Converts SK-42 geodetic coordinates to WGS84.
     */
    fun sk42ToWgs84(lat: Double, lon: Double, h: Double = 0.0): Triple<Double, Double, Double> {
        val ecefSk42 = geodeticToEcef(lat, lon, h, Ellipsoid.KRASOVSKY_1940)
        val ecefWgs = HelmertParameters.WGS84_TO_SK42.inverseTransform(ecefSk42)
        return ecefToGeodetic(ecefWgs, Ellipsoid.WGS84)
    }

    /**
     * Converts WGS84 coordinates to Krasovsky-based USK-2000 geodetic coordinates.
     */
    fun wgs84ToUsk2000(lat: Double, lon: Double, h: Double = 0.0): Triple<Double, Double, Double> {
        val ecefWgs = geodeticToEcef(lat, lon, h, Ellipsoid.WGS84)
        val ecefUsk = HelmertParameters.WGS84_TO_USK2000.transform(ecefWgs)
        return ecefToGeodetic(ecefUsk, Ellipsoid.KRASOVSKY_1940)
    }

    /**
     * Converts USK-2000 geodetic coordinates to WGS84.
     */
    fun usk2000ToWgs84(lat: Double, lon: Double, h: Double = 0.0): Triple<Double, Double, Double> {
        val ecefUsk = geodeticToEcef(lat, lon, h, Ellipsoid.KRASOVSKY_1940)
        val ecefWgs = HelmertParameters.WGS84_TO_USK2000.inverseTransform(ecefUsk)
        return ecefToGeodetic(ecefWgs, Ellipsoid.WGS84)
    }
}
