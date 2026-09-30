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
    val attachmentsJson: String? = null,
    val error: String? = null
)

data class AttachmentEntity(
    val id: String,
    val conversationId: String,
    val messageId: String,
    val type: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val duration: Int? = null,
    val localUri: String? = null,
    val remoteUrl: String? = null,
    val serverId: String? = null,
    val uploadState: String,
    val transcription: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class OutboxEntity(
    val id: String,
    val conversationId: String,
    val text: String,
    val attachmentIdsJson: String,
    val state: String,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis()
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
