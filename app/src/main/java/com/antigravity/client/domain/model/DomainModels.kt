package com.antigravity.client.domain.model

data class Conversation(
    val id: String,
    val title: String,
    val preview: String,
    val status: String,
    val stepCount: Int,
    val lastModified: String,
    val workspace: String,
    val parentConversationId: String? = null
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
    SENDING,
    SENT,
    DELIVERED,
    FAILED
}

data class PendingUserMessage(
    val id: String,
    val text: String,
    val status: MessageDeliveryStatus,
    val baseStepIndex: Int,
    val timestamp: Long = System.currentTimeMillis()
)
