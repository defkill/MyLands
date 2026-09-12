package com.example.model

import com.example.data.entity.RouteLeg
import com.example.data.entity.WaypointEntity
import com.example.geodesy.GeodesyEngine
import java.util.Locale

/**
 * Explicit active map tool mode as requested:
 * NONE, PLACE_WAYPOINT_PENDING, RULER, INTERSECTION.
 */
enum class ActiveMapTool {
    NONE,
    PLACE_WAYPOINT_PENDING,
    RULER,
    INTERSECTION
}

/**
 * State of interactive map ruler tool.
 */
data class RulerState(
    val isActive: Boolean = false,
    val startPoint: GeoPoint? = null,
    val endPoint: GeoPoint? = null,
    val distanceMeters: Double = 0.0,
    val azimuthDeg: Double = 0.0,
    val reverseAzimuthDeg: Double = 0.0
) {
    companion object {
        fun calculate(start: GeoPoint, end: GeoPoint): RulerState {
            val dist = GeodesyEngine.distanceMeters(start, end)
            val az = GeodesyEngine.azimuthDegrees(start, end)
            val revAz = AngleUnit.reverseAzimuth(az)
            return RulerState(
                isActive = true,
                startPoint = start,
                endPoint = end,
                distanceMeters = dist,
                azimuthDeg = az,
                reverseAzimuthDeg = revAz
            )
        }
    }

    fun formatDistance(): String {
        return if (distanceMeters >= 1000.0) {
            String.format(Locale.US, "%.2f км", distanceMeters / 1000.0)
        } else {
            String.format(Locale.US, "%.0f м", distanceMeters)
        }
    }

    fun formatAzimuth(unit: AngleUnit = AngleUnit.DEGREES_360): String {
        return AngleUnit.format(azimuthDeg, unit)
    }

    fun formatReverseAzimuth(unit: AngleUnit = AngleUnit.DEGREES_360): String {
        return AngleUnit.format(reverseAzimuthDeg, unit)
    }
}

/**
 * State of 2-ray azimuth intersection tool (засечка по двум направлениям).
 */
data class IntersectionToolState(
    val isActive: Boolean = false,
    val point1: GeoPoint? = null,
    val azimuth1Deg: Double = 0.0,
    val point2: GeoPoint? = null,
    val azimuth2Deg: Double = 0.0,
    val result: IntersectionResult? = null
)

/**
 * State of interactive route builder by points.
 */
data class RouteBuilderState(
    val isActive: Boolean = false,
    val routeName: String = "Новый маршрут",
    val selectedWaypoints: List<WaypointEntity> = emptyList(),
    val legs: List<RouteLeg> = emptyList(),
    val totalDistanceMeters: Double = 0.0
) {
    fun formatTotalDistance(): String {
        return if (totalDistanceMeters >= 1000.0) {
            String.format(Locale.US, "%.2f км", totalDistanceMeters / 1000.0)
        } else {
            String.format(Locale.US, "%.0f м", totalDistanceMeters)
        }
    }
}
