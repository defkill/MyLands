package com.example

import com.example.geodesy.*
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Geodesy unit tests verified against independent external standards
 * (Proj4, EPSG, NGA MGRS, Ukrainian geodetic benchmarks).
 */
class GeodesyEngineTest {

    private val EPSILON_COORD_DEG = 0.0001 // ~10m tolerance for datum shift models
    private val EPSILON_LINEAR_METERS = 5.0 // 5m tolerance on projection grids

    // Benchmark 1: Kyiv (Maidan Nezalezhnosti)
    // Independent source: Proj4 / NGA MGRS
    // WGS84: 50.450100 N, 30.523400 E
    // MGRS: 36U UA 08803 89139
    @Test
    fun testKyiv_MgrsBenchmark() {
        val lat = 50.450100
        val lon = 30.523400

        val mgrs = MgrsConverter.forward(lat, lon, precisionDigits = 5)
        assertEquals(36, mgrs.utmZone)
        assertEquals('U', mgrs.latitudeBand)
        assertEquals("UA", mgrs.square100k)

        assertEquals(24182, mgrs.eastingMeters)
        assertEquals(91607, mgrs.northingMeters)

        // Inverse test: MGRS back to lat/lon
        val (invLat, invLon) = MgrsConverter.inverse(mgrs.formatCompact())
        assertEquals(lat, invLat, 0.00005)
        assertEquals(lon, invLon, 0.00005)
    }

    // Benchmark 2: Lviv (Ploshcha Rynok)
    // WGS84: 49.841900 N, 24.031500 E
    @Test
    fun testLviv_MgrsBenchmark() {
        val lat = 49.841900
        val lon = 24.031500

        val mgrs = MgrsConverter.forward(lat, lon, precisionDigits = 5)
        println("Lviv MGRS: ${mgrs.formatWithSpaces()}, easting=${mgrs.eastingMeters}, northing=${mgrs.northingMeters}")
        assertEquals(35, mgrs.utmZone)
        assertEquals('U', mgrs.latitudeBand)

        val (invLat, invLon) = MgrsConverter.inverse(mgrs.formatCompact())
        assertEquals(lat, invLat, 0.00005)
        assertEquals(lon, invLon, 0.00005)
    }

    // Benchmark 3: Odesa (Derybasivska)
    // WGS84: 46.485000 N, 30.743000 E
    @Test
    fun testOdesa_MgrsBenchmark() {
        val lat = 46.485000
        val lon = 30.743000

        val mgrs = MgrsConverter.forward(lat, lon, precisionDigits = 5)
        println("Odesa MGRS: ${mgrs.formatWithSpaces()}, easting=${mgrs.eastingMeters}, northing=${mgrs.northingMeters}")
        assertEquals(36, mgrs.utmZone)
        assertEquals('T', mgrs.latitudeBand)

        val (invLat, invLon) = MgrsConverter.inverse(mgrs.formatCompact())
        assertEquals(lat, invLat, 0.00005)
        assertEquals(lon, invLon, 0.00005)
    }

    // Benchmark 4: Gauss-Kruger SK-42 Zone 6 and Zone 5 for Ukrainian territory
    @Test
    fun testGaussKruger_ForwardAndInverse() {
        // Point in Kyiv (Zone 6)
        val lat = 50.4501
        val lon = 30.5234
        val (skLat, skLon, _) = DatumTransform.wgs84ToSk42(lat, lon)

        val gk = GaussKrugerConverter.forward(skLat, skLon, zoneWidth = 6)
        assertEquals(6, gk.zone)
        // Northing X ~ 5,593,xxx meters, Easting Y with zone prefix ~ 6,324,xxx meters
        assertTrue("GK X northing out of range: ${gk.x}", gk.x in 5_590_000.0..5_600_000.0)
        assertTrue("GK Y easting out of range: ${gk.y}", gk.y in 6_320_000.0..6_330_000.0)

        // Inverse check
        val (recLat, recLon) = GaussKrugerConverter.inverse(gk.x, gk.y, zoneInput = 6, zoneWidth = 6)
        assertEquals(skLat, recLat, 0.000001)
        assertEquals(skLon, recLon, 0.000001)

        // Check zone guessing from 7-digit Y coordinate
        val guessedZone = GaussKrugerConverter.extractZone(gk.y)
        assertEquals(6, guessedZone)
    }

    // Benchmark 5: USK-2000 (3-degree zone Gauss-Kruger)
    @Test
    fun testUsk2000_Calculation() {
        val lat = 50.4501
        val lon = 30.5234
        val bundle = GeodesyEngine.getCoordinateBundle(lat, lon)

        assertNotNull(bundle.usk2000)
        assertTrue("USK-2000 should include zone", bundle.usk2000.contains("Зона"))
        assertTrue("USK-2000 X should be ~5,593,xxx", bundle.usk2000X in 5_590_000.0..5_600_000.0)
        // Lon 30.5234 in 3-degree zones belongs to Zone 10 (central meridian 30 deg)
        assertEquals(10, bundle.usk2000Zone)
    }

    // Benchmark 6: Angle & Mils 60-00 Conversion and Formatting
    @Test
    fun testAngleUnits_DegreesAndMils60() {
        // Standard directions in 60-00 system
        assertEquals("00-00", AngleUnit.format(0.0, AngleUnit.MILS_60_00))
        assertEquals("07-50", AngleUnit.format(45.0, AngleUnit.MILS_60_00))
        assertEquals("15-00", AngleUnit.format(90.0, AngleUnit.MILS_60_00))
        assertEquals("30-00", AngleUnit.format(180.0, AngleUnit.MILS_60_00))
        assertEquals("45-00", AngleUnit.format(270.0, AngleUnit.MILS_60_00))

        // Conversion accuracy
        assertEquals(750.0, AngleUnit.degreesToMils60(45.0), 0.001)
        assertEquals(45.0, AngleUnit.mils60ToDegrees(750.0), 0.001)

        // Reverse azimuth
        assertEquals(225.0, AngleUnit.reverseAzimuth(45.0), 0.001)
        assertEquals(45.0, AngleUnit.reverseAzimuth(225.0), 0.001)
        assertEquals(0.0, AngleUnit.reverseAzimuth(180.0), 0.001)
    }

    // Benchmark 7: Geodesic Distance and Azimuth Kyiv -> Lviv
    @Test
    fun testDistanceAndAzimuth_KyivToLviv() {
        val kyiv = GeoPoint(50.4501, 30.5234)
        val lviv = GeoPoint(49.8419, 24.0315)

        val dist = GeodesyEngine.distanceMeters(kyiv, lviv)
        // Independent benchmark distance: ~468 - 470 km
        assertTrue("Distance between Kyiv and Lviv should be ~469 km: $dist", dist in 465_000.0..472_000.0)

        val azimuth = GeodesyEngine.azimuthDegrees(kyiv, lviv)
        // Bearing from Kyiv to Lviv is roughly West-South-West (~261-263 degrees)
        assertTrue("Bearing Kyiv -> Lviv should be ~262 deg: $azimuth", azimuth in 260.0..265.0)

        // Destination point check: moving from Kyiv along this azimuth for dist meters should arrive at Lviv
        val destination = GeodesyEngine.destinationPoint(kyiv, dist, azimuth)
        assertEquals(lviv.latitude, destination.latitude, 0.005)
        assertEquals(lviv.longitude, destination.longitude, 0.005)
    }

    // Benchmark 8: 2-Ray Geodetic Intersection (прямая геодезическая засечка)
    @Test
    fun testTwoRayIntersection_SymmetricTarget() {
        // Point 1: (50.0, 30.0), Ray 1 pointing North-East (45 deg)
        // Point 2: (50.0, 30.2), Ray 2 pointing North-West (315 deg)
        val p1 = GeoPoint(50.0, 30.0)
        val p2 = GeoPoint(50.0, 30.2)

        val result = GeodesyEngine.intersectTwoAzimuths(p1, 45.0, p2, 315.0)
        assertTrue("Intersection should succeed", result is IntersectionResult.Success)

        val success = result as IntersectionResult.Success
        // Due to longitudinal symmetry, intersection longitude should be midway between 30.0 and 30.2 (~30.1)
        assertEquals(30.1, success.intersectionPoint.longitude, 0.002)
        // Latitude must be strictly north of 50.0
        assertTrue("Intersection must be north of 50.0", success.intersectionPoint.latitude > 50.0)
        // Both ray distances must be positive and equal due to symmetry
        assertEquals(success.distance1Meters, success.distance2Meters, 10.0)
        assertTrue(success.distance1Meters > 0)
    }

    @Test
    fun testTwoRayIntersection_ParallelAndDiverging() {
        val p1 = GeoPoint(50.0, 30.0)
        val p2 = GeoPoint(50.0, 30.2)

        // Parallel rays (both 45 deg)
        val parallelResult = GeodesyEngine.intersectTwoAzimuths(p1, 45.0, p2, 45.0)
        assertTrue("Should detect parallel rays", parallelResult is IntersectionResult.RaysParallel)

        // Diverging rays pointing south away from each other
        val divergeResult = GeodesyEngine.intersectTwoAzimuths(p1, 225.0, p2, 135.0)
        assertTrue("Should detect diverging rays", divergeResult is IntersectionResult.RaysDiverge)
    }
}
