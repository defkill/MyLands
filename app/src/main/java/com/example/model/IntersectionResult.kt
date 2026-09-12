package com.example.model

/**
 * Result of direct 2-ray azimuth intersection (геодезическая засечка по двум азимутам).
 */
sealed class IntersectionResult {
    data class Success(
        val intersectionPoint: GeoPoint,
        val distance1Meters: Double,
        val distance2Meters: Double,
        val angleBetweenRaysDeg: Double
    ) : IntersectionResult()

    data class RaysParallel(val message: String = "Лучи параллельны или лежат на одной прямой") : IntersectionResult()
    data class RaysDiverge(
        val message: String = "Лучи расходятся (пересечение находится позади исходных точек)"
    ) : IntersectionResult()
    data class Error(val message: String) : IntersectionResult()
}
