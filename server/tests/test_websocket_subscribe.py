import json
import pytest
from app.hub import hub, WebSocketClient

class FakeWebSocket:
    def __init__(self):
        self.sent = []
    async def send_text(self, text: str):
        self.sent.append(text)

@pytest.mark.asyncio
async def test_websocket_client_matching():
    ws = FakeWebSocket()
    client = WebSocketClient(ws, conversation_id="conv-1")
    assert client.matches("conv-1") is True
    assert client.matches("conv-2") is False

    await hub.register(client)
    assert client in hub.clients

    await hub.broadcast_event({"seq": 1, "type": "step", "conversation_id": "conv-1", "data": "test"})
    assert not client.queue.empty()
    item = await client.queue.get()
    assert item["seq"] == 1

    await hub.unregister(client)
    assert client not in hub.clients
