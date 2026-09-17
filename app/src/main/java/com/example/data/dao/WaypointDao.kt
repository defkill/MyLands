package com.example.data.dao

import androidx.room.*
import com.example.data.entity.WaypointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WaypointDao {
    @Query("SELECT * FROM waypoints ORDER BY timestamp DESC")
    fun getAllWaypoints(): Flow<List<WaypointEntity>>

    @Query("SELECT * FROM waypoints WHERE id = :id")
    suspend fun getWaypointById(id: Long): WaypointEntity?

    @Query("SELECT * FROM waypoints WHERE id IN (:ids)")
    suspend fun getWaypointsByIds(ids: List<Long>): List<WaypointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(waypoint: WaypointEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(waypoints: List<WaypointEntity>): List<Long>

    @Update
    suspend fun update(waypoint: WaypointEntity)

    @Delete
    suspend fun delete(waypoint: WaypointEntity)

    @Query("DELETE FROM waypoints WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM waypoints")
    suspend fun clearAll()
}
