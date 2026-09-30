from __future__ import annotations

import hashlib
import json
import secrets
import sqlite3
import threading
from datetime import datetime, timezone
from typing import Any, Optional
from app.config import settings

_db_lock = threading.Lock()

def get_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(settings.db_path, timeout=30.0, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode = WAL;")
    conn.execute("PRAGMA synchronous = NORMAL;")
    return conn

def init_db() -> None:
    with _db_lock, get_connection() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS events (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                run_id TEXT NOT NULL,
                type TEXT NOT NULL,
                payload TEXT NOT NULL,
                ts TEXT NOT NULL
            );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_events_seq ON events(seq);")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_events_conv_seq ON events(conversation_id, seq);")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_events_run ON events(run_id);")

        conn.execute("""
            CREATE TABLE IF NOT EXISTS runs (
                run_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                workspace TEXT NOT NULL,
                prompt TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                started_at TEXT,
                finished_at TEXT,
                error TEXT
            );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_runs_conv ON runs(conversation_id);")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_runs_status ON runs(status);")

        conn.execute("""
            CREATE TABLE IF NOT EXISTS device_tokens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                token_hash TEXT UNIQUE NOT NULL,
                device_name TEXT NOT NULL,
                created_at TEXT NOT NULL,
                last_used_at TEXT,
                revoked_at TEXT
            );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_device_tokens_hash ON device_tokens(token_hash);")

        conn.execute("""
            CREATE TABLE IF NOT EXISTS attachments (
                id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT,
                file_name TEXT NOT NULL,
                storage_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size INTEGER NOT NULL,
                duration INTEGER,
                transcription TEXT,
                created_at TEXT NOT NULL
            );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_attachments_conv ON attachments(conversation_id);")
        conn.commit()

def reconcile_startup_runs() -> list[dict[str, Any]]:
    """Mark any lingering queued/running runs as interrupted upon server restart."""
    now = datetime.now(timezone.utc).isoformat()
    interrupted_events: list[dict[str, Any]] = []
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT run_id, conversation_id FROM runs WHERE status IN ('queued', 'running')")
        rows = cur.fetchall()
        for row in rows:
            run_id = row["run_id"]
            conv_id = row["conversation_id"]
            cur.execute(
                "UPDATE runs SET status = 'interrupted', finished_at = ?, error = ? WHERE run_id = ?",
                (now, "Interrupted by server restart", run_id),
            )
            payload = {
                "run_id": run_id,
                "conversation_id": conv_id,
                "error": "Run was interrupted by server restart",
                "status": "interrupted",
            }
            cur.execute(
                "INSERT INTO events (conversation_id, run_id, type, payload, ts) VALUES (?, ?, 'run_error', ?, ?)",
                (conv_id, run_id, json.dumps(payload, ensure_ascii=False), now),
            )
            seq = cur.lastrowid
            interrupted_events.append({
                "seq": seq,
                "conversation_id": conv_id,
                "run_id": run_id,
                "type": "run_error",
                "payload": payload,
                "ts": now,
            })
        conn.commit()
    return interrupted_events

def insert_run(
    run_id: str,
    conversation_id: str,
    workspace: str,
    prompt: str,
    status: str = "queued",
) -> None:
    now = datetime.now(timezone.utc).isoformat()
    with _db_lock, get_connection() as conn:
        conn.execute(
            """
            INSERT INTO runs (run_id, conversation_id, workspace, prompt, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (run_id, conversation_id, workspace, prompt, status, now),
        )
        conn.commit()

def update_run_status(
    run_id: str,
    status: str,
    error: Optional[str] = None,
    started_at: Optional[str] = None,
    finished_at: Optional[str] = None,
    conversation_id: Optional[str] = None,
) -> None:
    with _db_lock, get_connection() as conn:
        updates: list[str] = ["status = ?"]
        params: list[Any] = [status]
        if error is not None:
            updates.append("error = ?")
            params.append(error)
        if started_at is not None:
            updates.append("started_at = ?")
            params.append(started_at)
        if finished_at is not None:
            updates.append("finished_at = ?")
            params.append(finished_at)
        if conversation_id is not None:
            updates.append("conversation_id = ?")
            params.append(conversation_id)
        params.append(run_id)
        query = f"UPDATE runs SET {', '.join(updates)} WHERE run_id = ?"
        conn.execute(query, params)
        conn.commit()

def get_run(run_id: str) -> Optional[dict[str, Any]]:
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT * FROM runs WHERE run_id = ?", (run_id,))
        row = cur.fetchone()
        return dict(row) if row else None

def log_event(
    conversation_id: str,
    run_id: str,
    event_type: str,
    payload: dict[str, Any],
) -> dict[str, Any]:
    now = datetime.now(timezone.utc).isoformat()
    payload_json = json.dumps(payload, ensure_ascii=False)
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        cur.execute(
            "INSERT INTO events (conversation_id, run_id, type, payload, ts) VALUES (?, ?, ?, ?, ?)",
            (conversation_id, run_id, event_type, payload_json, now),
        )
        seq = cur.lastrowid
        conn.commit()
    return {
        "seq": seq,
        "conversation_id": conversation_id,
        "run_id": run_id,
        "type": event_type,
        "payload": payload,
        "ts": now,
    }

def get_events(
    after_seq: int = 0,
    conversation_id: Optional[str] = None,
    limit: int = 100,
) -> list[dict[str, Any]]:
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        if conversation_id:
            cur.execute(
                """
                SELECT seq, conversation_id, run_id, type, payload, ts
                FROM events
                WHERE seq > ? AND conversation_id = ?
                ORDER BY seq ASC
                LIMIT ?
                """,
                (after_seq, conversation_id, limit),
            )
        else:
            cur.execute(
                """
                SELECT seq, conversation_id, run_id, type, payload, ts
                FROM events
                WHERE seq > ?
                ORDER BY seq ASC
                LIMIT ?
                """,
                (after_seq, limit),
            )
        rows = cur.fetchall()
        results: list[dict[str, Any]] = []
        for r in rows:
            try:
                parsed_payload = json.loads(r["payload"])
            except Exception:
                parsed_payload = r["payload"]
            results.append({
                "seq": r["seq"],
                "conversation_id": r["conversation_id"],
                "run_id": r["run_id"],
                "type": r["type"],
                "payload": parsed_payload,
                "ts": r["ts"],
            })
        return results

def get_latest_seq() -> int:
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        cur.execute("SELECT COALESCE(MAX(seq), 0) FROM events")
        row = cur.fetchone()
        return row[0] if (row and row[0] is not None) else 0

def create_device_token(device_name: str) -> tuple[int, str, str]:
    now = datetime.now(timezone.utc).isoformat()
    raw_token = f"agy_android_{secrets.token_hex(24)}"
    token_hash = hashlib.sha256(raw_token.encode("utf-8")).hexdigest()
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        cur.execute(
            """
            INSERT INTO device_tokens (token_hash, device_name, created_at)
            VALUES (?, ?, ?)
            """,
            (token_hash, device_name.strip(), now),
        )
        token_id = cur.lastrowid
        conn.commit()
    return token_id, raw_token, now

def verify_and_touch_token(raw_token: str) -> bool:
    if not raw_token:
        return False
    token_hash = hashlib.sha256(raw_token.strip().encode("utf-8")).hexdigest()
    now = datetime.now(timezone.utc).isoformat()
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        cur.execute(
            """
            SELECT id FROM device_tokens
            WHERE token_hash = ? AND revoked_at IS NULL
            """,
            (token_hash,),
        )
        row = cur.fetchone()
        if not row:
            return False
        token_id = row["id"]
        cur.execute(
            "UPDATE device_tokens SET last_used_at = ? WHERE id = ?",
            (now, token_id),
        )
        conn.commit()
    return True

def list_device_tokens() -> list[dict[str, Any]]:
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        cur.execute(
            """
            SELECT id, device_name, created_at, last_used_at, revoked_at
            FROM device_tokens
            ORDER BY id DESC
            """
        )
        rows = cur.fetchall()
        return [dict(r) for r in rows]

def revoke_device_token(token_id: int) -> bool:
    now = datetime.now(timezone.utc).isoformat()
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        cur.execute(
            """
            UPDATE device_tokens
            SET revoked_at = ?
            WHERE id = ? AND revoked_at IS NULL
            """,
            (now, token_id),
        )
        updated = cur.rowcount > 0
        conn.commit()
    return updated

def save_attachment(
    attachment_id: str,
    file_name: str,
    storage_path: str,
    mime_type: str,
    size: int,
    conversation_id: Optional[str] = None,
    duration: Optional[int] = None,
    transcription: Optional[str] = None,
) -> dict[str, Any]:
    now = datetime.now(timezone.utc).isoformat()
    with _db_lock, get_connection() as conn:
        conn.execute(
            """
            INSERT INTO attachments (id, conversation_id, file_name, storage_path, mime_type, size, duration, transcription, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (attachment_id, conversation_id, file_name, storage_path, mime_type, size, duration, transcription, now),
        )
        conn.commit()
    return {
        "id": attachment_id,
        "conversation_id": conversation_id,
        "file_name": file_name,
        "storage_path": storage_path,
        "mime_type": mime_type,
        "size": size,
        "duration": duration,
        "transcription": transcription,
        "created_at": now,
    }

def get_attachment(attachment_id: str) -> Optional[dict[str, Any]]:
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        cur.execute(
            """
            SELECT id, conversation_id, file_name, storage_path, mime_type, size, duration, transcription, created_at
            FROM attachments
            WHERE id = ? OR storage_path = ? OR file_name = ?
            """,
            (attachment_id, attachment_id, attachment_id),
        )
        row = cur.fetchone()
        return dict(row) if row else None

def update_attachment_conversation(attachment_id: str, conversation_id: str) -> None:
    with _db_lock, get_connection() as conn:
        conn.execute(
            "UPDATE attachments SET conversation_id = ? WHERE id = ?",
            (conversation_id, attachment_id),
        )
        conn.commit()

def update_attachment_transcription(identifier: str, transcription: str) -> Optional[dict[str, Any]]:
    with _db_lock, get_connection() as conn:
        cur = conn.cursor()
        cur.execute(
            """
            UPDATE attachments
            SET transcription = ?
            WHERE id = ? OR storage_path = ? OR file_name = ?
            RETURNING id, conversation_id, file_name, storage_path, mime_type, size, duration, transcription, created_at
            """,
            (transcription, identifier, identifier, identifier),
        )
        row = cur.fetchone()
        conn.commit()
        return dict(row) if row else None

