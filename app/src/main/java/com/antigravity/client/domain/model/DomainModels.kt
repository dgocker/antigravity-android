package com.antigravity.client.domain.model

data class Conversation(
    val id: String,
    val title: String,
    val preview: String,
    val status: String,
    val stepCount: Int,
    val lastModified: String,
    val workspace: String,
    val parentConversationId: String? = null,
    val isActive: Boolean = false
)

data class Step(
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
    val toolCalls: List<ToolCall> = emptyList(),
    val diffs: List<CodeDiff> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val error: String? = null
)

data class ToolCall(
    val name: String,
    val args: Map<String, Any?> = emptyMap()
)

data class CodeDiff(
    val file: String,
    val action: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val targetContent: String? = null,
    val replacementContent: String? = null,
    val instruction: String? = null
)

data class ModelOption(
    val id: String,
    val name: String,
    val reasoningLevel: String? = null
)

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val lastModified: String
)

enum class RunStatus {
    IDLE,
    QUEUED,
    RUNNING,
    COMPLETED,
    ERROR,
    CANCELLED,
    INTERRUPTED
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    REPLAYING,
    LIVE,
    RECONNECTING,
    ERROR
}

enum class MessageDeliveryStatus {
    LOCAL,
    UPLOADING,
    SENDING,
    SENT,
    DELIVERED,
    FAILED
}

enum class AttachmentType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    OTHER
}

enum class AttachmentUploadState {
    LOCAL,
    PENDING,
    UPLOADING,
    UPLOADED,
    COMPLETED,
    SENDING,
    SENT,
    FAILED,
    CANCELLED
}

data class Attachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val conversationId: String? = null,
    val type: AttachmentType,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val duration: Int? = null,
    val localUri: String? = null,
    val remoteUrl: String? = null,
    val serverId: String? = null,
    val uploadState: AttachmentUploadState = AttachmentUploadState.LOCAL,
    val uploadProgress: Float = 0f,
    val transcription: String? = null
)

data class PendingUserMessage(
    val id: String,
    val text: String,
    val status: MessageDeliveryStatus,
    val baseStepIndex: Int,
    val attachments: List<Attachment> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class LiveActivity(
    val activity: String = "idle",
    val toolName: String? = null,
    val detail: String = "",
    val parameters: Map<String, Any?> = emptyMap(),
    val output: String? = null,
    val durationSeconds: Double? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class FileAttachment(
    val name: String,
    val path: String,
    val isImage: Boolean,
    val action: String? = null
)

