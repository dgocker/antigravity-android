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
            if "press ctrl+c again to exit" in tail:
                return False
            if "Navigate" in tail and "Complete" in tail:
                return False
            if "esc to cancel" in tail and ("enter Select" in tail or "Effort" in tail):
                return False
            spinners = ("⣷", "⣯", "⣟", "⡿", "⢿", "⣻", "⣽", "⣾")
            if any(s in tail for s in spinners):
                return True
            if "Running command" in tail or "Thinking..." in tail or "Executing" in tail:
                return True
            if "esc to cancel" in tail:
                return True
    except Exception:
        pass
    return False

def get_chat_session_name(conv_id: str) -> str:
    if is_tmux_running(f"agy-{conv_id}"):
        return f"agy-{conv_id}"
    if is_conversation_active_in_terminal(conv_id) and is_tmux_running("agy"):
        return "agy"
    return f"agy-{conv_id}"

def is_chat_session_running(conv_id: str) -> bool:
    if is_tmux_running(f"agy-{conv_id}"):
        return True
    if is_conversation_active_in_terminal(conv_id) and is_tmux_running("agy"):
        return True
    return False

async def ensure_chat_session(conv_id: str, workspace: str) -> str:
    session_name = get_chat_session_name(conv_id)
    if not is_tmux_running(session_name):
        session_name = f"agy-{conv_id}"
        os.makedirs(workspace, exist_ok=True)
        from app.config import settings
        # Start interactive session with agy --conversation <conv_id>
        cmd = [
            "tmux", "new-session", "-d", "-s", session_name,
            "-c", workspace,
            f"{settings.agy_bin} --conversation {conv_id} --add-dir {workspace} --dangerously-skip-permissions"
        ]
        proc = await asyncio.create_subprocess_exec(*cmd)
        await proc.wait()
        # Wait for interactive prompt to be ready
        for _ in range(12):
            await asyncio.sleep(0.25)
            try:
                chk = subprocess.run(
                    ["tmux", "capture-pane", "-t", session_name, "-p"],
                    capture_output=True,
                    text=True,
                    timeout=1,
                )
                if chk.returncode == 0 and ">" in chk.stdout:
                    break
            except Exception:
                pass
    return session_name

async def kill_chat_session(conv_id: str) -> bool:
    target_sessions = [f"agy-{conv_id}"]
    if is_conversation_active_in_terminal(conv_id):
        target_sessions.append("agy")

    killed = False
    for s in target_sessions:
        if is_tmux_running(s):
            try:
                proc = await asyncio.create_subprocess_exec("tmux", "kill-session", "-t", s)
                await proc.wait()
                killed = True
                logger.info(f"Killed tmux session '{s}' for conversation {conv_id}")
            except Exception as e:
                logger.error(f"Error killing tmux session {s}: {e}")
    return killed

async def inject_message_to_tmux(
    conv_id: str,
    message: str,
    session_name: Optional[str] = None,
    workspace: Optional[str] = None,
) -> Optional[str]:
    """
    Injects prompt text directly into the active tmux session via load-buffer and paste-buffer -p (bracketed paste).
    Ensures tmux session is created if necessary.
    Returns run_id if successful, None otherwise.
    """
    if not session_name:
        if workspace:
            session_name = await ensure_chat_session(conv_id, workspace)
        else:
            session_name = get_chat_session_name(conv_id)

    if not is_tmux_running(session_name):
        return None

    run_id = f"run_{uuid.uuid4().hex[:12]}"
    insert_run(run_id=run_id, conversation_id=conv_id, workspace=workspace or "/root", prompt=message, status="running")

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
        proc_paste = await asyncio.create_subprocess_exec("tmux", "paste-buffer", "-p", "-t", session_name)
        await proc_paste.wait()

        # Give prompt-toolkit / readline time to process the bracketed paste delimiter (\e[201~)
        await asyncio.sleep(0.2)

        # Send Enter to submit the prompt
        proc_enter = await asyncio.create_subprocess_exec("tmux", "send-keys", "-t", session_name, "Enter")
        await proc_enter.wait()

        # Verification & retry loop: confirm if turn started or prompt is still sitting on input line
        for retry in range(3):
            await asyncio.sleep(0.25)
            chk = subprocess.run(
                ["tmux", "capture-pane", "-t", session_name, "-p"],
                capture_output=True,
                text=True,
                timeout=1,
            )
            if chk.returncode == 0:
                lines = chk.stdout.splitlines()[-10:]
                tail = "\n".join(lines)
                spinners = ("⣷", "⣯", "⣟", "⡿", "⢿", "⣻", "⣽", "⣾")
                if any(s in tail for s in spinners) or "Thinking..." in tail or "Running command" in tail or "Executing" in tail:
                    break
                snippet = message.strip()[:20]
                if any(f"> {snippet}" in l or f">  {snippet}" in l for l in lines):
                    logger.warning(f"Prompt text still sitting on input line after Enter (retry {retry+1}), resending Enter...")
                    p_retry = await asyncio.create_subprocess_exec("tmux", "send-keys", "-t", session_name, "C-m")
                    await p_retry.wait()
                else:
                    break

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
