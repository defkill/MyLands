package com.example.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.model.GeoPoint

/**
 * Saved waypoint entity in Room database.
 */
@Entity(
    tableName = "waypoints",
    indices = [
        Index(value = ["latitude", "longitude"]),
        Index(value = ["groupName"])
    ]
)
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val colorArgb: Int = 0xFFFF5722.toInt(),
    val groupName: String = "Общие",
    val isSelectedInRoute: Boolean = false
) {
    fun toGeoPoint(): GeoPoint = GeoPoint(
        latitude = latitude,
        longitude = longitude,
        altitude = altitudeMeters,
        timestamp = timestamp
    )
}
