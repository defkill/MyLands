package com.example.data.dao

import androidx.room.*
import com.example.data.entity.TrackEntity
import com.example.data.entity.TrackPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY startTime DESC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isActive = 1 LIMIT 1")
    fun getActiveTrack(): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveTrackSync(): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity): Long

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Delete
    suspend fun deleteTrack(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: TrackPointEntity): Long

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Transaction
    suspend fun replaceTrackPoints(trackId: Long, points: List<TrackPointEntity>) {
        deletePointsByTrackId(trackId)
        insertPoints(points)
    }

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    fun getTrackPoints(trackId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    suspend fun getTrackPointsSync(trackId: Long): List<TrackPointEntity>

    @Query("DELETE FROM track_points WHERE trackId = :trackId")
    suspend fun deletePointsByTrackId(trackId: Long)
}
