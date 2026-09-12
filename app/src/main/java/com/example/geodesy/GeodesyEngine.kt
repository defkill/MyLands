package com.example.geodesy

import com.example.model.CoordinateBundle
import com.example.model.GeoPoint
import com.example.model.IntersectionResult
import java.util.Locale
import kotlin.math.*

/**
 * Primary geodetic calculation and conversion engine.
 */
object GeodesyEngine {

    private const val EARTH_MEAN_RADIUS_METERS = 6371008.8

    /**
     * Converts a WGS84 point to all supported coordinate systems.
     */
    fun getCoordinateBundle(
        lat: Double,
        lon: Double,
        altitudeMeters: Double? = null,
        gaussKrugerZoneOverride: Int? = null
    ): CoordinateBundle {
        val wgsDec = formatDecimal(lat, lon)
        val wgsDms = formatDms(lat, lon)

        // MGRS
        val mgrs = try {
            MgrsConverter.forward(lat, lon, precisionDigits = 5).formatWithSpaces()
        } catch (e: Exception) {
            "MGRS: N/A"
        }

        // Gauss-Kruger (SK-42)
        val (sk42Lat, sk42Lon, _) = DatumTransform.wgs84ToSk42(lat, lon, altitudeMeters ?: 0.0)
        val gk = GaussKrugerConverter.forward(sk42Lat, sk42Lon, zoneOverride = gaussKrugerZoneOverride, zoneWidth = 6)
        val gkFormatted = String.format(
            Locale.US,
            "X: %d m, Y: %d m (Зона %d)",
            gk.x.roundToLong(),
            gk.y.roundToLong(),
            gk.zone
        )

        // USK-2000 (3-degree zone Gauss-Kruger on Krasovsky with USK-2000 datum)
        val (uskLat, uskLon, _) = DatumTransform.wgs84ToUsk2000(lat, lon, altitudeMeters ?: 0.0)
        val usk = GaussKrugerConverter.forward(uskLat, uskLon, zoneOverride = null, zoneWidth = 3)
        val uskFormatted = String.format(
            Locale.US,
            "X: %d m, Y: %d m (Зона %d)",
            usk.x.roundToLong(),
            usk.y.roundToLong(),
            usk.zone
        )

        return CoordinateBundle(
            latitude = lat,
            longitude = lon,
            altitudeMeters = altitudeMeters,
            wgs84Decimal = wgsDec,
            wgs84Dms = wgsDms,
            mgrs = mgrs,
            gaussKruger = gkFormatted,
            gaussKrugerZone = gk.zone,
            gaussKrugerX = gk.x,
            gaussKrugerY = gk.y,
            usk2000 = uskFormatted,
            usk2000Zone = usk.zone,
            usk2000X = usk.x,
            usk2000Y = usk.y
        )
    }

    /**
     * Formats latitude and longitude as decimal degrees with directional letters.
     */
    fun formatDecimal(lat: Double, lon: Double): String {
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        return String.format(
            Locale.US,
            "%.6f° %s, %.6f° %s",
            abs(lat), latDir,
            abs(lon), lonDir
        )
    }

    /**
     * Formats latitude and longitude in Degrees, Minutes, Seconds (DMS).
     */
    fun formatDms(lat: Double, lon: Double): String {
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"

        val absLat = abs(lat)
        val latD = absLat.toInt()
        val latMTotal = (absLat - latD) * 60.0
        val latM = latMTotal.toInt()
        val latS = (latMTotal - latM) * 60.0

        val absLon = abs(lon)
        val lonD = absLon.toInt()
        val lonMTotal = (absLon - lonD) * 60.0
        val lonM = lonMTotal.toInt()
        val lonS = (lonMTotal - lonM) * 60.0

        return String.format(
            Locale.US,
            "%d°%02d'%04.1f\" %s, %d°%02d'%04.1f\" %s",
            latD, latM, latS, latDir,
            lonD, lonM, lonS, lonDir
        )
    }

    /**
     * Calculates great-circle distance between two points in meters.
     */
    fun distanceMeters(p1: GeoPoint, p2: GeoPoint): Double {
        return distanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0).pow(2) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_MEAN_RADIUS_METERS * c
    }

    /**
     * Calculates initial forward bearing (azimuth) from p1 to p2 in degrees [0..360).
     */
    fun azimuthDegrees(p1: GeoPoint, p2: GeoPoint): Double {
        return azimuthDegrees(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
    }

    fun azimuthDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)

        var az = Math.toDegrees(atan2(y, x))
        az = (az % 360.0 + 360.0) % 360.0
        return az
    }

    /**
     * Computes the destination point given starting point, distance in meters, and azimuth in degrees.
     */
    fun destinationPoint(origin: GeoPoint, distanceMeters: Double, azimuthDeg: Double): GeoPoint {
        val distRatio = distanceMeters / EARTH_MEAN_RADIUS_METERS
        val azRad = Math.toRadians(azimuthDeg)
        val rLat1 = Math.toRadians(origin.latitude)
        val rLon1 = Math.toRadians(origin.longitude)

        val rLat2 = asin(sin(rLat1) * cos(distRatio) + cos(rLat1) * sin(distRatio) * cos(azRad))
        val rLon2 = rLon1 + atan2(
            sin(azRad) * sin(distRatio) * cos(rLat1),
            cos(distRatio) - sin(rLat1) * sin(rLat2)
        )

        val lat2 = Math.toDegrees(rLat2)
        var lon2 = Math.toDegrees(rLon2)
        lon2 = (lon2 + 540.0) % 360.0 - 180.0

        return GeoPoint(lat2, lon2, origin.altitude)
    }

    /**
     * Direct 2-ray azimuth intersection (геодезическая прямая засечка по двум направлениям).
     * Computes intersection in a conformal projected plane (Gauss-Kruger) to preserve angles and linear accuracy.
     *
     * @param p1 Starting point 1 (WGS84)
     * @param azimuth1Deg Azimuth from point 1 to target in degrees [0..360)
     * @param p2 Starting point 2 (WGS84)
     * @param azimuth2Deg Azimuth from point 2 to target in degrees [0..360)
     */
    fun intersectTwoAzimuths(
        p1: GeoPoint,
        azimuth1Deg: Double,
        p2: GeoPoint,
        azimuth2Deg: Double
    ): IntersectionResult {
        // Project both points into Gauss-Kruger using the average zone
        val avgLon = (p1.longitude + p2.longitude) / 2.0
        val zone = GaussKrugerConverter.getZone6(avgLon)

        // Convert WGS84 -> SK42 -> GK
        val (sk42Lat1, sk42Lon1, _) = DatumTransform.wgs84ToSk42(p1.latitude, p1.longitude)
        val (sk42Lat2, sk42Lon2, _) = DatumTransform.wgs84ToSk42(p2.latitude, p2.longitude)

        val gk1 = GaussKrugerConverter.forward(sk42Lat1, sk42Lon1, zoneOverride = zone)
        val gk2 = GaussKrugerConverter.forward(sk42Lat2, sk42Lon2, zoneOverride = zone)

        // Meridian convergence (сближение меридианов) gamma = (L - L0) * sin(B)
        val l0 = GaussKrugerConverter.getCentralMeridian6(zone)
        val gamma1Deg = (sk42Lon1 - l0) * sin(Math.toRadians(sk42Lat1))
        val gamma2Deg = (sk42Lon2 - l0) * sin(Math.toRadians(sk42Lat2))

        // Grid azimuth (дирекционный угол) = True azimuth - meridian convergence
        val gridAz1 = (azimuth1Deg - gamma1Deg + 360.0) % 360.0
        val gridAz2 = (azimuth2Deg - gamma2Deg + 360.0) % 360.0

        val a1Rad = Math.toRadians(gridAz1)
        val a2Rad = Math.toRadians(gridAz2)

        // In Gauss-Kruger: X = North, Y = East.
        // Direction vectors: (cos(az), sin(az))
        val cos1 = cos(a1Rad)
        val sin1 = sin(a1Rad)
        val cos2 = cos(a2Rad)
        val sin2 = sin(a2Rad)

        // Linear system:
        // cos1 * d1 - cos2 * d2 = deltaX
        // sin1 * d1 - sin2 * d2 = deltaY
        val deltaX = gk2.x - gk1.x
        val deltaY = gk2.y - gk1.y

        // Determinant = sin(a1 - a2)
        val det = sin(a1Rad - a2Rad)

        val azDiff = abs((azimuth1Deg - azimuth2Deg + 360.0) % 360.0)
        if (azDiff < 0.2 || abs(azDiff - 180.0) < 0.2 || abs(det) < 0.005) {
            return IntersectionResult.RaysParallel()
        }

        val d1 = (-deltaX * sin2 + deltaY * cos2) / det
        val d2 = (-deltaX * sin1 + deltaY * cos1) / det

        if (d1 < 0 || d2 < 0) {
            return IntersectionResult.RaysDiverge()
        }

        // Intersection in Gauss-Kruger
        val xInter = gk1.x + d1 * cos1
        val yInter = gk1.y + d1 * sin1

        // Inverse Gauss-Kruger -> SK42 -> WGS84
        val (sk42LatInter, sk42LonInter) = GaussKrugerConverter.inverse(xInter, yInter, zoneInput = zone)
        val (wgsLatInter, wgsLonInter, _) = DatumTransform.sk42ToWgs84(sk42LatInter, sk42LonInter)

        val angleBetween = abs(Math.toDegrees(asin(abs(det))))

        val approxAlt = if (p1.altitude != null && p2.altitude != null) {
            (p1.altitude + p2.altitude) / 2.0
        } else p1.altitude ?: p2.altitude

        return IntersectionResult.Success(
            intersectionPoint = GeoPoint(wgsLatInter, wgsLonInter, approxAlt),
            distance1Meters = d1,
            distance2Meters = d2,
            angleBetweenRaysDeg = angleBetween
        )
    }

    /**
     * Converts USK-2000 coordinates (Northing X, Easting Y, and optional 3° zone) to WGS84 GeoPoint.
     */
    fun usk2000ToWgs84(x: Double, y: Double, zoneInput: Int? = null): GeoPoint {
        val effectiveZone = zoneInput ?: GaussKrugerConverter.extractZone(y) ?: 10
        val (uskLat, uskLon) = GaussKrugerConverter.inverse(x, y, zoneInput = effectiveZone, zoneWidth = 3)
        val (wgsLat, wgsLon, _) = DatumTransform.usk2000ToWgs84(uskLat, uskLon)
        return GeoPoint(wgsLat, wgsLon)
    }

    /**
     * Converts SK-42 / Gauss-Kruger coordinates (Northing X, Easting Y, and optional 6° zone) to WGS84 GeoPoint.
     */
    fun gaussKrugerToWgs84(x: Double, y: Double, zoneInput: Int? = null): GeoPoint {
        val effectiveZone = zoneInput ?: GaussKrugerConverter.extractZone(y) ?: 6
        val (skLat, skLon) = GaussKrugerConverter.inverse(x, y, zoneInput = effectiveZone, zoneWidth = 6)
        val (wgsLat, wgsLon, _) = DatumTransform.sk42ToWgs84(skLat, skLon)
        return GeoPoint(wgsLat, wgsLon)
    }
}
