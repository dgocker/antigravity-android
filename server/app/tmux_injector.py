from __future__ import annotations

import asyncio
import logging
import os
import shutil
import subprocess
import uuid
from typing import Optional
from app.db import insert_run
from app.session_detector import is_conversation_active_in_terminal

logger = logging.getLogger("agy_gateway.tmux")

def is_tmux_running(session_name: str = "agy") -> bool:
    if not shutil.which("tmux"):
        return False
    try:
        res = os.system(f"tmux has-session -t {session_name} 2>/dev/null")
        return res == 0
    except Exception:
        return False

def is_tmux_turn_active(session_name: str = "agy") -> bool:
    """
    Checks if Antigravity in tmux is actively executing a turn (running commands, thinking).
    """
    if not is_tmux_running(session_name):
        return False
    try:
        proc = subprocess.run(
            ["tmux", "capture-pane", "-t", session_name, "-p"],
            capture_output=True,
            text=True,
            timeout=1,
        )
        if proc.returncode == 0:
            tail = "\n".join(proc.stdout.splitlines()[-15:])
            spinners = ("⣷", "⣯", "⣟", "⡿", "⢿", "⣻", "⣽", "⣾")
            if any(s in tail for s in spinners):
                return True
            if "esc to cancel" in tail:
                return True
            if "Running command" in tail or "Thinking..." in tail:
                return True
    except Exception:
        pass
    return False

async def inject_message_to_tmux(conv_id: str, message: str, session_name: str = "agy") -> Optional[str]:
    """
    Injects prompt text directly into the active tmux session via load-buffer and paste-buffer -p (bracketed paste).
    Returns run_id if successful, None otherwise.
    """
    if not is_tmux_running(session_name) or not is_conversation_active_in_terminal(conv_id):
        return None

    run_id = f"run_{uuid.uuid4().hex[:12]}"
    insert_run(run_id=run_id, conversation_id=conv_id, workspace="/root", prompt=message, status="running")

    try:
        # Load prompt text into tmux buffer via stdin to avoid shell escaping issues
        proc = await asyncio.create_subprocess_exec(
            "tmux", "load-buffer", "-",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        await proc.communicate(input=message.encode("utf-8"))

        # Paste buffer into the pane with bracketed paste mode (-p) so newlines are not treated as Enter
        await asyncio.create_subprocess_exec("tmux", "paste-buffer", "-p", "-t", session_name)
        await asyncio.sleep(0.08)
        # Send Enter to submit the prompt
        await asyncio.create_subprocess_exec("tmux", "send-keys", "-t", session_name, "Enter")

        logger.info(f"Successfully injected message into tmux '{session_name}' for {conv_id} (run_id={run_id})")
        return run_id
    except Exception as e:
        logger.error(f"Failed to inject message into tmux session: {e}")
        return None

async def cancel_tmux_session(session_name: str = "agy") -> bool:
    """
    Sends Escape and Ctrl+C to the active tmux pane to cancel running command/agent turn.
    """
    if not is_tmux_running(session_name):
        return False
    try:
        proc_esc = await asyncio.create_subprocess_exec("tmux", "send-keys", "-t", session_name, "Escape")
        await proc_esc.communicate()
        await asyncio.sleep(0.05)
        proc_c = await asyncio.create_subprocess_exec("tmux", "send-keys", "-t", session_name, "C-c")
        await proc_c.communicate()
        return True
    except Exception as e:
        logger.error(f"Failed to send cancel keys to tmux: {e}")
        return False
