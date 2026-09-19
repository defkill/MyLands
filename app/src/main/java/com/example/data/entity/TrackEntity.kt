package com.example.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.model.GeoPoint

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val totalDistanceMeters: Double = 0.0,
    val colorArgb: Int = 0xFF4CAF50.toInt(),
    val isActive: Boolean = false,
    val hasDeadReckoningSegments: Boolean = false
)

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["trackId", "timestamp"]),
        Index(value = ["latitude", "longitude"])
    ]
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val source: Int = SOURCE_GPS, // 0 = GPS, 1 = DEAD_RECKONING
    val headingDegrees: Float = 0f,
    val speedMps: Float? = null,
    val accuracyMeters: Float? = null
) {
    companion object {
        const val SOURCE_GPS = 0
        const val SOURCE_DEAD_RECKONING = 1
    }

    fun toGeoPoint(): GeoPoint = GeoPoint(
        latitude = latitude,
        longitude = longitude,
        altitude = altitudeMeters,
        accuracy = accuracyMeters,
        timestamp = timestamp
    )
}
