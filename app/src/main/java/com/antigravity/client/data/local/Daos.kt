package com.antigravity.client.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conversations: List<ConversationEntity>)

    @Query("SELECT * FROM conversations ORDER BY lastModified DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE conversationId = :id")
    fun getConversation(id: String): Flow<ConversationEntity?>

    @Query("""
        SELECT * FROM conversations 
        WHERE title LIKE '%' || :query || '%' OR preview LIKE '%' || :query || '%'
        ORDER BY lastModified DESC
    """)
    fun searchConversations(query: String): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET status = :status WHERE conversationId = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM conversations WHERE conversationId = :id")
    suspend fun delete(id: String)
}

@Dao
interface EventDao {
    // CRITICAL: OnConflictStrategy.IGNORE prevents duplicates when server replays seq
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>): List<Long>

    @Query("SELECT * FROM events WHERE seq > :afterSeq ORDER BY seq ASC")
    suspend fun getEventsAfter(afterSeq: Long): List<EventEntity>

    @Query("SELECT * FROM events WHERE conversationId = :conversationId ORDER BY seq ASC")
    fun getEventsForConversation(conversationId: String): Flow<List<EventEntity>>

    @Query("SELECT MAX(seq) FROM events")
    suspend fun getLatestSeq(): Long?

    @Query("SELECT COUNT(*) FROM events WHERE seq = :seq")
    suspend fun countBySeq(seq: Long): Int
}

@Dao
interface StepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(step: StepEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(steps: List<StepEntity>)

    @Query("SELECT * FROM steps WHERE conversationId = :conversationId ORDER BY stepIndex ASC")
    fun getStepsForConversation(conversationId: String): Flow<List<StepEntity>>

    @Query("SELECT * FROM steps WHERE conversationId = :conversationId AND stepIndex = :stepIndex")
    suspend fun getStep(conversationId: String, stepIndex: Int): StepEntity?

    @Query("DELETE FROM steps WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(run: RunEntity)

    @Query("SELECT * FROM runs WHERE runId = :runId")
    fun getRun(runId: String): Flow<RunEntity?>

    @Query("SELECT * FROM runs WHERE conversationId = :conversationId ORDER BY startedAt DESC")
    fun getRunsForConversation(conversationId: String): Flow<List<RunEntity>>

    @Query("UPDATE runs SET status = :status, finishedAt = :finishedAt, error = :error WHERE runId = :runId")
    suspend fun updateRunStatus(runId: String, status: String, finishedAt: String? = null, error: String? = null)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    suspend fun getSyncState(key: String = "global_sync"): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: SyncStateEntity)

    @Query("UPDATE sync_state SET lastReceivedSeq = :seq, updatedAt = :updatedAt WHERE `key` = :key")
    suspend fun updateLastSeq(seq: Long, key: String = "global_sync", updatedAt: Long = System.currentTimeMillis())
}

@Dao
interface FileCacheDao {
    @Query("SELECT * FROM file_cache WHERE path = :path")
    suspend fun getFile(path: String): FileCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(file: FileCacheEntity)

    @Query("DELETE FROM file_cache WHERE path = :path")
    suspend fun delete(path: String)
}
