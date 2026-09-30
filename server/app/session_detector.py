from __future__ import annotations

import fcntl
import logging
import os
from pathlib import Path

logger = logging.getLogger("agy_gateway.presence")

PRESENCE_DIR = Path("/root/.gemini/antigravity-cli/presence")

def is_conversation_active_in_terminal(conv_id: str) -> bool:
    """
    Checks if an interactive `agy` CLI process currently holds an exclusive file lock
    on /root/.gemini/antigravity-cli/presence/<conv_id>.lock.
    """
    if not conv_id:
        return False

    lock_file = PRESENCE_DIR / f"{conv_id}.lock"
    if not lock_file.is_file():
        return False

    try:
        fd = os.open(str(lock_file), os.O_RDWR)
    except OSError:
        return False

    try:
        # Try non-blocking exclusive lock
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        # If we successfully acquired the lock, no other process holds it -> inactive
        fcntl.flock(fd, fcntl.LOCK_UN)
        return False
    except (BlockingIOError, PermissionError):
        # File is locked by another process -> actively open in interactive TUI!
        return True
    finally:
        os.close(fd)
