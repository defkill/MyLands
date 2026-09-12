package com.example.model

/**
 * Basic geographic point in WGS84 with optional altitude and accuracy.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)
