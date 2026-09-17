package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.entity.RouteEntity
import com.example.data.entity.RouteLeg
import com.example.data.entity.TrackEntity
import com.example.data.entity.TrackPointEntity
import com.example.data.entity.WaypointEntity
import com.example.data.io.ImportedNavigationData
import com.example.data.track.TrackFilter
import com.example.geodesy.GeodesyEngine
import kotlinx.coroutines.flow.Flow

class NavigationRepository(private val database: AppDatabase) {

    private val waypointDao = database.waypointDao()
    private val trackDao = database.trackDao()
    private val routeDao = database.routeDao()

    val allWaypoints: Flow<List<WaypointEntity>> = waypointDao.getAllWaypoints()
    val allTracks: Flow<List<TrackEntity>> = trackDao.getAllTracks()
    val activeTrack: Flow<TrackEntity?> = trackDao.getActiveTrack()
    val allRoutes: Flow<List<RouteEntity>> = routeDao.getAllRoutes()

    suspend fun addWaypoint(waypoint: WaypointEntity): Long {
        return waypointDao.insert(waypoint)
    }

    suspend fun addWaypointsBatch(waypoints: List<WaypointEntity>): List<Long> {
        if (waypoints.isEmpty()) return emptyList()
        return waypointDao.insertAll(waypoints)
    }

    suspend fun importNavigationDataBatch(data: ImportedNavigationData): Pair<Int, Int> = database.withTransaction {
        var wpCount = 0
        var rteCount = 0

        if (data.waypoints.isNotEmpty()) {
            val insertedIds = waypointDao.insertAll(data.waypoints)
            wpCount = insertedIds.size
        }

        for ((name, pts) in data.routes) {
            val entitiesToInsert = pts.mapIndexed { idx, p ->
                WaypointEntity(
                    name = "$name - T${idx + 1}",
                    latitude = p.latitude,
                    longitude = p.longitude,
                    altitudeMeters = p.altitude
                )
            }
            if (entitiesToInsert.isNotEmpty()) {
                val newIds = waypointDao.insertAll(entitiesToInsert)
                val savedEntities = entitiesToInsert.mapIndexed { idx, entity ->
                    entity.copy(id = newIds[idx])
                }
                if (savedEntities.size >= 2) {
                    createRouteFromWaypoints(name, savedEntities)
                    rteCount++
                }
            }
        }

        Pair(wpCount, rteCount)
    }

    suspend fun updateWaypoint(waypoint: WaypointEntity) {
        waypointDao.update(waypoint)
    }

    suspend fun deleteWaypoint(id: Long) {
        waypointDao.deleteById(id)
    }

    suspend fun getWaypointById(id: Long): WaypointEntity? {
        return waypointDao.getWaypointById(id)
    }

    // --- Route Management ---
    suspend fun createRouteFromWaypoints(name: String, waypoints: List<WaypointEntity>): Long {
        if (waypoints.size < 2) return -1
        var totalDist = 0.0
        for (i in 0 until waypoints.size - 1) {
            totalDist += GeodesyEngine.distanceMeters(
                waypoints[i].latitude, waypoints[i].longitude,
                waypoints[i + 1].latitude, waypoints[i + 1].longitude
            )
        }
        val csv = waypoints.joinToString(",") { it.id.toString() }
        val entity = RouteEntity(
            name = name,
            waypointIdsCsv = csv,
            totalDistanceMeters = totalDist
        )
        return routeDao.insert(entity)
    }

    suspend fun getRouteLegs(route: RouteEntity): List<RouteLeg> {
        val orderedPoints = getRoutePoints(route)
        if (orderedPoints.size < 2) return emptyList()

        val legs = mutableListOf<RouteLeg>()
        for (i in 0 until orderedPoints.size - 1) {
            legs.add(RouteLeg.create(i + 1, orderedPoints[i], orderedPoints[i + 1]))
        }
        return legs
    }

    suspend fun getRoutePoints(route: RouteEntity): List<WaypointEntity> {
        val ids = route.parseWaypointIds()
        if (ids.isEmpty()) return emptyList()
        val points = waypointDao.getWaypointsByIds(ids).associateBy { it.id }
        return ids.mapNotNull { points[it] }
    }

    suspend fun deleteRoute(id: Long) {
        routeDao.deleteById(id)
    }

    // --- Track Management ---
    suspend fun startNewTrack(name: String): Long {
        val currentActive = trackDao.getActiveTrackSync()
        if (currentActive != null) {
            trackDao.updateTrack(currentActive.copy(isActive = false, endTime = System.currentTimeMillis()))
        }
        val newTrack = TrackEntity(
            name = name,
            startTime = System.currentTimeMillis(),
            isActive = true
        )
        return trackDao.insertTrack(newTrack)
    }

    suspend fun stopCurrentTrack() {
        val currentActive = trackDao.getActiveTrackSync() ?: return
        val points = trackDao.getTrackPointsSync(currentActive.id)

        // Measure the cleaned path: a single GPS jump would otherwise report kilometres
        // that were never walked. Raw points stay in the database untouched.
        val totalDist = TrackFilter.filter(points).distanceMeters
        val hasDr = points.any { it.source == TrackPointEntity.SOURCE_DEAD_RECKONING }
        trackDao.updateTrack(
            currentActive.copy(
                isActive = false,
                endTime = System.currentTimeMillis(),
                totalDistanceMeters = totalDist,
                hasDeadReckoningSegments = hasDr
            )
        )
    }

    suspend fun recordTrackPoint(point: TrackPointEntity) {
        trackDao.insertPoint(point)
    }

    fun getTrackPoints(trackId: Long): Flow<List<TrackPointEntity>> {
        return trackDao.getTrackPoints(trackId)
    }

    suspend fun getTrackPointsSync(trackId: Long): List<TrackPointEntity> {
        return trackDao.getTrackPointsSync(trackId)
    }

    suspend fun updateTrack(track: TrackEntity) {
        trackDao.updateTrack(track)
    }

    suspend fun deleteTrack(id: Long) {
        trackDao.deleteTrackById(id)
    }
}
