from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import json
import logging
import os
from pathlib import Path
from typing import Any, Optional
from app.config import settings
from app.db import log_event, update_run_status
from app.hub import hub
from app.step_parser import parse_transcript_line_to_step

logger = logging.getLogger("agy_gateway.runner")

class ActiveRunInfo:
    def __init__(self, run_id: str, conversation_id: str, process: asyncio.subprocess.Process):
        self.run_id = run_id
        self.conversation_id = conversation_id
        self.process = process
        self.cancel_requested = False

active_runs: dict[str, ActiveRunInfo] = {}

def get_transcript_path(conversation_id: str) -> Path:
    return Path(settings.brain_dir) / conversation_id / ".system_generated" / "logs" / "transcript_full.jsonl"

def get_highest_step_index_in_file(file_path: Path) -> int:
    highest = -1
    if not file_path.is_file():
        return highest
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        d = json.loads(line)
                        idx = d.get("step_index", -1)
                        if idx > highest:
                            highest = idx
                    except Exception:
                        pass
    except Exception:
        pass
    return highest

async def cancel_active_run(conversation_id: str) -> Optional[str]:
    run_info = active_runs.get(conversation_id)
    if not run_info or not run_info.process:
        return None

    run_info.cancel_requested = True
    proc = run_info.process
    run_id = run_info.run_id

    try:
        proc.terminate()  # Send SIGTERM
        logger.info(f"Sent SIGTERM to process PID {proc.pid} for run {run_id}")
    except ProcessLookupError:
        return run_id

    async def force_kill_watchdog():
        try:
            await asyncio.sleep(5)
            if proc.returncode is None:
                logger.warning(f"Process PID {proc.pid} did not exit after 5s; sending SIGKILL")
                proc.kill()  # Send SIGKILL
        except ProcessLookupError:
            pass
        except Exception as e:
            logger.error(f"Error in SIGKILL watchdog: {e}")

    asyncio.create_task(force_kill_watchdog())
    return run_id

async def execute_agy_turn(
    run_id: str,
    conversation_id: Optional[str],
    workspace: str,
    message: str,
    model: Optional[str] = None,
    effort: Optional[str] = None,
    mode: Optional[str] = None,
    init_future: Optional[asyncio.Future[str]] = None,
) -> None:
    now = datetime.now(timezone.utc).isoformat()
    os.makedirs(workspace, exist_ok=True)

    cmd = [
        settings.agy_bin,
        "-p", message,
        "--output-format", "stream-json",
        "--dangerously-skip-permissions",
        "--add-dir", workspace,
    ]
    if conversation_id:
        cmd.extend(["--conversation", conversation_id])
    if model:
        cmd.extend(["--model", model])
    if effort:
        cmd.extend(["--effort", effort])
    if mode:
        cmd.extend(["--mode", mode])

    logger.info(f"Starting agy run {run_id}: {' '.join(cmd[:6])}...")
    update_run_status(run_id, "running", started_at=now)

    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            cwd=workspace,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except Exception as e:
        logger.error(f"Failed to spawn agy process: {e}")
        err_msg = str(e)
        if init_future and not init_future.done():
            init_future.set_exception(e)
        update_run_status(run_id, "failed", error=err_msg, finished_at=now)
        ev = log_event(conversation_id or "unknown", run_id, "run_error", {
            "run_id": run_id,
            "conversation_id": conversation_id or "unknown",
            "error": err_msg,
        })
        await hub.broadcast_event(ev)
        return

    confirmed_conv_id = conversation_id or ""
    run_info = ActiveRunInfo(run_id=run_id, conversation_id=confirmed_conv_id, process=proc)
    if confirmed_conv_id:
        active_runs[confirmed_conv_id] = run_info

    transcript_path: Optional[Path] = None
    last_processed_step_index = -1
    if confirmed_conv_id:
        transcript_path = get_transcript_path(confirmed_conv_id)
        last_processed_step_index = get_highest_step_index_in_file(transcript_path)

    tail_stop_event = asyncio.Event()
    result_data: dict[str, Any] = {}
    stderr_lines: list[str] = []

    async def tail_transcript_worker():
        nonlocal last_processed_step_index, transcript_path, confirmed_conv_id
        # Wait until confirmed_conv_id and transcript_path are ready
        while not confirmed_conv_id or not transcript_path:
            if tail_stop_event.is_set():
                break
            await asyncio.sleep(0.05)

        file_offset = 0
        while True:
            if transcript_path.is_file():
                try:
                    with open(transcript_path, "r", encoding="utf-8") as f:
                        f.seek(file_offset)
                        while True:
                            line = f.readline()
                            if not line:
                                break
                            file_offset = f.tell()
                            step = parse_transcript_line_to_step(line)
                            if step and step.step_index > last_processed_step_index:
                                last_processed_step_index = step.step_index
                                ev = log_event(
                                    confirmed_conv_id,
                                    run_id,
                                    "step",
                                    step.model_dump(),
                                )
                                await hub.broadcast_event(ev)
                except Exception as e:
                    logger.debug(f"Transcript read loop exception: {e}")

            if tail_stop_event.is_set():
                # Processed final pass, exit loop
                break
            await asyncio.sleep(0.1)

    tail_task = asyncio.create_task(tail_transcript_worker())

    async def read_stdout_worker():
        nonlocal confirmed_conv_id, transcript_path, last_processed_step_index, result_data
        if not proc.stdout:
            return

        while True:
            raw_line = await proc.stdout.readline()
            if not raw_line:
                break
            line_str = raw_line.decode("utf-8", errors="ignore").strip()
            if not line_str:
                continue

            try:
                msg = json.loads(line_str)
            except Exception:
                continue

            event_type = msg.get("event")

            # Handle "init" event
            if event_type == "init":
                conv_id = msg.get("conversation_id", "")
                if conv_id:
                    confirmed_conv_id = conv_id
                    run_info.conversation_id = conv_id
                    active_runs[conv_id] = run_info
                    transcript_path = get_transcript_path(conv_id)
                    if last_processed_step_index == -1:
                        last_processed_step_index = get_highest_step_index_in_file(transcript_path)

                    if init_future and not init_future.done():
                        init_future.set_result(conv_id)

                    update_run_status(run_id, "running", conversation_id=conv_id)
                    start_ev = log_event(
                        conv_id,
                        run_id,
                        "run_started",
                        {
                            "run_id": run_id,
                            "conversation_id": conv_id,
                            "workspace": workspace,
                            "prompt": message,
                            "model": model,
                            "effort": effort,
                            "mode": mode,
                        },
                    )
                    await hub.broadcast_event(start_ev)
                    await hub.broadcast_live({
                        "type": "agent_activity",
                        "conversation_id": conv_id,
                        "run_id": run_id,
                        "activity": "thinking",
                        "detail": "Thinking...",
                    })

            # Handle "step_update" event: stream text_delta live and report tools/thinking
            elif event_type == "step_update":
                step_update = msg.get("step_update", {})
                text_delta = step_update.get("text_delta")
                step_type = step_update.get("step_type")
                state = step_update.get("state")
                cid = confirmed_conv_id or conversation_id or ""

                if text_delta:
                    await hub.broadcast_live({
                        "type": "text_delta",
                        "conversation_id": cid,
                        "run_id": run_id,
                        "step_index": step_update.get("step_index"),
                        "text_delta": text_delta,
                    })
                    await hub.broadcast_live({
                        "type": "agent_activity",
                        "conversation_id": cid,
                        "run_id": run_id,
                        "activity": "generating",
                        "detail": "Writing response...",
                    })
                elif step_type == "tool":
                    tool_name = step_update.get("tool_name") or "tool"
                    tool_info = step_update.get("tool_info") or {}
                    params = tool_info.get("parameters") or {}
                    if state == "ACTIVE":
                        detail = f"Running: {tool_name}"
                        if tool_name == "run_command":
                            cmd_val = params.get("CommandLine", "")
                            detail = f"Running: {cmd_val[:60]}" if cmd_val else "Running command..."
                        elif tool_name in ("view_file", "read_file_content"):
                            path_val = params.get("AbsolutePath") or params.get("path") or ""
                            detail = f"Reading: {path_val.split('/')[-1]}" if path_val else "Reading file..."
                        elif tool_name in ("replace_file_content", "write_to_file"):
                            path_val = params.get("TargetFile") or params.get("path") or ""
                            detail = f"Editing: {path_val.split('/')[-1]}" if path_val else "Editing file..."
                        await hub.broadcast_live({
                            "type": "agent_activity",
                            "conversation_id": cid,
                            "run_id": run_id,
                            "activity": "tool_running",
                            "tool_name": tool_name,
                            "detail": detail,
                            "parameters": params,
                        })
                    elif state == "DONE":
                        out_val = tool_info.get("output") or ""
                        dur_val = step_update.get("duration_seconds")
                        await hub.broadcast_live({
                            "type": "agent_activity",
                            "conversation_id": cid,
                            "run_id": run_id,
                            "activity": "tool_done",
                            "tool_name": tool_name,
                            "detail": f"Done: {tool_name}",
                            "parameters": params,
                            "output": out_val[:2000] if out_val else None,
                            "duration_seconds": dur_val,
                        })
                elif step_type == "agent_response" and state == "ACTIVE" and not text_delta:
                    await hub.broadcast_live({
                        "type": "agent_activity",
                        "conversation_id": cid,
                        "run_id": run_id,
                        "activity": "thinking",
                        "detail": "Thinking...",
                    })

            # Handle "result" event
            elif event_type == "result":
                result_data = msg.get("result", {})

    async def read_stderr_worker():
        if not proc.stderr:
            return
        while True:
            line = await proc.stderr.readline()
            if not line:
                break
            decoded = line.decode("utf-8", errors="ignore").strip()
            if decoded:
                stderr_lines.append(decoded)

    stdout_task = asyncio.create_task(read_stdout_worker())
    stderr_task = asyncio.create_task(read_stderr_worker())

    await asyncio.gather(stdout_task, stderr_task)
    await proc.wait()

    # Small pause to allow transcript file flush, then signal tail task to finish
    await asyncio.sleep(0.3)
    tail_stop_event.set()
    await tail_task

    finished_now = datetime.now(timezone.utc).isoformat()
    cid = confirmed_conv_id or conversation_id or "unknown"
    stderr_output = "\n".join(stderr_lines)

    if run_info.cancel_requested:
        update_run_status(run_id, "cancelled", finished_at=finished_now, conversation_id=cid)
        ev = log_event(cid, run_id, "run_cancelled", {
            "run_id": run_id,
            "conversation_id": cid,
            "reason": "cancelled_by_user",
        })
        await hub.broadcast_event(ev)
    elif proc.returncode != 0:
        err = stderr_output or result_data.get("response") or f"Process exited with code {proc.returncode}"
        update_run_status(run_id, "failed", error=err, finished_at=finished_now, conversation_id=cid)
        ev = log_event(cid, run_id, "run_error", {
            "run_id": run_id,
            "conversation_id": cid,
            "exit_code": proc.returncode,
            "error": err,
        })
        await hub.broadcast_event(ev)
    else:
        update_run_status(run_id, "completed", finished_at=finished_now, conversation_id=cid)
        ev = log_event(cid, run_id, "run_finished", {
            "run_id": run_id,
            "conversation_id": cid,
            "status": result_data.get("status", "SUCCESS"),
            "duration_seconds": result_data.get("duration_seconds"),
            "usage": result_data.get("usage", {}),
            "response": result_data.get("response", ""),
        })
        await hub.broadcast_event(ev)

    await hub.broadcast_live({
        "type": "agent_activity",
        "conversation_id": cid,
        "run_id": run_id,
        "activity": "idle",
        "detail": "",
    })

    # Clean up active run registration
    if confirmed_conv_id and confirmed_conv_id in active_runs:
        del active_runs[confirmed_conv_id]
    if conversation_id and conversation_id in active_runs:
        del active_runs[conversation_id]
