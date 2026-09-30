# Terminal and App Real-Time Synchronization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement real-time bidirectional synchronization between the active interactive Antigravity CLI terminal session on the VPS (running in tmux) and the Android mobile client.

**Architecture:** A presence detector in `agy-gateway` checks file locks in `/root/.gemini/antigravity-cli/presence/<id>.lock` to determine if a chat is active in tmux; if active, messages from the app are injected directly into the tmux session via `load-buffer` and `paste-buffer`, avoiding parallel processes. A background `TranscriptWatcherManager` continuously tails `transcript_full.jsonl`, broadcasting steps, tool calls, and agent activity to connected WebSocket clients in real time. The Android client dynamically subscribes to the active chat and reconciles incoming steps in Room.

**Tech Stack:** Python 3.12 (FastAPI, asyncio, fcntl, tmux), SQLite WAL (`gateway.db`), Kotlin, Jetpack Compose, Room Database, OkHttp WebSocket.

**Spec:** `docs/superpowers/specs/2026-09-30-terminal-app-sync-design.md`

## Global Constraints

- Never run gradle locally on the VPS (build exclusively via GitHub Actions CI).
- Do not kill or disturb existing user processes (`sshd`, `xray`, `nginx`, `gitea`, or active interactive tmux session).
- Bearer authentication and path traversal protection must remain strictly enforced.
- Memory consumption of `agy-gateway` must remain under 300MB.

---

### Task 1: Active Session Presence Detector & Tmux Injector

**Files:**
- Create: `/root/agy-gateway/app/session_detector.py`
- Create: `/root/agy-gateway/app/tmux_injector.py`
- Modify: `/root/agy-gateway/app/main.py:287-320`
- Test: `/root/agy-gateway/tests/test_tmux_injector.py`

**Interfaces:**
- Produces:
  - `is_conversation_active_in_terminal(conv_id: str) -> bool`
  - `inject_message_to_tmux(conv_id: str, message: str) -> Optional[str]`
  - `cancel_tmux_session(conv_id: str) -> bool`

- [ ] **Step 1: Write unit tests for session detection and tmux injection**

Create `/root/agy-gateway/tests/test_tmux_injector.py`:
```python
import os
import pytest
from app.session_detector import is_conversation_active_in_terminal
from app.tmux_injector import is_tmux_running, inject_message_to_tmux

def test_session_detector_nonexistent():
    assert is_conversation_active_in_terminal("nonexistent-conv-id-12345") is False

def test_tmux_running_check():
    # agy tmux session is running on the VPS
    assert is_tmux_running("agy") is True
    assert is_tmux_running("nonexistent_session_9999") is False
```

- [ ] **Step 2: Run test to verify it fails before implementation**

Run:
```bash
/root/agy-gateway/venv/bin/python3 -m pytest /root/agy-gateway/tests/test_tmux_injector.py -v
```
Expected: FAIL (ModuleNotFoundError: No module named 'app.session_detector')

- [ ] **Step 3: Implement `session_detector.py`**

Create `/root/agy-gateway/app/session_detector.py`:
```python
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
```

- [ ] **Step 4: Implement `tmux_injector.py`**

Create `/root/agy-gateway/app/tmux_injector.py`:
```python
from __future__ import annotations

import asyncio
import logging
import shutil
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

async def inject_message_to_tmux(conv_id: str, message: str, session_name: str = "agy") -> Optional[str]:
    """
    Injects prompt text directly into the active tmux session via load-buffer and paste-buffer.
    Returns run_id if successful, None otherwise.
    """
    if not is_tmux_running(session_name) or not is_conversation_active_in_terminal(conv_id):
        return None

    run_id = f"run_{uuid.uuid4().hex[:12]}"
    insert_run(run_id=run_id, conversation_id=conv_id, workspace="/root", prompt=message, status="running")

    # Load prompt text into tmux buffer via stdin to avoid shell escaping issues
    proc = await asyncio.create_subprocess_exec(
        "tmux", "load-buffer", "-",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    await proc.communicate(input=message.encode("utf-8"))

    # Paste buffer and press Enter
    await asyncio.create_subprocess_exec("tmux", "paste-buffer", "-t", session_name)
    await asyncio.sleep(0.05)
    await asyncio.create_subprocess_exec("tmux", "send-keys", "-t", session_name, "Enter")

    logger.info(f"Injected message into tmux session '{session_name}' for conversation {conv_id} (run_id={run_id})")
    return run_id

async def cancel_tmux_session(session_name: str = "agy") -> bool:
    """
    Sends Ctrl+C to the active tmux pane to cancel running command/agent turn.
    """
    if not is_tmux_running(session_name):
        return False
    proc = await asyncio.create_subprocess_exec("tmux", "send-keys", "-t", session_name, "C-c")
    await proc.communicate()
    return proc.returncode == 0
```

- [ ] **Step 5: Run tests and verify they pass**

Run:
```bash
/root/agy-gateway/venv/bin/python3 -m pytest /root/agy-gateway/tests/test_tmux_injector.py -v
```
Expected: PASS

- [ ] **Step 6: Update `main.py` to route messages through `tmux_injector` when terminal is active**

In `/root/agy-gateway/app/main.py`:
In `send_message()`:
```python
    # Check if this conversation is actively open in tmux
    if is_conversation_active_in_terminal(id):
        tmux_run_id = await inject_message_to_tmux(id, prompt)
        if tmux_run_id:
            for att in req.attachments:
                try:
                    update_attachment_conversation(att.id, id)
                except Exception:
                    pass
            # Ensure transcript watcher is active for this conversation
            await watcher_manager.ensure_watcher(id)
            return SendMessageResponse(run_id=tmux_run_id, status="running")
```

In `cancel_chat_run()`:
```python
    if is_conversation_active_in_terminal(id):
        await cancel_tmux_session()
        return CancelResponse(conversation_id=id, status="cancelling", run_id="tmux_cancel")
```

---

### Task 2: Server Global Transcript Watcher

**Files:**
- Create: `/root/agy-gateway/app/transcript_watcher.py`
- Modify: `/root/agy-gateway/app/hub.py`
- Modify: `/root/agy-gateway/app/main.py`
- Test: `/root/agy-gateway/tests/test_transcript_watcher.py`

**Interfaces:**
- Produces:
  - `TranscriptWatcherManager`
  - `TranscriptWatcherManager.ensure_watcher(conv_id: str) -> None`
  - `TranscriptWatcherManager.stop_watcher(conv_id: str) -> None`
  - `TranscriptWatcherManager.on_client_subscribed(conv_id: str) -> None`

- [ ] **Step 1: Write test for `transcript_watcher.py`**

Create `/root/agy-gateway/tests/test_transcript_watcher.py`:
```python
import asyncio
import os
import pytest
from app.transcript_watcher import TranscriptWatcherManager

@pytest.mark.asyncio
async def test_watcher_lifecycle():
    manager = TranscriptWatcherManager()
    await manager.ensure_watcher("test-conv-id")
    assert "test-conv-id" in manager.active_watchers
    await manager.stop_watcher("test-conv-id")
    assert "test-conv-id" not in manager.active_watchers
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
/root/agy-gateway/venv/bin/python3 -m pytest /root/agy-gateway/tests/test_transcript_watcher.py -v
```
Expected: FAIL

- [ ] **Step 3: Implement `TranscriptWatcherManager`**

Create `/root/agy-gateway/app/transcript_watcher.py`:
```python
from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from typing import Optional
from app.config import settings
from app.db import log_event
from app.hub import hub
from app.runner import get_transcript_path, get_highest_step_index_in_file
from app.step_parser import parse_transcript_line_to_step

logger = logging.getLogger("agy_gateway.watcher")

class TranscriptWatcher:
    def __init__(self, conversation_id: str):
        self.conversation_id = conversation_id
        self.stop_event = asyncio.Event()
        self.task: Optional[asyncio.Task] = None
        self.transcript_path = get_transcript_path(conversation_id)
        self.last_step_index = -1
        self.file_offset = 0

    async def start(self):
        self.last_step_index = get_highest_step_index_in_file(self.transcript_path)
        if self.transcript_path.is_file():
            self.file_offset = self.transcript_path.stat().st_size
        self.task = asyncio.create_task(self._watch_loop())

    async def stop(self):
        self.stop_event.set()
        if self.task:
            await asyncio.gather(self.task, return_exceptions=True)

    async def _watch_loop(self):
        logger.info(f"Started TranscriptWatcher for conversation {self.conversation_id} (offset={self.file_offset}, last_step={self.last_step_index})")
        while not self.stop_event.is_set():
            if self.transcript_path.is_file():
                try:
                    curr_size = self.transcript_path.stat().st_size
                    if curr_size > self.file_offset:
                        with open(self.transcript_path, "r", encoding="utf-8") as f:
                            f.seek(self.file_offset)
                            while True:
                                line = f.readline()
                                if not line:
                                    break
                                self.file_offset = f.tell()
                                step = parse_transcript_line_to_step(line)
                                if step and step.step_index > self.last_step_index:
                                    self.last_step_index = step.step_index
                                    ev = log_event(
                                        self.conversation_id,
                                        f"step_{step.step_index}",
                                        "step",
                                        step.model_dump(),
                                    )
                                    await hub.broadcast_event(ev)

                                    # Emit agent activity based on step contents
                                    if step.source == "USER_EXPLICIT":
                                        await hub.broadcast_live({
                                            "type": "agent_activity",
                                            "conversation_id": self.conversation_id,
                                            "activity": "thinking",
                                            "detail": "Thinking...",
                                        })
                                    elif step.tool_calls:
                                        t_name = step.tool_calls[0].name
                                        await hub.broadcast_live({
                                            "type": "agent_activity",
                                            "conversation_id": self.conversation_id,
                                            "activity": "tool_running",
                                            "tool_name": t_name,
                                            "detail": f"Running {t_name}...",
                                            "parameters": step.tool_calls[0].args,
                                        })
                                    elif step.type == "PLANNER_RESPONSE" and step.content:
                                        await hub.broadcast_live({
                                            "type": "agent_activity",
                                            "conversation_id": self.conversation_id,
                                            "activity": "idle",
                                            "detail": "",
                                        })
                except Exception as e:
                    logger.debug(f"Error in TranscriptWatcher loop: {e}")
            await asyncio.sleep(0.08)

class TranscriptWatcherManager:
    def __init__(self):
        self.active_watchers: dict[str, TranscriptWatcher] = {}
        self._lock = asyncio.Lock()

    async def ensure_watcher(self, conversation_id: str):
        if not conversation_id:
            return
        async with self._lock:
            if conversation_id not in self.active_watchers:
                watcher = TranscriptWatcher(conversation_id)
                await watcher.start()
                self.active_watchers[conversation_id] = watcher

    async def stop_watcher(self, conversation_id: str):
        async with self._lock:
            watcher = self.active_watchers.pop(conversation_id, None)
            if watcher:
                await watcher.stop()

watcher_manager = TranscriptWatcherManager()
```

- [ ] **Step 4: Run tests and verify they pass**

Run:
```bash
/root/agy-gateway/venv/bin/python3 -m pytest /root/agy-gateway/tests/test_transcript_watcher.py -v
```
Expected: PASS

---

### Task 3: WebSocket Protocol Extensions & Live Subscription

**Files:**
- Modify: `/root/agy-gateway/app/hub.py`
- Modify: `/root/agy-gateway/app/main.py`
- Test: `/root/agy-gateway/tests/test_websocket_subscribe.py`

**Interfaces:**
- Handles incoming JSON message on `/v1/ws`: `{"type": "subscribe", "conversation_id": "..."}`
- Automatically invokes `watcher_manager.ensure_watcher(conv_id)`

- [ ] **Step 1: Write test for WebSocket subscription**

Create `/root/agy-gateway/tests/test_websocket_subscribe.py`:
```python
import pytest
from app.hub import WebSocketClient, hub
from unittest.mock import AsyncMock

@pytest.mark.asyncio
async def test_handle_subscribe_message():
    mock_ws = AsyncMock()
    client = WebSocketClient(mock_ws, authenticated=True)
    await client.handle_incoming_text('{"type":"subscribe","conversation_id":"test-conv-456"}')
    assert client.conversation_id == "test-conv-456"
```

- [ ] **Step 2: Update `WebSocketClient` in `hub.py` to handle `subscribe`**

In `/root/agy-gateway/app/hub.py`:
Add:
```python
            if msg_type == "subscribe":
                cid = data.get("conversation_id", "")
                if cid:
                    self.conversation_id = cid
                    from app.transcript_watcher import watcher_manager
                    asyncio.create_task(watcher_manager.ensure_watcher(cid))
```

- [ ] **Step 3: Run test and verify it passes**

Run:
```bash
/root/agy-gateway/venv/bin/python3 -m pytest /root/agy-gateway/tests/test_websocket_subscribe.py -v
```
Expected: PASS

- [ ] **Step 4: Restart agy-gateway service and verify status**

Run:
```bash
systemctl restart agy-gateway && systemctl status agy-gateway --no-pager
```
Expected: active (running)

---

### Task 4: Android SyncEngine & WebSocket Dynamic Subscription

**Files:**
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/sync/WebSocketManager.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/sync/SyncEngine.kt`
- Modify: `/root/agy-android/app/src/main/java/com/antigravity/client/ui/chat/ChatViewModel.kt`
- Modify: `/root/agy-android/app/build.gradle.kts` (bump version to 1.0.20)

**Interfaces:**
- Produces: `syncEngine.subscribeToConversation(conversationId: String)`
- Produces: `webSocketManager.subscribe(conversationId: String)`

- [ ] **Step 1: Update `WebSocketManager.kt` to send subscribe message and track active conversation**

In `WebSocketManager.kt`:
```kotlin
    private var activeConversationId: String? = null

    fun subscribe(conversationId: String) {
        activeConversationId = conversationId
        val ws = webSocket
        if (ws != null && _connectionState.value == ConnectionStatus.LIVE) {
            val json = JSONObject().apply {
                put("type", "subscribe")
                put("conversation_id", conversationId)
            }
            ws.send(json.toString())
        }
    }
```
In `onOpen`:
```kotlin
    activeConversationId?.let { cid ->
        val json = JSONObject().apply {
            put("type", "subscribe")
            put("conversation_id", cid)
        }
        webSocket.send(json.toString())
    }
```

- [ ] **Step 2: Expose `subscribeToConversation` in `SyncEngine.kt`**

```kotlin
    fun subscribeToConversation(conversationId: String) {
        webSocketManager.subscribe(conversationId)
    }
```

- [ ] **Step 3: Call `subscribeToConversation` in `ChatViewModel.kt`**

In `ChatViewModel.init`:
```kotlin
    syncEngine.subscribeToConversation(conversationId)
```

- [ ] **Step 4: Bump version to `v1.0.20` in `app/build.gradle.kts`**

Set `versionCode = 20`, `versionName = "1.0.20"`.

- [ ] **Step 5: Commit, tag `v1.0.20`, and push to GitHub Actions CI**

Run:
```bash
git add -A && git commit -m "feat: real-time terminal sync and tmux bridge (v1.0.20)"
git tag v1.0.20
git push origin main && git push origin v1.0.20
```

- [ ] **Step 6: Watch CI build, download APK, and send to Telegram**

Run:
```bash
gh run watch <run_id> -R dgocker/antigravity-android
gh release download v1.0.20 -R dgocker/antigravity-android -p "app-debug.apk" -D /tmp/ --clobber
python3 /root/.agents/skills/telegram/scripts/tg.py file /tmp/app-debug.apk --caption "🚀 Antigravity Android v1.0.20 with real-time terminal synchronization"
```

---

### Task 5: End-to-End Validation & Live Testing

**Files:**
- Test: Live testing with Termius tmux session `agy` and the Android app

- [ ] **Step 1: Test Terminal -> App streaming**
  - Type a test prompt in Termius in tmux `agy`.
  - Verify that the message immediately appears in the Android app without touching the phone or reloading the screen.
  - Verify that the agent's thinking and response stream in real time.

- [ ] **Step 2: Test App -> Terminal injection**
  - Send a message from the Android app composer.
  - Verify that the message instantly appears on the screen in Termius in the `agy` session and is answered in the same session without creating a parallel process.
