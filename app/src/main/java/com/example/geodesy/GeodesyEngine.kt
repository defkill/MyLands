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
     * Computes high-precision intersection in local tangent plane (East-North-Up) based on WGS84 coordinates.
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
        val meanLatRad = Math.toRadians((p1.latitude + p2.latitude) / 2.0)
        
        // Meters per degree latitude and longitude on WGS-84 ellipsoid
        val metersPerDegLat = 111132.954 - 559.822 * cos(2.0 * meanLatRad) + 1.175 * cos(4.0 * meanLatRad)
        val metersPerDegLon = (111412.84 * cos(meanLatRad) - 93.5 * cos(3.0 * meanLatRad)).coerceAtLeast(100.0)

        // Local ENU coordinates with origin at p1:
        // Point 1: (E1 = 0, N1 = 0)
        // Point 2: (E2 = deltaE, N2 = deltaN)
        val deltaE = (p2.longitude - p1.longitude) * metersPerDegLon
        val deltaN = (p2.latitude - p1.latitude) * metersPerDegLat

        val a1Rad = Math.toRadians(azimuth1Deg)
        val a2Rad = Math.toRadians(azimuth2Deg)

        // Direction unit vectors in ENU: (East = sin(az), North = cos(az))
        val sin1 = sin(a1Rad)
        val cos1 = cos(a1Rad)
        val sin2 = sin(a2Rad)
        val cos2 = cos(a2Rad)

        // Determinant of linear system: [ sin1  -sin2 ] [ d1 ] = [ deltaE ]
        //                               [ cos1  -cos2 ] [ d2 ] = [ deltaN ]
        // det = sin1 * (-cos2) - (-sin2) * cos1 = sin2 * cos1 - cos2 * sin1 = sin(a2 - a1) = -sin(a1 - a2)
        val det = sin2 * cos1 - cos2 * sin1

        val azDiff = abs((azimuth1Deg - azimuth2Deg + 360.0) % 360.0)
        if (azDiff < 0.2 || abs(azDiff - 180.0) < 0.2 || abs(det) < 0.005) {
            return IntersectionResult.RaysParallel()
        }

        // Solve for distances along rays:
        // d1 = (deltaN * sin2 - deltaE * cos2) / det
        // d2 = (deltaN * sin1 - deltaE * cos1) / det
        val d1 = (deltaN * sin2 - deltaE * cos2) / det
        val d2 = (deltaN * sin1 - deltaE * cos1) / det

        if (d1 < 0.0 || d2 < 0.0) {
            return IntersectionResult.RaysDiverge()
        }

        // Intersection point in ENU relative to p1
        val interE = d1 * sin1
        val interN = d1 * cos1

        // Convert back to WGS84
        val wgsLatInter = p1.latitude + (interN / metersPerDegLat)
        val wgsLonInter = p1.longitude + (interE / metersPerDegLon)

        val angleBetween = abs(Math.toDegrees(asin(abs(det).coerceAtMost(1.0))))

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
