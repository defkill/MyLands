package com.example.model

/**
 * User navigation and app settings.
 * Defaults follow user requirements:
 * 1. Degrees 360° is default angle unit (Mils 60-00 is an optional toggle).
 * 2. Metric system.
 */
data class UserPreferences(
    val defaultCoordinateSystem: CoordinateSystem = CoordinateSystem.MGRS,
    val defaultAngleUnit: AngleUnit = AngleUnit.DEGREES_360,
    val pdrStepLengthMeters: Float = 0.75f,
    val showMilitaryGrid: Boolean = true,
    val showMagneticDeclination: Boolean = true,
    val mapTileSourceId: String = "osm_standard",
    val lastMapLat: Double? = null,
    val lastMapLon: Double? = null,
    val lastMapZoom: Double? = null
)
