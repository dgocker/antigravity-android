package com.antigravity.client.data.remote.dto

import com.google.gson.annotations.SerializedName

data class HealthResponseDto(
    @SerializedName("status") val status: String,
    @SerializedName("service") val service: String,
    @SerializedName("gateway") val gateway: String,
    @SerializedName("agy_available") val agyAvailable: Boolean,
    @SerializedName("max_concurrent_runs") val maxConcurrentRuns: Int
)

data class ModelInfoDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("reasoning_level") val reasoningLevel: String? = null
)

data class ChatSummaryDto(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("preview") val preview: String,
    @SerializedName("status") val status: String,
    @SerializedName("step_count") val stepCount: Int,
    @SerializedName("last_modified") val lastModified: String,
    @SerializedName("workspace") val workspace: String,
    @SerializedName("parent_conversation_id") val parentConversationId: String? = null
)

data class AttachmentRefDto(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String = "document",
    @SerializedName("file_name") val fileName: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("size") val size: Long,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("server_path") val serverPath: String? = null,
    @SerializedName("transcription") val transcription: String? = null
)

data class AttachmentUploadResponseDto(
    @SerializedName("id") val id: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("size") val size: Long,
    @SerializedName("storage_path") val storagePath: String,
    @SerializedName("download_url") val downloadUrl: String,
    @SerializedName("transcription") val transcription: String? = null
)

data class CreateChatRequestDto(
    @SerializedName("workspace") val workspace: String,
    @SerializedName("message") val message: String,
    @SerializedName("attachments") val attachments: List<AttachmentRefDto> = emptyList(),
    @SerializedName("model") val model: String? = null,
    @SerializedName("effort") val effort: String? = null,
    @SerializedName("mode") val mode: String? = null
)

data class CreateChatResponseDto(
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("run_id") val runId: String
)

data class SendMessageRequestDto(
    @SerializedName("text") val text: String,
    @SerializedName("attachments") val attachments: List<AttachmentRefDto> = emptyList(),
    @SerializedName("model") val model: String? = null,
    @SerializedName("effort") val effort: String? = null,
    @SerializedName("mode") val mode: String? = null
)

data class SendMessageResponseDto(
    @SerializedName("run_id") val runId: String,
    @SerializedName("status") val status: String = "queued"
)

data class CancelResponseDto(
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("status") val status: String,
    @SerializedName("run_id") val runId: String? = null
)

data class ToolCallDto(
    @SerializedName("name") val name: String,
    @SerializedName("args") val args: Map<String, Any?>? = null
)

data class CodeDiffDto(
    @SerializedName("file") val file: String,
    @SerializedName("action") val action: String, // create | overwrite | append | replace | multi_replace
    @SerializedName("start_line") val startLine: Int? = null,
    @SerializedName("end_line") val endLine: Int? = null,
    @SerializedName("target_content") val targetContent: String? = null,
    @SerializedName("replacement_content") val replacementContent: String? = null,
    @SerializedName("instruction") val instruction: String? = null
)

data class NormalizedStepDto(
    @SerializedName("step_index") val stepIndex: Int,
    @SerializedName("source") val source: String, // USER_EXPLICIT | MODEL | SYSTEM
    @SerializedName("type") val type: String,     // USER_INPUT | PLANNER_RESPONSE | GENERIC | CODE_ACTION | ERROR_MESSAGE | SYSTEM_MESSAGE
    @SerializedName("status") val status: String, // DONE | RUNNING
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("thinking") val thinking: String? = null,
    @SerializedName("content") val content: String? = null,
    @SerializedName("user_prompt") val userPrompt: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerializedName("diffs") val diffs: List<CodeDiffDto>? = null,
    @SerializedName("attachments") val attachments: List<AttachmentRefDto>? = null,
    @SerializedName("error") val error: String? = null
)

data class EventItemDto(
    @SerializedName("seq") val seq: Long,
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("run_id") val runId: String,
    @SerializedName("type") val type: String,
    @SerializedName("payload") val payload: com.google.gson.JsonElement? = null,
    @SerializedName("ts") val ts: String
)

data class FileEntryDto(
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("is_dir") val isDir: Boolean,
    @SerializedName("is_symlink") val isSymlink: Boolean,
    @SerializedName("size") val size: Long,
    @SerializedName("last_modified") val lastModified: String
)

data class DirectoryListResponseDto(
    @SerializedName("path") val path: String,
    @SerializedName("entries") val entries: List<FileEntryDto>
)

data class FileContentResponseDto(
    @SerializedName("path") val path: String,
    @SerializedName("is_binary") val isBinary: Boolean,
    @SerializedName("size") val size: Long,
    @SerializedName("content") val content: String? = null,
    @SerializedName("message") val message: String? = null
)

data class DeviceTokenCreateRequestDto(
    @SerializedName("device_name") val deviceName: String
)

data class DeviceTokenCreateResponseDto(
    @SerializedName("id") val id: Int,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("token") val token: String,
    @SerializedName("created_at") val createdAt: String
)

data class DeviceTokenInfoDto(
    @SerializedName("id") val id: Int,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("last_used_at") val lastUsedAt: String? = null,
    @SerializedName("revoked_at") val revokedAt: String? = null
)

data class ArtifactDto(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("path") val path: String,
    @SerializedName("summary") val summary: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("size_bytes") val sizeBytes: Long = 0L
)

data class SubagentDto(
    @SerializedName("id") val id: String,
    @SerializedName("type_name") val typeName: String,
    @SerializedName("role") val role: String,
    @SerializedName("state") val state: String,
    @SerializedName("workspace") val workspace: String = ""
)

data class TaskItemDto(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("log_file") val logFile: String,
    @SerializedName("size_bytes") val sizeBytes: Long = 0L,
    @SerializedName("updated_at") val updatedAt: String = ""
)

data class TasksResponseDto(
    @SerializedName("subagents") val subagents: List<SubagentDto> = emptyList(),
    @SerializedName("tasks") val tasks: List<TaskItemDto> = emptyList()
)

data class SlashCommandDto(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("category") val category: String,
    @SerializedName("action") val action: String = "insert",
    @SerializedName("example") val example: String = ""
)

data class DiffResponseDto(
    @SerializedName("has_changes") val hasChanges: Boolean = false,
    @SerializedName("summary") val summary: String = "",
    @SerializedName("files") val files: List<String> = emptyList(),
    @SerializedName("diff") val diff: String = ""
)

data class RenameChatRequestDto(
    @SerializedName("title") val title: String
)

data class ContextResponseDto(
    @SerializedName("used_tokens") val usedTokens: Long = 0L,
    @SerializedName("max_tokens") val maxTokens: Long = 1048576L,
    @SerializedName("system_tokens") val systemTokens: Long = 0L,
    @SerializedName("conversation_tokens") val conversationTokens: Long = 0L,
    @SerializedName("cache_percent") val cachePercent: Float = 0f
)

data class GenericItemDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("category") val category: String? = null
)
