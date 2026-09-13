package com.example.model

import com.example.data.entity.WaypointEntity

/**
 * A single ray (or bounded segment) cast from a waypoint along a given azimuth.
 *
 * When [lengthMeters] is null the ray is treated as unbounded and is drawn out to a long
 * default length; when set, it is a segment of that exact length and its end can be turned
 * into a waypoint directly.
 */
data class AzimuthRay(
    val origin: WaypointEntity,
    val azimuthDeg: Double,
    val lengthMeters: Double? = null
) {
    val originPoint: GeoPoint
        get() = GeoPoint(origin.latitude, origin.longitude, origin.altitudeMeters)

    /** Effective draw length: real length for segments, long fallback for open rays. */
    fun effectiveLengthMeters(): Double = lengthMeters ?: DEFAULT_RAY_LENGTH_METERS

    companion object {
        const val DEFAULT_RAY_LENGTH_METERS = 25_000.0
    }
}

/**
 * State of the triangulation tool: up to two azimuth rays whose crossing point can be
 * promoted into a new waypoint.
 */
data class TriangulationState(
    val isActive: Boolean = false,
    val rays: List<AzimuthRay> = emptyList(),
    val result: IntersectionResult? = null
) {
    val canIntersect: Boolean get() = rays.size >= 2

    fun intersectionPoint(): GeoPoint? =
        (result as? IntersectionResult.Success)?.intersectionPoint
}
