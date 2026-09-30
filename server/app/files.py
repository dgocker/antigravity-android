from __future__ import annotations

from datetime import datetime, timezone
import json
import mimetypes
import os
import sqlite3
from typing import Optional
from urllib.parse import urlparse
from fastapi import HTTPException, status
from fastapi.responses import FileResponse
from app.config import settings
from app.models import DirectoryListResponse, FileContentResponse, FileEntry

def get_chat_workspaces() -> list[str]:
    workspaces: list[str] = []
    db_path = settings.conversation_summaries_db
    if not os.path.isfile(db_path):
        return workspaces
    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        cur = conn.cursor()
        cur.execute("SELECT workspace_uris FROM conversation_summaries")
        rows = cur.fetchall()
        for (w_uris,) in rows:
            if not w_uris:
                continue
            try:
                uris = json.loads(w_uris)
                if isinstance(uris, list):
                    for u in uris:
                        parsed = urlparse(u)
                        if parsed.scheme == "file":
                            workspaces.append(parsed.path)
                        elif u.startswith("/"):
                            workspaces.append(u)
            except Exception:
                pass
        conn.close()
    except Exception:
        pass
    return workspaces

def get_all_allowed_roots() -> list[str]:
    roots = list(settings.allowed_workspace_roots)
    roots.extend(get_chat_workspaces())
    # Canonicalize all allowed roots
    canonical_roots: list[str] = []
    for r in roots:
        try:
            if os.path.exists(r):
                canonical_roots.append(os.path.realpath(os.path.abspath(r)))
            else:
                canonical_roots.append(os.path.abspath(r))
        except Exception:
            pass
    return list(set(canonical_roots))

def validate_path_access(target_path: str) -> str:
    """
    Validates that target_path resolves strictly within one of the allowed roots.
    Protects against path traversal (../) and symlinks pointing outside.
    Returns the canonical real path if valid, raises HTTPException(403) otherwise.
    """
    if not target_path or "\x00" in target_path:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid path specification",
        )

    # Resolve all relative segments and symlinks
    try:
        real_target = os.path.realpath(os.path.abspath(target_path))
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Cannot resolve path: {e}",
        )

    allowed_roots = get_all_allowed_roots()
    is_allowed = False
    for root in allowed_roots:
        if real_target == root or real_target.startswith(root + os.sep):
            is_allowed = True
            break

    if not is_allowed:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Access to path forbidden: outside allowed workspace roots",
        )

    return real_target

def is_binary_sample(sample: bytes) -> bool:
    if b"\x00" in sample:
        return True
    try:
        sample.decode("utf-8")
        return False
    except UnicodeDecodeError:
        return True

def list_directory(dir_path: str) -> DirectoryListResponse:
    real_path = validate_path_access(dir_path)
    if not os.path.exists(real_path):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Directory not found")
    if not os.path.isdir(real_path):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Path is not a directory")

    entries: list[FileEntry] = []
    try:
        with os.scandir(real_path) as it:
            for entry in it:
                try:
                    stat = entry.stat(follow_symlinks=False)
                    is_symlink = entry.is_symlink()
                    is_dir = entry.is_dir(follow_symlinks=True)
                    size = stat.st_size if not is_dir else 0
                    mtime = datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat()
                    entries.append(
                        FileEntry(
                            name=entry.name,
                            path=os.path.join(dir_path, entry.name),
                            is_dir=is_dir,
                            is_symlink=is_symlink,
                            size=size,
                            last_modified=mtime,
                        )
                    )
                except Exception:
                    continue
    except PermissionError:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Permission denied")

    # Sort: directories first, then alphabetical
    entries.sort(key=lambda e: (not e.is_dir, e.name.lower()))
    return DirectoryListResponse(path=dir_path, entries=entries)

def read_file_content(file_path: str) -> FileContentResponse:
    real_path = validate_path_access(file_path)
    if not os.path.exists(real_path):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found")
    if not os.path.isfile(real_path):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Path is not a regular file")

    try:
        file_size = os.path.getsize(real_path)
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))

    if file_size > settings.file_max_size_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"File size ({file_size} bytes) exceeds limit ({settings.file_max_size_bytes} bytes)",
        )

    try:
        with open(real_path, "rb") as f:
            sample = f.read(8192)
            if is_binary_sample(sample):
                return FileContentResponse(
                    path=file_path,
                    is_binary=True,
                    size=file_size,
                    message="Binary file content omitted",
                )
            # Text file: read remaining and decode
            rest = f.read()
            full_bytes = sample + rest
            text = full_bytes.decode("utf-8", errors="replace")
            return FileContentResponse(
                path=file_path,
                is_binary=False,
                size=file_size,
                content=text,
            )
    except PermissionError:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Permission denied")
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))

def get_raw_file_response(file_path: str, download: bool = False) -> FileResponse:
    real_path = validate_path_access(file_path)
    if not os.path.exists(real_path):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="File not found")
    if not os.path.isfile(real_path):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Path is not a regular file")

    filename = os.path.basename(real_path)
    mime_type, _ = mimetypes.guess_type(real_path)
    if not mime_type:
        mime_type = "application/octet-stream"

    content_disposition_type = "attachment" if download else "inline"
    return FileResponse(
        path=real_path,
        media_type=mime_type,
        filename=filename if download else None,
        content_disposition_type=content_disposition_type,
    )

