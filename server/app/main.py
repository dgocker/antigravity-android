from __future__ import annotations

from contextlib import asynccontextmanager
import json
import logging
import os
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
    reconcile_startup_runs,
    revoke_device_token,
    update_attachment_conversation,
)
from app.files import (
    DirectoryListResponse,
    FileContentResponse,
    get_raw_file_response,
    list_directory,
    read_file_content,
    validate_path_access,
)
from app.hub import handle_websocket_connection
from app.models import (
    AttachmentRef,
    AttachmentUploadResponse,
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
)
from app.queue import task_queue
from app.runner import cancel_active_run, get_transcript_path
from app.session_detector import is_conversation_active_in_terminal
from app.step_parser import parse_transcript_line_to_step
from app.tmux_injector import cancel_tmux_session, inject_message_to_tmux
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

            chats.append(
                ChatSummary(
                    id=r["conversation_id"],
                    title=r["title"],
                    preview=r["preview"],
                    status=r["status"],
                    step_count=r["step_count"],
                    last_modified=str(r["last_modified_time"]),
                    workspace=workspace,
                    parent_conversation_id=r["parent_conversation_id"] or None,
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
    if is_conversation_active_in_terminal(id):
        stopped = await cancel_tmux_session()
        if stopped:
            return CancelResponse(conversation_id=id, status="cancelling", run_id="tmux_cancel")

    cancelled_run_id = await cancel_active_run(id)
    if cancelled_run_id:
        return CancelResponse(conversation_id=id, status="cancelling", run_id=cancelled_run_id)
    return CancelResponse(conversation_id=id, status="not_running")

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

