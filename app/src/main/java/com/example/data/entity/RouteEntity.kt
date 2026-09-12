package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.geodesy.GeodesyEngine
import com.example.model.AngleUnit
import java.util.Locale

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val waypointIdsCsv: String, // e.g. "1,4,2,7"
    val totalDistanceMeters: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun parseWaypointIds(): List<Long> {
        if (waypointIdsCsv.isBlank()) return emptyList()
        return waypointIdsCsv.split(",").mapNotNull { it.trim().toLongOrNull() }
    }
}

/**
 * Calculated segment between two sequential waypoints in a route.
 */
data class RouteLeg(
    val index: Int,
    val fromPoint: WaypointEntity,
    val toPoint: WaypointEntity,
    val distanceMeters: Double,
    val forwardAzimuthDeg: Double,
    val backwardAzimuthDeg: Double
) {
    companion object {
        fun create(index: Int, from: WaypointEntity, to: WaypointEntity): RouteLeg {
            val dist = GeodesyEngine.distanceMeters(
                from.latitude, from.longitude,
                to.latitude, to.longitude
            )
            val forwardAz = GeodesyEngine.azimuthDegrees(
                from.latitude, from.longitude,
                to.latitude, to.longitude
            )
            val backwardAz = AngleUnit.reverseAzimuth(forwardAz)
            return RouteLeg(
                index = index,
                fromPoint = from,
                toPoint = to,
                distanceMeters = dist,
                forwardAzimuthDeg = forwardAz,
                backwardAzimuthDeg = backwardAz
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
}
