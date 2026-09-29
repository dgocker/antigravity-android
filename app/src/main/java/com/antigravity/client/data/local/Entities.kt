package com.antigravity.client.data.local

data class ConversationEntity(
    val conversationId: String,
    val title: String,
    val preview: String,
    val status: String,
    val stepCount: Int,
    val lastModified: String,
    val workspace: String,
    val parentConversationId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class EventEntity(
    val seq: Long,
    val conversationId: String,
    val runId: String,
    val type: String,
    val timestamp: String,
    val payloadJson: String
)

data class RunEntity(
    val runId: String,
    val conversationId: String,
    val workspace: String,
    val prompt: String,
    val status: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val error: String? = null
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

data class FileCacheEntity(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val lastModified: String,
    val cachedContent: String? = null,
    val isBinary: Boolean = false
)

data class SyncStateEntity(
    val key: String = "global_sync",
    val lastReceivedSeq: Long = 0L,
    val serverUrl: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
