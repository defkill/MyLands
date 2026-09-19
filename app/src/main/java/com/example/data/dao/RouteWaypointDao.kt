package com.example.data.dao

import androidx.room.*
import com.example.data.entity.RouteWaypointCrossRef
import com.example.data.entity.WaypointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteWaypointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(crossRefs: List<RouteWaypointCrossRef>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(crossRef: RouteWaypointCrossRef)

    @Query("SELECT * FROM route_waypoints WHERE routeId = :routeId ORDER BY orderIndex ASC")
    suspend fun getWaypointsForRoute(routeId: Long): List<RouteWaypointCrossRef>

    @Query("""
        SELECT w.* FROM waypoints w
        INNER JOIN route_waypoints rw ON w.id = rw.waypointId
        WHERE rw.routeId = :routeId
        ORDER BY rw.orderIndex ASC
    """)
    suspend fun getOrderedWaypointsForRoute(routeId: Long): List<WaypointEntity>

    @Query("DELETE FROM route_waypoints WHERE routeId = :routeId")
    suspend fun deleteByRouteId(routeId: Long)
}
