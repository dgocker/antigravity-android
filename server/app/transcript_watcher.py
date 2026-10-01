from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from typing import Optional
from app.config import settings
from app.db import log_event
from app.hub import hub
from app.runner import get_transcript_path
from app.session_detector import is_conversation_active_in_terminal
from app.step_parser import parse_transcript_line_to_step
from app.tmux_injector import is_tmux_turn_active, get_chat_session_name, is_tmux_running

logger = logging.getLogger("agy_gateway.watcher")

def get_highest_monotonic_step_index(transcript_path: Path) -> tuple[int, int]:
    """
    Scans the transcript file to establish monotonic step indexing matching get_chat_steps.
    Returns (highest_step_index, file_offset).
    """
    last_idx = -1
    offset = 0
    if transcript_path.is_file():
        try:
            with open(transcript_path, "r", encoding="utf-8") as f:
                for line in f:
                    s = parse_transcript_line_to_step(line)
                    if s is not None:
                        if s.step_index <= last_idx:
                            s.step_index = last_idx + 1
                        last_idx = s.step_index
                offset = f.tell()
        except Exception as e:
            logger.warning(f"Error computing monotonic index for {transcript_path}: {e}")
    return last_idx, offset

class TranscriptWatcher:
    def __init__(self, conversation_id: str):
        self.conversation_id = conversation_id
        self.stop_event = asyncio.Event()
        self.task: Optional[asyncio.Task] = None
        self.transcript_path = get_transcript_path(conversation_id)
        self.last_step_index = -1
        self.file_offset = 0
        self.is_active = False
        self._last_active_check = 0.0
        self._idle_consecutive = 0

    async def start(self):
        self.last_step_index, self.file_offset = get_highest_monotonic_step_index(self.transcript_path)
        self.task = asyncio.create_task(self._watch_loop())

    async def stop(self):
        self.stop_event.set()
        if self.task:
            await asyncio.gather(self.task, return_exceptions=True)

    async def _watch_loop(self):
        logger.info(f"Started TranscriptWatcher for conversation {self.conversation_id} (offset={self.file_offset}, last_step={self.last_step_index})")
        tmux_active = is_tmux_turn_active()
        self.is_active = tmux_active
        await hub.broadcast_live({
            "type": "agent_activity",
            "conversation_id": self.conversation_id,
            "activity": "thinking" if tmux_active else "idle",
            "detail": "Working in terminal..." if tmux_active else "",
        })
        while not self.stop_event.is_set():
            if self.transcript_path.is_file():
                try:
                    curr_size = self.transcript_path.stat().st_size
                    if curr_size < self.file_offset:
                        logger.info(f"Transcript file truncated/compacted (size={curr_size} < offset={self.file_offset}), resetting offset")
                        self.last_step_index, self.file_offset = get_highest_monotonic_step_index(self.transcript_path)
                    elif curr_size > self.file_offset:
                        with open(self.transcript_path, "r", encoding="utf-8") as f:
                            f.seek(self.file_offset)
                            while True:
                                line = f.readline()
                                if not line:
                                    break
                                self.file_offset = f.tell()
                                step = parse_transcript_line_to_step(line)
                                if step is not None:
                                    if step.step_index <= self.last_step_index:
                                        step.step_index = self.last_step_index + 1
                                    self.last_step_index = step.step_index

                                    ev = log_event(
                                        self.conversation_id,
                                        f"step_{step.step_index}",
                                        "step",
                                        step.model_dump(),
                                    )
                                    await hub.broadcast_event(ev)

                                    # Emit live agent activity based on step contents
                                    if step.source == "USER_EXPLICIT" or step.type == "USER_INPUT":
                                        self.is_active = True
                                        await hub.broadcast_live({
                                            "type": "agent_activity",
                                            "conversation_id": self.conversation_id,
                                            "activity": "thinking",
                                            "detail": "Thinking...",
                                        })
                                    elif step.tool_calls:
                                        self.is_active = True
                                        t_name = step.tool_calls[0].name
                                        await hub.broadcast_live({
                                            "type": "agent_activity",
                                            "conversation_id": self.conversation_id,
                                            "activity": "tool_running",
                                            "tool_name": t_name,
                                            "detail": f"Running {t_name}...",
                                            "parameters": step.tool_calls[0].args,
                                        })
                                    elif step.type in ("GENERIC", "TOOL_RESPONSE"):
                                        self.is_active = True
                                        await hub.broadcast_live({
                                            "type": "agent_activity",
                                            "conversation_id": self.conversation_id,
                                            "activity": "thinking",
                                            "detail": "Thinking...",
                                        })
                                    elif step.status == "DONE" and not step.tool_calls:
                                        session_name = get_chat_session_name(self.conversation_id)
                                        if is_tmux_turn_active(session_name):
                                            self.is_active = True
                                            self._idle_consecutive = 0
                                            await hub.broadcast_live({
                                                "type": "agent_activity",
                                                "conversation_id": self.conversation_id,
                                                "activity": "thinking",
                                                "detail": "Thinking...",
                                            })
                                        else:
                                            self.is_active = False
                                            await hub.broadcast_live({
                                                "type": "agent_activity",
                                                "conversation_id": self.conversation_id,
                                                "activity": "idle",
                                                "detail": "",
                                            })
                except Exception as e:
                    logger.debug(f"Error in TranscriptWatcher loop: {e}")

            # Periodically synchronize terminal active status with the mobile app
            loop = asyncio.get_event_loop()
            now = loop.time()
            if now - self._last_active_check > 0.5:
                self._last_active_check = now
                session_name = get_chat_session_name(self.conversation_id)
                if is_tmux_running(session_name):
                    tmux_active = is_tmux_turn_active(session_name)
                    if tmux_active:
                        self._idle_consecutive = 0
                        if not self.is_active:
                            self.is_active = True
                            await hub.broadcast_live({
                                "type": "agent_activity",
                                "conversation_id": self.conversation_id,
                                "activity": "thinking",
                                "detail": "Working in terminal...",
                            })
                    else:
                        self._idle_consecutive += 1
                        if self._idle_consecutive >= 2 and self.is_active:
                            self.is_active = False
                            await hub.broadcast_live({
                                "type": "agent_activity",
                                "conversation_id": self.conversation_id,
                                "activity": "idle",
                                "detail": "",
                            })
                else:
                    if self.is_active:
                        self.is_active = False
                        await hub.broadcast_live({
                            "type": "agent_activity",
                            "conversation_id": self.conversation_id,
                            "activity": "idle",
                            "detail": "",
                        })

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
