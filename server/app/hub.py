from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import json
import logging
from typing import Any, Optional
from fastapi import WebSocket
from app.db import get_events

logger = logging.getLogger("agy_gateway.hub")

class WebSocketClient:
    def __init__(
        self,
        websocket: WebSocket,
        conversation_id: Optional[str] = None,
        after_seq: Optional[int] = None,
    ):
        self.websocket = websocket
        self.conversation_id = conversation_id
        self.after_seq = after_seq
        self.queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self.last_sent_seq: int = after_seq if after_seq is not None else 0

    def matches(self, conversation_id: Optional[str]) -> bool:
        if not self.conversation_id:
            return True
        return self.conversation_id == conversation_id

class Hub:
    def __init__(self) -> None:
        self.clients: set[WebSocketClient] = set()
        self._lock = asyncio.Lock()

    async def register(self, client: WebSocketClient) -> None:
        async with self._lock:
            self.clients.add(client)
        logger.info(f"WS client registered. Total active clients: {len(self.clients)}")

    async def unregister(self, client: WebSocketClient) -> None:
        async with self._lock:
            self.clients.discard(client)
        logger.info(f"WS client unregistered. Total active clients: {len(self.clients)}")

    async def broadcast_event(self, event: dict[str, Any]) -> None:
        """Broadcast persistent database event to relevant connected clients."""
        conv_id = event.get("conversation_id")
        async with self._lock:
            for client in list(self.clients):
                if client.matches(conv_id):
                    try:
                        client.queue.put_nowait(event)
                    except Exception as e:
                        logger.warning(f"Error queueing event to client: {e}")

    async def broadcast_live(self, message: dict[str, Any]) -> None:
        """Broadcast live transient event (e.g. text_delta) to relevant connected clients."""
        conv_id = message.get("conversation_id")
        async with self._lock:
            for client in list(self.clients):
                if client.matches(conv_id):
                    try:
                        client.queue.put_nowait(message)
                    except Exception as e:
                        logger.warning(f"Error queueing live message to client: {e}")

hub = Hub()

async def handle_websocket_connection(
    websocket: WebSocket,
    after: Optional[int] = None,
    conversation_id: Optional[str] = None,
) -> None:
    await websocket.accept()
    client = WebSocketClient(websocket=websocket, conversation_id=conversation_id, after_seq=after)
    await hub.register(client)

    # Step 1: Replay missed events if client was previously connected (after > 0)
    from app.db import get_latest_seq
    latest_seq = get_latest_seq()
    max_replayed_seq = after if after is not None else 0

    if after is not None and after > 0:
        missed = get_events(after_seq=after, conversation_id=conversation_id, limit=1000)
        for ev in missed:
            await websocket.send_text(json.dumps(ev, ensure_ascii=False))
            seq = ev.get("seq")
            if seq and seq > max_replayed_seq:
                max_replayed_seq = seq
        client.last_sent_seq = max(max_replayed_seq, latest_seq)
        if latest_seq > max_replayed_seq:
            await websocket.send_text(json.dumps({
                "type": "sync_seq",
                "seq": latest_seq,
            }))
    else:
        # Fresh connection / new install: fast-forward to latest seq without replaying old historical events
        client.last_sent_seq = latest_seq
        await websocket.send_text(json.dumps({
            "type": "sync_seq",
            "seq": latest_seq,
        }))

    if conversation_id:
        from app.transcript_watcher import watcher_manager
        asyncio.create_task(watcher_manager.ensure_watcher(conversation_id))
        from app.session_detector import is_conversation_active_in_terminal
        from app.tmux_injector import is_tmux_turn_active
        if is_conversation_active_in_terminal(conversation_id) and is_tmux_turn_active():
            client.queue.put_nowait({
                "type": "agent_activity",
                "conversation_id": conversation_id,
                "activity": "thinking",
                "detail": "Working in terminal...",
            })

    # Background ping task
    async def ping_worker():
        try:
            while True:
                await asyncio.sleep(20)
                ping_msg = {
                    "type": "ping",
                    "ts": datetime.now(timezone.utc).isoformat(),
                }
                await websocket.send_text(json.dumps(ping_msg))
        except asyncio.CancelledError:
            pass
        except Exception:
            pass

    # Sending queue worker
    async def send_worker():
        try:
            while True:
                msg = await client.queue.get()
                seq = msg.get("seq")
                # Deduplicate: if it was already sent during replay, drop
                if seq is not None and seq <= client.last_sent_seq:
                    client.queue.task_done()
                    continue
                if seq is not None and seq > client.last_sent_seq:
                    client.last_sent_seq = seq

                await websocket.send_text(json.dumps(msg, ensure_ascii=False))
                client.queue.task_done()
        except asyncio.CancelledError:
            pass
        except Exception:
            pass

    # Receiving worker (to detect disconnects and subscription updates)
    async def receive_worker():
        try:
            while True:
                data = await websocket.receive_text()
                try:
                    parsed = json.loads(data)
                    msg_type = parsed.get("type")
                    if msg_type == "pong":
                        pass
                    elif msg_type == "subscribe":
                        cid = parsed.get("conversation_id", "")
                        if cid:
                            client.conversation_id = cid
                            from app.transcript_watcher import watcher_manager
                            asyncio.create_task(watcher_manager.ensure_watcher(cid))
                            from app.session_detector import is_conversation_active_in_terminal
                            from app.tmux_injector import is_tmux_turn_active
                            if is_conversation_active_in_terminal(cid) and is_tmux_turn_active():
                                client.queue.put_nowait({
                                    "type": "agent_activity",
                                    "conversation_id": cid,
                                    "activity": "thinking",
                                    "detail": "Working in terminal...",
                                })
                except Exception:
                    pass
        except asyncio.CancelledError:
            pass
        except Exception:
            pass

    ping_task = asyncio.create_task(ping_worker())
    send_task = asyncio.create_task(send_worker())
    recv_task = asyncio.create_task(receive_worker())

    done, pending = await asyncio.wait(
        [ping_task, send_task, recv_task],
        return_when=asyncio.FIRST_COMPLETED,
    )

    for task in pending:
        task.cancel()
    await hub.unregister(client)
    if client.conversation_id:
        from app.transcript_watcher import watcher_manager
        other = [c for c in hub.clients if c.conversation_id == client.conversation_id]
        if not other:
            asyncio.create_task(watcher_manager.stop_watcher(client.conversation_id))
