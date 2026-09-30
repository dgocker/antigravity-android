import asyncio
import os
import pytest
from app.transcript_watcher import TranscriptWatcherManager

@pytest.mark.asyncio
async def test_watcher_lifecycle():
    manager = TranscriptWatcherManager()
    await manager.ensure_watcher("test-conv-id-123")
    assert "test-conv-id-123" in manager.active_watchers
    await manager.stop_watcher("test-conv-id-123")
    assert "test-conv-id-123" not in manager.active_watchers
