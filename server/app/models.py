from __future__ import annotations

from typing import Any, Optional
from pydantic import BaseModel, Field

class AttachmentRef(BaseModel):
    id: str
    type: str = "document"  # "image" | "video" | "audio" | "document" | "other"
    file_name: str
    mime_type: str
    size: int
    duration: Optional[int] = None
    server_path: Optional[str] = None
    transcription: Optional[str] = None

class AttachmentUploadResponse(BaseModel):
    id: str
    file_name: str
    mime_type: str
    size: int
    storage_path: str
    download_url: str
    transcription: Optional[str] = None

class CreateChatRequest(BaseModel):
    workspace: str = Field(..., description="Absolute path to the workspace directory")
    message: str = Field("", description="Initial prompt message to start the conversation")
    attachments: list[AttachmentRef] = Field(default_factory=list)
    model: Optional[str] = Field(None, description="Optional model ID (e.g. gemini-3.8-flash-high)")
    effort: Optional[str] = Field(None, description="Reasoning effort: low | medium | high")
    mode: Optional[str] = Field(None, description="Agent mode: plan | accept-edits")

class CreateChatResponse(BaseModel):
    conversation_id: str
    run_id: str

class SendMessageRequest(BaseModel):
    text: str = Field("", description="User message to send to the conversation")
    attachments: list[AttachmentRef] = Field(default_factory=list)
    model: Optional[str] = Field(None, description="Optional override of model ID")
    effort: Optional[str] = Field(None, description="Optional override of reasoning effort")
    mode: Optional[str] = Field(None, description="Optional override of agent execution mode")

class SendMessageResponse(BaseModel):
    run_id: str
    status: str = "queued"

class CancelResponse(BaseModel):
    conversation_id: str
    status: str
    run_id: Optional[str] = None

class ModelInfo(BaseModel):
    id: str
    name: str
    reasoning_level: Optional[str] = None

class ChatSummary(BaseModel):
    id: str
    title: str
    preview: str
    status: str
    step_count: int
    last_modified: str
    workspace: str
    parent_conversation_id: Optional[str] = None

class ToolCall(BaseModel):
    name: str
    args: dict[str, Any] = Field(default_factory=dict)

class CodeDiff(BaseModel):
    file: str
    action: str  # "create" | "overwrite" | "append" | "replace" | "multi_replace"
    start_line: Optional[int] = None
    end_line: Optional[int] = None
    target_content: Optional[str] = None
    replacement_content: Optional[str] = None
    instruction: Optional[str] = None

class NormalizedStep(BaseModel):
    step_index: int
    source: str  # USER_EXPLICIT | MODEL | SYSTEM
    type: str    # USER_INPUT | PLANNER_RESPONSE | GENERIC | CODE_ACTION | ERROR_MESSAGE | SYSTEM_MESSAGE
    status: str  # DONE | RUNNING
    created_at: str
    thinking: Optional[str] = None
    content: Optional[str] = None
    user_prompt: Optional[str] = None
    tool_calls: list[ToolCall] = Field(default_factory=list)
    diffs: list[CodeDiff] = Field(default_factory=list)
    attachments: list[AttachmentRef] = Field(default_factory=list)
    error: Optional[str] = None

class EventItem(BaseModel):
    seq: int
    conversation_id: str
    run_id: str
    type: str
    payload: dict[str, Any]
    ts: str

class FileEntry(BaseModel):
    name: str
    path: str
    is_dir: bool
    is_symlink: bool
    size: int
    last_modified: str

class DirectoryListResponse(BaseModel):
    path: str
    entries: list[FileEntry]

class FileContentResponse(BaseModel):
    path: str
    is_binary: bool
    size: int
    content: Optional[str] = None
    message: Optional[str] = None

class DeviceTokenCreateRequest(BaseModel):
    device_name: str = Field(..., description="Human-readable name of the mobile device")

class DeviceTokenCreateResponse(BaseModel):
    id: int
    device_name: str
    token: str
    created_at: str

class DeviceTokenInfo(BaseModel):
    id: int
    device_name: str
    created_at: str
    last_used_at: Optional[str] = None
    revoked_at: Optional[str] = None

class HealthResponse(BaseModel):
    status: str
    service: str
    gateway: str
    agy_available: bool
    max_concurrent_runs: int

class ArtifactInfo(BaseModel):
    id: str
    title: str
    file_name: str
    path: str
    summary: str = ""
    updated_at: str = ""
    size_bytes: int = 0

class SubagentItem(BaseModel):
    id: str
    type_name: str
    role: str
    state: str
    workspace: str = ""

class TaskItem(BaseModel):
    id: str
    status: str
    log_file: str
    size_bytes: int = 0
    updated_at: str = ""

class TasksResponse(BaseModel):
    subagents: list[SubagentItem] = []
    tasks: list[TaskItem] = []

class SlashCommandInfo(BaseModel):
    name: str
    description: str
    category: str
    action: str = "insert"
    example: str = ""

class DiffResponse(BaseModel):
    has_changes: bool
    summary: str
    files: list[str] = Field(default_factory=list)
    diff: str = ""

class RenameChatRequest(BaseModel):
    title: str

class ContextResponse(BaseModel):
    used_tokens: int
    max_tokens: int
    system_tokens: int
    conversation_tokens: int
    cache_percent: float

class GenericItem(BaseModel):
    id: str
    name: str
    description: str
    category: Optional[str] = None

class UpdateTranscriptionRequest(BaseModel):
    attachment_id: Optional[str] = None
    path: Optional[str] = None
    transcription: str
    conversation_id: Optional[str] = None

class UpdateTranscriptionResponse(BaseModel):
    status: str = "ok"
    attachment_id: Optional[str] = None
    server_path: Optional[str] = None
    transcription: str
    conversation_id: Optional[str] = None

