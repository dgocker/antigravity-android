from __future__ import annotations

import mimetypes
import os
import re
import uuid
import logging
from pathlib import Path
from typing import Optional, List
from fastapi import HTTPException, UploadFile, status
from fastapi.responses import FileResponse

from app.config import settings
from app.db import save_attachment, get_attachment
from app.models import AttachmentRef, AttachmentUploadResponse

logger = logging.getLogger("agy_gateway.attachments")

# Disallowed dangerous extensions to avoid execution
DANGEROUS_EXTENSIONS = {
    ".exe", ".bat", ".cmd", ".sh", ".bash", ".zsh", ".bin", ".elf",
    ".so", ".dll", ".dylib", ".vbs", ".ps1", ".cgi", ".pl"
}

def sanitize_filename(filename: str) -> str:
    """Sanitize filename to prevent path traversal and shell injection."""
    name = Path(filename).name
    # Strip dangerous characters
    name = re.sub(r'[^a-zA-Z0-9_.-]', '_', name)
    if not name or name.startswith('.'):
        name = f"attachment_{uuid.uuid4().hex[:6]}" + Path(filename).suffix
    return name

async def handle_attachment_upload(
    file: UploadFile,
    conversation_id: Optional[str] = None,
    transcription: Optional[str] = None,
    duration: Optional[int] = None,
) -> AttachmentUploadResponse:
    if not file.filename:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Filename is required"
        )

    safe_name = sanitize_filename(file.filename)
    suffix = Path(safe_name).suffix.lower()
    
    # Block dangerous executable files
    if suffix in DANGEROUS_EXTENSIONS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Files with extension '{suffix}' are not allowed for security reasons"
        )

    attachment_id = f"att_{uuid.uuid4().hex[:12]}"
    server_filename = f"{attachment_id}_{safe_name}"
    upload_path = Path(settings.uploads_dir) / server_filename

    # Stream file to disk and measure size
    total_bytes = 0
    max_size = settings.upload_max_size_bytes

    try:
        with open(upload_path, "wb") as f_out:
            while chunk := await file.read(64 * 1024):  # 64 KB chunks
                total_bytes += len(chunk)
                if total_bytes > max_size:
                    upload_path.unlink(missing_ok=True)
                    raise HTTPException(
                        status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                        detail=f"File exceeds maximum allowed size of {max_size // (1024 * 1024)} MB"
                    )
                f_out.write(chunk)
    except HTTPException:
        raise
    except Exception as e:
        upload_path.unlink(missing_ok=True)
        logger.error(f"Failed to save uploaded attachment: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Failed to save attachment on server"
        )

    # Detect or verify MIME type
    mime_type = file.content_type
    if not mime_type or mime_type == "application/octet-stream":
        guessed_type, _ = mimetypes.guess_type(safe_name)
        mime_type = guessed_type or "application/octet-stream"

    # Save to database
    save_attachment(
        attachment_id=attachment_id,
        file_name=safe_name,
        storage_path=str(upload_path),
        mime_type=mime_type,
        size=total_bytes,
        conversation_id=conversation_id,
        duration=duration,
        transcription=transcription,
    )

    download_url = f"/v1/attachments/{attachment_id}"
    logger.info(f"Saved attachment {attachment_id} ({safe_name}, {total_bytes} bytes, {mime_type})")

    return AttachmentUploadResponse(
        id=attachment_id,
        file_name=safe_name,
        mime_type=mime_type,
        size=total_bytes,
        storage_path=str(upload_path),
        download_url=download_url,
        transcription=transcription,
    )

def serve_attachment(attachment_id: str, download: bool = False) -> FileResponse:
    rec = get_attachment(attachment_id)
    if not rec:
        raise HTTPException(status_code=404, detail="Attachment not found")

    file_path = Path(rec["storage_path"])
    if not file_path.is_file():
        raise HTTPException(status_code=404, detail="Attachment file missing from disk")

    filename = rec["file_name"]
    mime_type = rec["mime_type"]

    headers = {}
    if download:
        headers["Content-Disposition"] = f'attachment; filename="{filename}"'
    else:
        headers["Content-Disposition"] = f'inline; filename="{filename}"'

    return FileResponse(
        path=str(file_path),
        media_type=mime_type,
        headers=headers,
    )

def format_prompt_with_attachments(
    text: str,
    attachments: List[AttachmentRef],
) -> str:
    if not attachments:
        return text

    blocks: list[str] = []
    for att in attachments:
        # Resolve storage path if server ID is provided
        storage_path = att.server_path
        if not storage_path:
            rec = get_attachment(att.id)
            if rec:
                storage_path = rec["storage_path"]
        
        path_str = storage_path or att.file_name
        size_kb = max(1, att.size // 1024)

        if att.type.lower() == "audio" or "audio" in att.mime_type.lower():
            dur_str = f", {att.duration}s" if att.duration else ""
            blocks.append(f"[Голосовое сообщение: {path_str} ({att.mime_type}{dur_str})]")
            if att.transcription and att.transcription.strip():
                blocks.append(f"Расшифровка аудио: \"{att.transcription.strip()}\"")
        elif att.type.lower() == "image" or "image" in att.mime_type.lower():
            blocks.append(f"[Изображение: {path_str} ({att.mime_type}, {size_kb} KB)]")
        elif att.type.lower() == "video" or "video" in att.mime_type.lower():
            blocks.append(f"[Видео: {path_str} ({att.mime_type}, {size_kb} KB)]")
        else:
            blocks.append(f"[Вложение: {att.file_name} ({path_str}, {size_kb} KB)]")

    prefix = "\n".join(blocks)
    if text and text.strip():
        return f"{prefix}\n\n{text.strip()}"
    return prefix
