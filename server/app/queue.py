from __future__ import annotations

import asyncio
import logging
import uuid
from typing import Optional
from app.config import settings
from app.db import insert_run
from app.runner import execute_agy_turn

logger = logging.getLogger("agy_gateway.queue")

class TaskQueue:
    def __init__(self, max_concurrent: int):
        self.global_semaphore = asyncio.Semaphore(max_concurrent)
        self.conv_locks: dict[str, asyncio.Lock] = {}
        self._lock_guard = asyncio.Lock()

    async def get_conv_lock(self, conv_id: str) -> asyncio.Lock:
        async with self._lock_guard:
            if conv_id not in self.conv_locks:
                self.conv_locks[conv_id] = asyncio.Lock()
            return self.conv_locks[conv_id]

    async def submit_new_chat(
        self,
        workspace: str,
        message: str,
        model: Optional[str] = None,
        effort: Optional[str] = None,
        mode: Optional[str] = None,
    ) -> tuple[str, str]:
        """
        Submits a new chat. Waits until the 'init' event is processed and conversation_id is known,
        then returns (conversation_id, run_id). The run continues in the background.
        """
        run_id = f"run_{uuid.uuid4().hex[:12]}"
        insert_run(run_id=run_id, conversation_id="", workspace=workspace, prompt=message, status="queued")

        loop = asyncio.get_running_loop()
        init_future: asyncio.Future[str] = loop.create_future()

        async def worker():
            async with self.global_semaphore:
                # We start the turn without conversation_id
                # Once init event arrives, init_future will be set with the new conversation_id
                run_task = asyncio.create_task(
                    execute_agy_turn(
                        run_id=run_id,
                        conversation_id=None,
                        workspace=workspace,
                        message=message,
                        model=model,
                        effort=effort,
                        mode=mode,
                        init_future=init_future,
                    )
                )

                try:
                    conv_id = await asyncio.wait_for(asyncio.shield(init_future), timeout=30.0)
                    # Acquire lock for this conversation to prevent subsequent messages from overlapping
                    lock = await self.get_conv_lock(conv_id)
                    async with lock:
                        await run_task
                except Exception:
                    await run_task

        asyncio.create_task(worker())

        # Wait for conversation_id to be determined from init event
        conversation_id = await asyncio.wait_for(init_future, timeout=30.0)
        return conversation_id, run_id

    async def submit_message(
        self,
        conversation_id: str,
        workspace: str,
        message: str,
        model: Optional[str] = None,
        effort: Optional[str] = None,
        mode: Optional[str] = None,
    ) -> str:
        """
        Submits a message to an existing conversation. Returns run_id immediately (status queued).
        Executes sequentially behind any existing run on this conversation and respects global concurrency.
        """
        run_id = f"run_{uuid.uuid4().hex[:12]}"
        insert_run(run_id=run_id, conversation_id=conversation_id, workspace=workspace, prompt=message, status="queued")

        async def worker():
            conv_lock = await self.get_conv_lock(conversation_id)
            async with conv_lock:
                async with self.global_semaphore:
                    await execute_agy_turn(
                        run_id=run_id,
                        conversation_id=conversation_id,
                        workspace=workspace,
                        message=message,
                        model=model,
                        effort=effort,
                        mode=mode,
                    )

        asyncio.create_task(worker())
        return run_id

task_queue = TaskQueue(max_concurrent=settings.max_concurrent_runs)
