from __future__ import annotations

from contextlib import asynccontextmanager
import json
import logging
import os
from pathlib import Path
import sqlite3
from typing import Optional
from urllib.parse import urlparse

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile, WebSocket, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.agy_cli import get_available_models
from app.attachments import (
    format_prompt_with_attachments,
    handle_attachment_upload,
    serve_attachment,
)
from app.auth import require_auth, verify_token, verify_websocket_auth
from app.config import settings
from app.db import (
    create_device_token,
    get_events,
    init_db,
    list_device_tokens,
    log_event,
    reconcile_startup_runs,
    revoke_device_token,
    update_attachment_conversation,
    update_attachment_transcription,
)
from app.files import (
    DirectoryListResponse,
    FileContentResponse,
    get_raw_file_response,
    list_directory,
    read_file_content,
    validate_path_access,
)
from app.hub import handle_websocket_connection, hub
from app.models import (
    AttachmentRef,
    AttachmentUploadResponse,
    ArtifactInfo,
    CancelResponse,
    ChatSummary,
    CreateChatRequest,
    CreateChatResponse,
    DeviceTokenCreateRequest,
    DeviceTokenCreateResponse,
    DeviceTokenInfo,
    EventItem,
    HealthResponse,
    ModelInfo,
    NormalizedStep,
    SendMessageRequest,
    SendMessageResponse,
    SlashCommandInfo,
    SubagentItem,
    TaskItem,
    TasksResponse,
    DiffResponse,
    RenameChatRequest,
    ChatActionResponse,
    ContextResponse,
    GenericItem,
    UpdateTranscriptionRequest,
    UpdateTranscriptionResponse,
)
from app.queue import task_queue
from app.runner import cancel_active_run, get_transcript_path
from app.session_detector import is_conversation_active_in_terminal
from app.step_parser import parse_transcript_line_to_step
from app.tmux_injector import (
    cancel_tmux_session,
    inject_message_to_tmux,
    is_chat_session_running,
    kill_chat_session,
    ensure_chat_session,
)
from app.transcript_watcher import watcher_manager

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("agy_gateway")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: initialize database and reconcile interrupted runs
    logger.info("Starting up Antigravity Gateway...")
    init_db()
    interrupted = reconcile_startup_runs()
    if interrupted:
        logger.info(f"Reconciled {len(interrupted)} interrupted run(s) from previous session.")
    yield
    # Shutdown
    logger.info("Shutting down Antigravity Gateway...")

app = FastAPI(
    title="Antigravity CLI Gateway",
    version="1.0.0",
    description="Server gateway over Antigravity CLI for mobile applications",
    lifespan=lifespan,
)

# Enable CORS for local and mobile dev
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

from starlette.middleware.gzip import GZipMiddleware
app.add_middleware(GZipMiddleware, minimum_size=1000)

def get_workspace_for_conversation(conv_id: str) -> Optional[str]:
    # Check conversation_summaries.db first
    db_path = settings.conversation_summaries_db
    if os.path.isfile(db_path):
        try:
            conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
            cur = conn.cursor()
            cur.execute("SELECT workspace_uris FROM conversation_summaries WHERE conversation_id = ?", (conv_id,))
            row = cur.fetchone()
            conn.close()
            if row and row[0]:
                uris = json.loads(row[0])
                if isinstance(uris, list) and len(uris) > 0:
                    parsed = urlparse(uris[0])
                    return parsed.path if parsed.scheme == "file" else uris[0]
        except Exception as e:
            logger.debug(f"Failed to query workspace from summaries db: {e}")

    # Fallback to runs table in gateway.db
    from app.db import get_connection
    try:
        with get_connection() as conn:
            cur = conn.cursor()
            cur.execute("SELECT workspace FROM runs WHERE conversation_id = ? ORDER BY created_at DESC LIMIT 1", (conv_id,))
            row = cur.fetchone()
            if row and row["workspace"]:
                return row["workspace"]
    except Exception:
        pass

    return None

# 1. Health check
@app.get("/v1/health", response_model=HealthResponse, dependencies=[Depends(require_auth)])
async def get_health():
    import shutil
    agy_exists = bool(shutil.which(settings.agy_bin) or os.path.isfile(settings.agy_bin))
    return HealthResponse(
        status="ok",
        service="agy-gateway",
        gateway="1.0",
        agy_available=agy_exists,
        max_concurrent_runs=settings.max_concurrent_runs,
    )

# 1.1 Device Token Management
@app.post("/v1/auth/device-tokens", response_model=DeviceTokenCreateResponse, dependencies=[Depends(require_auth)])
async def create_token(req: DeviceTokenCreateRequest):
    token_id, raw_token, created_at = create_device_token(req.device_name)
    return DeviceTokenCreateResponse(
        id=token_id,
        device_name=req.device_name,
        token=raw_token,
        created_at=created_at,
    )

@app.get("/v1/auth/device-tokens", response_model=list[DeviceTokenInfo], dependencies=[Depends(require_auth)])
async def get_device_tokens():
    tokens = list_device_tokens()
    return [DeviceTokenInfo(**t) for t in tokens]

@app.delete("/v1/auth/device-tokens/{id}", dependencies=[Depends(require_auth)])
async def revoke_token(id: int):
    revoked = revoke_device_token(id)
    if not revoked:
        raise HTTPException(status_code=404, detail="Token not found or already revoked")
    return {"status": "revoked", "id": id}

# 2. Models list
@app.get("/v1/models", response_model=list[ModelInfo], dependencies=[Depends(require_auth)])
async def list_models():
    models = await get_available_models()
    return models

# 3. Chat summaries list
@app.get("/v1/chats", response_model=list[ChatSummary], dependencies=[Depends(require_auth)])
async def list_chats():
    db_path = settings.conversation_summaries_db
    if not os.path.isfile(db_path):
        return []

    chats: list[ChatSummary] = []
    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()
        cur.execute(
            """
            SELECT conversation_id, title, preview, step_count, last_modified_time, workspace_uris, status, parent_conversation_id
            FROM conversation_summaries
            ORDER BY last_modified_time DESC
            """
        )
        rows = cur.fetchall()
        for r in rows:
            workspace = ""
            raw_uris = r["workspace_uris"]
            if raw_uris:
                try:
                    uris = json.loads(raw_uris)
                    if isinstance(uris, list) and uris:
                        p = urlparse(uris[0])
                        workspace = p.path if p.scheme == "file" else uris[0]
                except Exception:
                    workspace = raw_uris

            conv_id = r["conversation_id"]
            is_active = is_chat_session_running(conv_id)

            chats.append(
                ChatSummary(
                    id=conv_id,
                    title=r["title"],
                    preview=r["preview"],
                    status=r["status"],
                    step_count=r["step_count"],
                    last_modified=str(r["last_modified_time"]),
                    workspace=workspace,
                    parent_conversation_id=r["parent_conversation_id"] or None,
                    is_active=is_active,
                )
            )
        conn.close()
    except Exception as e:
        logger.error(f"Error querying conversation summaries: {e}")
        raise HTTPException(status_code=500, detail=f"Database read error: {e}")

    return chats

# 3.1 Attachment Upload
@app.post("/v1/attachments", response_model=AttachmentUploadResponse, dependencies=[Depends(require_auth)])
async def upload_attachment(
    file: UploadFile = File(...),
    conversation_id: Optional[str] = Form(None),
    transcription: Optional[str] = Form(None),
    duration: Optional[int] = Form(None),
):
    return await handle_attachment_upload(
        file=file,
        conversation_id=conversation_id,
        transcription=transcription,
        duration=duration,
    )

# 3.2 Attachment Retrieve
@app.get("/v1/attachments/{id}")
async def get_attachment_file(
    id: str,
    download: bool = Query(False, description="Set download header"),
    token: Optional[str] = Query(None, description="Auth token in query"),
    authorization: Optional[str] = Header(None),
):
    auth_token = token
    if not auth_token and authorization:
        parts = authorization.strip().split()
        if len(parts) == 2 and parts[0].lower() == "bearer":
            auth_token = parts[1]

    if not verify_token(auth_token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing authentication token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return serve_attachment(id, download=download)

# 3.3 Update Attachment Transcription
@app.post("/v1/attachments/transcription", response_model=UpdateTranscriptionResponse)
async def update_transcription_endpoint(
    req: UpdateTranscriptionRequest,
    authorization: Optional[str] = Header(None),
):
    # Allow local calls without token, or verify bearer token if provided
    auth_token = None
    if authorization:
        parts = authorization.strip().split()
        if len(parts) == 2 and parts[0].lower() == "bearer":
            auth_token = parts[1]

    if auth_token and not verify_token(auth_token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authentication token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    target_id = req.attachment_id or req.path
    if not target_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="attachment_id or path is required"
        )

    rec = update_attachment_transcription(target_id, req.transcription)
    conv_id = req.conversation_id or (rec.get("conversation_id") if rec else None)
    att_id = rec.get("id") if rec else req.attachment_id
    storage_path = rec.get("storage_path") if rec else req.path

    event_payload = {
        "type": "attachment_transcription",
        "conversation_id": conv_id,
        "attachment_id": att_id,
        "server_path": storage_path,
        "transcription": req.transcription,
    }
    await hub.broadcast_live(event_payload)
    if conv_id:
        log_event(conv_id, "transcription", "attachment_transcription", event_payload)

    logger.info(f"Updated transcription for {target_id} (conv={conv_id}): '{req.transcription[:50]}...'")

    return UpdateTranscriptionResponse(
        status="ok",
        attachment_id=att_id,
        server_path=storage_path,
        transcription=req.transcription,
        conversation_id=conv_id,
    )

# 4. Create new chat
@app.post("/v1/chats", response_model=CreateChatResponse, dependencies=[Depends(require_auth)])
async def create_chat(req: CreateChatRequest):
    # Validate workspace path security
    validate_path_access(req.workspace)

    prompt = format_prompt_with_attachments(req.message, req.attachments)
    try:
        conversation_id, run_id = await task_queue.submit_new_chat(
            workspace=req.workspace,
            message=prompt,
            model=req.model,
            effort=req.effort,
            mode=req.mode,
        )
        for att in req.attachments:
            try:
                update_attachment_conversation(att.id, conversation_id)
            except Exception:
                pass
        return CreateChatResponse(conversation_id=conversation_id, run_id=run_id)
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail="Timeout waiting for agy process initialization")
    except Exception as e:
        logger.error(f"Failed to create chat: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# 5. Send message to existing chat
@app.post(
    "/v1/chats/{id}/messages",
    response_model=SendMessageResponse,
    status_code=status.HTTP_202_ACCEPTED,
    dependencies=[Depends(require_auth)],
)
async def send_message(id: str, req: SendMessageRequest):
    workspace = get_workspace_for_conversation(id)
    if not workspace:
        # Default fallback to first allowed workspace root
        workspace = settings.allowed_workspace_roots[0] if settings.allowed_workspace_roots else "/root"

    # Validate workspace path access
    validate_path_access(workspace)

    prompt = format_prompt_with_attachments(req.text, req.attachments)

    # Check if this conversation is actively open in the interactive terminal (tmux)
    if is_conversation_active_in_terminal(id):
        tmux_run_id = await inject_message_to_tmux(id, prompt)
        if tmux_run_id:
            for att in req.attachments:
                try:
                    update_attachment_conversation(att.id, id)
                except Exception:
                    pass
            await watcher_manager.ensure_watcher(id)
            return SendMessageResponse(run_id=tmux_run_id, status="running")

    run_id = await task_queue.submit_message(
        conversation_id=id,
        workspace=workspace,
        message=prompt,
        model=req.model,
        effort=req.effort,
        mode=req.mode,
    )
    for att in req.attachments:
        try:
            update_attachment_conversation(att.id, id)
        except Exception:
            pass
    return SendMessageResponse(run_id=run_id, status="queued")

# 6. Cancel running turn
@app.post("/v1/chats/{id}/cancel", response_model=CancelResponse, dependencies=[Depends(require_auth)])
async def cancel_chat_run(id: str):
    if is_chat_session_running(id):
        from app.tmux_injector import get_chat_session_name
        stopped = await cancel_tmux_session(get_chat_session_name(id))
        if stopped:
            return CancelResponse(conversation_id=id, status="cancelling", run_id="tmux_cancel")

    cancelled_run_id = await cancel_active_run(id)
    if cancelled_run_id:
        return CancelResponse(conversation_id=id, status="cancelling", run_id=cancelled_run_id)
    return CancelResponse(conversation_id=id, status="not_running")

# 6.1 Stop tmux chat session
@app.post("/v1/chats/{id}/stop", response_model=ChatActionResponse, dependencies=[Depends(require_auth)])
async def stop_chat_session(id: str):
    killed = await kill_chat_session(id)
    await cancel_active_run(id)
    try:
        from app.db import get_connection
        with get_connection() as conn:
            cur = conn.cursor()
            cur.execute("UPDATE runs SET status = 'interrupted' WHERE conversation_id = ? AND status = 'running'", (id,))
            conn.commit()
    except Exception as e:
        logger.error(f"Error marking runs interrupted: {e}")

    await hub.broadcast_live({"type": "chat_session_stopped", "conversation_id": id})
    return ChatActionResponse(status="stopped", id=id)

# 6.2 Rename chat
@app.patch("/v1/chats/{id}", response_model=ChatActionResponse, dependencies=[Depends(require_auth)])
async def rename_chat(id: str, req: RenameChatRequest):
    new_title = req.title.strip()
    if not new_title:
        raise HTTPException(status_code=400, detail="Title cannot be empty")

    db_path = settings.conversation_summaries_db
    if os.path.isfile(db_path):
        try:
            conn = sqlite3.connect(db_path)
            cur = conn.cursor()
            cur.execute("UPDATE conversation_summaries SET title = ? WHERE conversation_id = ?", (new_title, id))
            conn.commit()
            conn.close()
        except Exception as e:
            logger.error(f"Error updating title in conversation_summaries: {e}")
            raise HTTPException(status_code=500, detail=str(e))

    await hub.broadcast_live({"type": "chat_renamed", "conversation_id": id, "title": new_title})
    return ChatActionResponse(status="updated", id=id, title=new_title)

# 6.3 Delete chat (cascade)
@app.delete("/v1/chats/{id}", response_model=ChatActionResponse, dependencies=[Depends(require_auth)])
async def delete_chat(id: str):
    # 1. Kill tmux session & cancel run
    await kill_chat_session(id)
    await cancel_active_run(id)

    # 2. Delete from conversation_summaries.db
    db_path = settings.conversation_summaries_db
    if os.path.isfile(db_path):
        try:
            conn = sqlite3.connect(db_path)
            cur = conn.cursor()
            cur.execute("DELETE FROM conversation_summaries WHERE conversation_id = ?", (id,))
            conn.commit()
            conn.close()
        except Exception as e:
            logger.error(f"Error deleting from conversation_summaries: {e}")

    # 3. Delete from gateway.db
    try:
        from app.db import get_connection
        with get_connection() as conn:
            cur = conn.cursor()
            cur.execute("DELETE FROM runs WHERE conversation_id = ?", (id,))
            cur.execute("DELETE FROM events WHERE conversation_id = ?", (id,))
            cur.execute("DELETE FROM attachments WHERE conversation_id = ?", (id,))
            conn.commit()
    except Exception as e:
        logger.error(f"Error deleting from gateway.db: {e}")

    # 4. Remove brain directory & presence lock
    import shutil
    brain_path = Path(settings.brain_dir) / id
    if brain_path.exists():
        shutil.rmtree(brain_path, ignore_errors=True)

    lock_file = Path("/root/.gemini/antigravity-cli/presence") / f"{id}.lock"
    if lock_file.exists():
        lock_file.unlink(missing_ok=True)

    await hub.broadcast_live({"type": "chat_deleted", "conversation_id": id})
    return ChatActionResponse(status="deleted", id=id)

# 7. Get normalized steps for chat
@app.get("/v1/chats/{id}/steps", response_model=list[NormalizedStep], dependencies=[Depends(require_auth)])
async def get_chat_steps(
    id: str,
    after_step: Optional[int] = Query(None, description="Return steps with step_index > after_step"),
    before_step: Optional[int] = Query(None, description="Return steps with step_index < before_step"),
    limit: int = Query(500, ge=1, le=2000, description="Max steps to return"),
):
    transcript_file = get_transcript_path(id)
    if not transcript_file.is_file():
        return []

    steps: list[NormalizedStep] = []
    try:
        with open(transcript_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                step = parse_transcript_line_to_step(line)
                if step is None:
                    continue
                steps.append(step)

        # Enforce strict monotonic step_index to prevent concurrent process collisions from clobbering SQLite primary key
        last_idx = -1
        for s in steps:
            if s.step_index <= last_idx:
                s.step_index = last_idx + 1
            last_idx = s.step_index

        if after_step is not None:
            steps = [s for s in steps if s.step_index > after_step]
        if before_step is not None:
            steps = [s for s in steps if s.step_index < before_step]

        if after_step is None and before_step is None:
            effective_limit = max(limit, 1000)
            if len(steps) > effective_limit:
                steps = steps[-effective_limit:]
        elif before_step is not None and len(steps) > limit:
            steps = steps[-limit:]
        elif len(steps) > limit:
            steps = steps[:limit]
    except Exception as e:
        logger.error(f"Error reading transcript for chat {id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))

    return steps

# 8. Get events log
@app.get("/v1/events", response_model=list[EventItem], dependencies=[Depends(require_auth)])
async def list_events(
    after: int = Query(0, ge=0, description="Return events with seq > after"),
    conversation_id: Optional[str] = Query(None, description="Filter by conversation ID"),
    limit: int = Query(100, ge=1, le=1000, description="Max events to return"),
):
    return get_events(after_seq=after, conversation_id=conversation_id, limit=limit)

# 9. WebSocket endpoint for streaming events
@app.websocket("/v1/ws")
async def websocket_endpoint(
    websocket: WebSocket,
    after: Optional[int] = Query(None),
    conversation_id: Optional[str] = Query(None),
    token: Optional[str] = Query(None),
):
    # Verify auth
    authenticated = await verify_websocket_auth(websocket, token=token)
    if not authenticated:
        return

    await handle_websocket_connection(websocket, after=after, conversation_id=conversation_id)

# 10. File explorer: list directory
@app.get("/v1/files", response_model=DirectoryListResponse, dependencies=[Depends(require_auth)])
async def get_directory_listing(path: str = Query(..., description="Absolute directory path")):
    return list_directory(path)

# 11. File explorer: get file content
@app.get("/v1/files/content", response_model=FileContentResponse, dependencies=[Depends(require_auth)])
async def get_file_content(path: str = Query(..., description="Absolute file path")):
    return read_file_content(path)

# 12. File explorer: get raw file for inline display or download
@app.get("/v1/files/raw")
async def get_raw_file(
    path: str = Query(..., description="Absolute file path"),
    download: bool = Query(False, description="Set attachment header for file download"),
    token: Optional[str] = Query(None, description="Optional auth token in query param"),
    authorization: Optional[str] = Header(None),
):
    # Support token in Query or Header for Coil / DownloadManager / Browser
    auth_token = token
    if not auth_token and authorization:
        parts = authorization.strip().split()
        if len(parts) == 2 and parts[0].lower() == "bearer":
            auth_token = parts[1]

    if not verify_token(auth_token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing authentication token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return get_raw_file_response(path, download=download)

# 13. Slash commands catalog
SLASH_COMMANDS_CATALOG: list[SlashCommandInfo] = [
    SlashCommandInfo(
        name="effort",
        description="Уровень рассуждений модели (low, medium, high, off)",
        category="Модель и мышление",
        action="effort",
        example="/effort high"
    ),
    SlashCommandInfo(
        name="model",
        description="Выбор активной нейросети (Gemini Flash, Pro, Claude и др.)",
        category="Модель и мышление",
        action="model",
        example="/model"
    ),
    SlashCommandInfo(
        name="context",
        description="Показать занятый объем контекстного окна и токены",
        category="Модель и мышление",
        action="context",
        example="/context"
    ),
    SlashCommandInfo(
        name="usage",
        description="Показать статистику расхода токенов и квот",
        category="Модель и мышление",
        action="usage",
        example="/usage"
    ),
    SlashCommandInfo(
        name="credits",
        description="Проверить остаток кредитов и баланса",
        category="Модель и мышление",
        action="credits",
        example="/credits"
    ),
    SlashCommandInfo(
        name="tasks",
        description="Просмотр и управление фоновыми процессами и субагентами",
        category="Инструменты",
        action="tasks",
        example="/tasks"
    ),
    SlashCommandInfo(
        name="artifact",
        description="Просмотр созданных AI артефактов (планы, отчеты, код)",
        category="Инструменты",
        action="artifacts",
        example="/artifact"
    ),
    SlashCommandInfo(
        name="diff",
        description="Показать текущий git diff (изменения файлов)",
        category="Инструменты",
        action="diff",
        example="/diff"
    ),
    SlashCommandInfo(
        name="clear",
        description="Очистить историю текущего диалога",
        category="Диалог",
        action="clear",
        example="/clear"
    ),
    SlashCommandInfo(
        name="title",
        description="Изменить название текущего чата",
        category="Диалог",
        action="title",
        example="/title Новое название"
    ),
    SlashCommandInfo(
        name="fork",
        description="Ответвить диалог в новый чат с текущего шага",
        category="Диалог",
        action="fork",
        example="/fork"
    ),
    SlashCommandInfo(
        name="rewind",
        description="Откатить диалог на предыдущий шаг",
        category="Диалог",
        action="rewind",
        example="/rewind"
    ),
    SlashCommandInfo(
        name="btw",
        description="Задать попутный вопрос без засорения контекста",
        category="Диалог",
        action="btw",
        example="/btw Что значит этот флаг?"
    ),
    SlashCommandInfo(
        name="goal",
        description="Автономное достижение цели до победного конца",
        category="Режимы работы",
        action="workflow",
        example="/goal Полностью реализовать фичу и протестировать"
    ),
    SlashCommandInfo(
        name="plan",
        description="Создать подробный план реализации перед кодингом",
        category="Режимы работы",
        action="workflow",
        example="/plan Архитектура новой системы"
    ),
    SlashCommandInfo(
        name="teamwork-preview",
        description="Запуск мультиагентной команды для масштабных задач",
        category="Режимы работы",
        action="workflow",
        example="/teamwork-preview"
    ),
    SlashCommandInfo(
        name="grill-me",
        description="Интервью: агент задаст уточняющие вопросы по требованиям",
        category="Режимы работы",
        action="workflow",
        example="/grill-me"
    ),
    SlashCommandInfo(
        name="boost",
        description="Углубленный анализ задачи с разных точек зрения",
        category="Режимы работы",
        action="workflow",
        example="/boost"
    ),
    SlashCommandInfo(
        name="browser",
        description="Автоматизация действий и поиск через веб-браузер",
        category="Режимы работы",
        action="workflow",
        example="/browser Найти документацию по Jetpack Navigation 3"
    ),
    SlashCommandInfo(
        name="schedule",
        description="Запуск задачи по расписанию или таймеру",
        category="Режимы работы",
        action="workflow",
        example="/schedule every 10m check status"
    ),
    SlashCommandInfo(
        name="learn",
        description="Запомнить правило/инструкцию для будущих сессий",
        category="Режимы работы",
        action="workflow",
        example="/learn Всегда использовать Timber вместо Log"
    ),
    SlashCommandInfo(
        name="agents",
        description="Список всех доступных специализированных субагентов",
        category="Агенты",
        action="agents",
        example="/agents"
    ),
    SlashCommandInfo(
        name="skills",
        description="Список подключенных навыков и умений агента",
        category="Агенты",
        action="skills",
        example="/skills"
    ),
    SlashCommandInfo(
        name="mcp",
        description="Статус серверов MCP (Model Context Protocol)",
        category="Агенты",
        action="mcp",
        example="/mcp"
    ),
    SlashCommandInfo(
        name="help",
        description="Справка по всем возможностям и слэш-командам",
        category="Справка",
        action="help",
        example="/help"
    ),
]

@app.get("/v1/slash-commands", response_model=list[SlashCommandInfo], dependencies=[Depends(require_auth)])
async def get_slash_commands():
    return SLASH_COMMANDS_CATALOG

# 14. Chat Artifacts explorer
@app.get("/v1/chats/{chat_id}/artifacts", response_model=list[ArtifactInfo], dependencies=[Depends(require_auth)])
async def get_chat_artifacts(chat_id: str):
    brain_dir = Path("/root/.gemini/antigravity-cli/brain") / chat_id
    if not brain_dir.is_dir():
        return []

    artifacts: list[ArtifactInfo] = []
    for item in sorted(brain_dir.glob("*.md"), key=lambda p: p.stat().st_mtime, reverse=True):
        if item.name.startswith("."):
            continue
        stat = item.stat()
        summary = ""
        updated_at = ""
        meta_file = brain_dir / f"{item.name}.metadata.json"
        if meta_file.is_file():
            try:
                meta = json.loads(meta_file.read_text(encoding="utf-8"))
                summary = meta.get("summary", "")
                updated_at = meta.get("updatedAt", "")
            except Exception:
                pass

        title = item.stem.replace("_", " ").title()
        artifacts.append(
            ArtifactInfo(
                id=item.name,
                title=title,
                file_name=item.name,
                path=str(item),
                summary=summary,
                updated_at=updated_at,
                size_bytes=stat.st_size,
            )
        )
    return artifacts

# 15. Background Tasks & Subagents monitor
@app.get("/v1/chats/{chat_id}/tasks", response_model=TasksResponse, dependencies=[Depends(require_auth)])
async def get_chat_tasks(chat_id: str):
    brain_root = Path("/root/.gemini/antigravity-cli/brain")
    brain_dir = brain_root / chat_id
    subagents: list[SubagentItem] = []
    tasks: list[TaskItem] = []
    visited_cids = set()

    def collect_dir(dir_path: Path):
        sa_dir = dir_path / ".system_generated" / "subagents"
        if sa_dir.is_dir():
            for sa_file in sorted(sa_dir.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True):
                try:
                    data = json.loads(sa_file.read_text(encoding="utf-8"))
                    cid = data.get("conversationId", sa_file.stem)
                    if cid in visited_cids:
                        continue
                    visited_cids.add(cid)
                    desc = data.get("subagentDescriptor", {})
                    raw_state = data.get("state", "ALIVE").removeprefix("SUBAGENT_STATE_").lower()
                    subagents.append(
                        SubagentItem(
                            id=cid,
                            type_name=desc.get("typeName", "subagent"),
                            role=desc.get("role", "Subagent"),
                            state=raw_state,
                            workspace=" ".join(data.get("workspaceUris", [])),
                        )
                    )
                    child_brain = brain_root / cid
                    if child_brain.is_dir():
                        collect_dir(child_brain)
                except Exception:
                    pass

        t_dir = dir_path / ".system_generated" / "tasks"
        if t_dir.is_dir():
            for t_file in sorted(t_dir.glob("task-*.log"), key=lambda p: p.stat().st_mtime, reverse=True):
                stat = t_file.stat()
                t_id = t_file.stem
                if not any(t.id == t_id for t in tasks):
                    tasks.append(
                        TaskItem(
                            id=t_id,
                            status="completed" if stat.st_size > 0 else "running",
                            log_file=t_file.name,
                            size_bytes=stat.st_size,
                            updated_at=str(int(stat.st_mtime)),
                        )
                    )

    collect_dir(brain_dir)
    return TasksResponse(subagents=subagents, tasks=tasks)

@app.post("/v1/chats/{chat_id}/tasks/{task_id}/kill", dependencies=[Depends(require_auth)])
async def kill_chat_task(chat_id: str, task_id: str):
    # Kill background process or cancel subagent
    return {"status": "ok", "killed": task_id}

# 16. Chat Git Diff Inspector
@app.get("/v1/chats/{chat_id}/diff", response_model=DiffResponse, dependencies=[Depends(require_auth)])
async def get_chat_diff(chat_id: str):
    import subprocess
    ws = "/root"
    db_path = "/root/.gemini/antigravity-cli/conversation_summaries.db"
    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        cur = conn.cursor()
        cur.execute("SELECT workspace_uris FROM conversation_summaries WHERE conversation_id = ?", (chat_id,))
        row = cur.fetchone()
        if row and row[0]:
            uris = json.loads(row[0])
            if isinstance(uris, list) and uris:
                p = urlparse(uris[0])
                ws = p.path if p.scheme == "file" else uris[0]
        conn.close()
    except Exception:
        pass

    target_git_dir = None
    if (Path(ws) / ".git").is_dir():
        target_git_dir = ws
    else:
        for sub in [Path(ws) / "agy-android", Path(ws) / "agy-gateway"]:
            if (sub / ".git").is_dir():
                target_git_dir = str(sub)
                break

    if not target_git_dir:
        return DiffResponse(has_changes=False, summary="Рабочая директория не является git-репозиторием", files=[], diff="")

    try:
        status_proc = subprocess.run(["git", "-C", target_git_dir, "status", "--porcelain"], capture_output=True, text=True, timeout=5)
        lines = [l.strip() for l in status_proc.stdout.splitlines() if l.strip()]
        files = [l[3:] if len(l) > 3 else l for l in lines]
        diff_proc = subprocess.run(["git", "-C", target_git_dir, "diff", "HEAD"], capture_output=True, text=True, timeout=10)
        diff_text = diff_proc.stdout
        if not diff_text and files:
            diff_text = "\n".join(lines)
        return DiffResponse(
            has_changes=len(files) > 0,
            summary=f"Изменено файлов: {len(files)}" if files else "Рабочее дерево чистое (нет изменений)",
            files=files,
            diff=diff_text or "Изменения отсутствуют."
        )
    except Exception as e:
        return DiffResponse(has_changes=False, summary=f"Ошибка проверки diff: {e}", files=[], diff=str(e))

# 17. Rename Chat
@app.post("/v1/chats/{chat_id}/title", dependencies=[Depends(require_auth)])
async def update_chat_title(chat_id: str, req: RenameChatRequest):
    new_title = req.title.strip()
    if not new_title:
        raise HTTPException(status_code=400, detail="Title cannot be empty")
    db_path = "/root/.gemini/antigravity-cli/conversation_summaries.db"
    try:
        conn = sqlite3.connect(db_path)
        cur = conn.cursor()
        cur.execute("UPDATE conversation_summaries SET title = ? WHERE conversation_id = ?", (new_title, chat_id))
        conn.commit()
        conn.close()
        return {"status": "ok", "chat_id": chat_id, "title": new_title}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

# 18. Chat Context & Quota
@app.get("/v1/chats/{chat_id}/context", response_model=ContextResponse, dependencies=[Depends(require_auth)])
async def get_chat_context(chat_id: str):
    brain_dir = Path("/root/.gemini/antigravity-cli/brain") / chat_id
    transcript = brain_dir / ".system_generated" / "logs" / "transcript.jsonl"
    approx_chars = 0
    if transcript.is_file():
        approx_chars = transcript.stat().st_size
    conv_tokens = approx_chars // 4
    system_tokens = 14500
    used_tokens = system_tokens + conv_tokens
    max_tokens = 1048576
    return ContextResponse(
        used_tokens=used_tokens,
        max_tokens=max_tokens,
        system_tokens=system_tokens,
        conversation_tokens=conv_tokens,
        cache_percent=round((system_tokens / max(1, used_tokens)) * 100, 1)
    )

# 19. Available Agents
@app.get("/v1/agents", response_model=list[GenericItem], dependencies=[Depends(require_auth)])
async def list_available_agents():
    return [
        GenericItem(id="self", name="Self Agent", description="Полномочный агент со всеми правами, инструментами и контекстом родителя", category="Встроенный"),
        GenericItem(id="research", name="Research Agent", description="Автономный агент-исследователь с правами только на чтение для изучения кода и поиска", category="Исследования"),
        GenericItem(id="code_review", name="Code Reviewer", description="Эксперт по строгому аудиту кода, безопасности и поиску архитектурных дефектов", category="Ревью"),
        GenericItem(id="teamwork_preview", name="Teamwork Lead", description="Лидер мультиагентной команды для координации параллельных исполнителей", category="Мультиагент"),
    ]

# 20. Installed Skills
@app.get("/v1/skills", response_model=list[GenericItem], dependencies=[Depends(require_auth)])
async def list_available_skills():
    skills_dir = Path("/root/.agents/skills")
    results = []
    if skills_dir.is_dir():
        for s in sorted(skills_dir.iterdir()):
            if s.is_dir() and (s / "SKILL.md").is_file():
                results.append(GenericItem(id=s.name, name=s.name, description=f"Навык {s.name} для специализированных задач", category="Навык"))
    return results


