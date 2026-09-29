package com.antigravity.client.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val title: String,
    val preview: String,
    val status: String,
    val stepCount: Int,
    val lastModified: String,
    val workspace: String,
    val parentConversationId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["seq"], unique = true),
        Index(value = ["conversationId", "seq"]),
        Index(value = ["runId"])
    ]
)
data class EventEntity(
    @PrimaryKey val seq: Long,
    val conversationId: String,
    val runId: String,
    val type: String,
    val timestamp: String,
    val payloadJson: String
)

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val runId: String,
    val conversationId: String,
    val workspace: String,
    val prompt: String,
    val status: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val error: String? = null
)

@Entity(
    tableName = "steps",
    primaryKeys = ["conversationId", "stepIndex"],
    indices = [Index(value = ["conversationId", "stepIndex"])]
)
data class StepEntity(
    val conversationId: String,
    val runId: String,
    val stepIndex: Int,
    val source: String,
    val type: String,
    val status: String,
    val createdAt: String,
    val thinking: String? = null,
    val content: String? = null,
    val userPrompt: String? = null,
    val toolCallsJson: String? = null,
    val diffsJson: String? = null,
    val error: String? = null
)

@Entity(tableName = "file_cache")
data class FileCacheEntity(
    @PrimaryKey val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val lastModified: String,
    val cachedContent: String? = null,
    val isBinary: Boolean = false
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String = "global_sync",
    val lastReceivedSeq: Long = 0L,
    val serverUrl: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
