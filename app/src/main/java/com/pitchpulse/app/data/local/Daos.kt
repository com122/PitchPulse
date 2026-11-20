package com.pitchpulse.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PitchDao {
    @Query("SELECT * FROM pitches ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PitchEntity>>

    @Query("SELECT * FROM pitches ORDER BY updatedAt DESC")
    suspend fun getAll(): List<PitchEntity>

    @Query("SELECT * FROM pitches WHERE id = :id")
    suspend fun getById(id: String): PitchEntity?

    @Upsert
    suspend fun upsert(pitch: PitchEntity)

    @Query("DELETE FROM pitches WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MatchDao {
    @Query("SELECT * FROM matches ORDER BY startTime DESC")
    fun observeAll(): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches ORDER BY startTime DESC")
    suspend fun getAll(): List<MatchEntity>

    @Query("SELECT * FROM matches WHERE id = :id")
    fun observeById(id: String): Flow<MatchEntity?>

    @Query("SELECT * FROM matches WHERE id = :id")
    suspend fun getById(id: String): MatchEntity?

    @Query("SELECT * FROM matches WHERE pitchId = :pitchId")
    suspend fun getByPitch(pitchId: String): List<MatchEntity>

    @Query("SELECT pitchId, COUNT(*) AS count FROM matches WHERE pitchId IS NOT NULL GROUP BY pitchId")
    fun observeCountsByPitch(): Flow<List<PitchMatchCount>>

    @Upsert
    suspend fun upsert(match: MatchEntity)

    @Query("UPDATE matches SET pitchId = :pitchId WHERE id = :matchId")
    suspend fun updatePitch(matchId: String, pitchId: String)

    @Query("DELETE FROM matches WHERE id = :id")
    suspend fun delete(id: String)
}
