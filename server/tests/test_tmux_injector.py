import os
import pytest
from app.session_detector import is_conversation_active_in_terminal
from app.tmux_injector import is_tmux_running, inject_message_to_tmux

def test_session_detector_nonexistent():
    assert is_conversation_active_in_terminal("nonexistent-conv-id-12345") is False

def test_session_detector_active():
    # 9f2a2ff6-c294-4209-958a-9e4f1c878fe3 is currently active and locked by agy!
    assert is_conversation_active_in_terminal("9f2a2ff6-c294-4209-958a-9e4f1c878fe3") is True

def test_tmux_running_check():
    # agy tmux session is running on the VPS
    assert is_tmux_running("agy") is True
    assert is_tmux_running("nonexistent_session_9999") is False
