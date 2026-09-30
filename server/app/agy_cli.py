from __future__ import annotations

import asyncio
import logging
import re
import time
from typing import Optional
from app.config import settings
from app.models import ModelInfo

logger = logging.getLogger("agy_gateway.cli")

_models_cache: list[ModelInfo] = []
_models_cache_time: float = 0.0
_CACHE_TTL_SECONDS = 600.0

DEFAULT_MODELS: list[ModelInfo] = [
    ModelInfo(id="gemini-3.8-flash-high", name="Gemini 3.8 Flash (High)", reasoning_level="High"),
    ModelInfo(id="gemini-3.8-flash-medium", name="Gemini 3.8 Flash (Medium)", reasoning_level="Medium"),
    ModelInfo(id="gemini-3.8-flash-low", name="Gemini 3.8 Flash (Low)", reasoning_level="Low"),
    ModelInfo(id="gemini-3.7-flash-high", name="Gemini 3.7 Flash (High)", reasoning_level="High"),
    ModelInfo(id="gemini-3.7-flash-medium", name="Gemini 3.7 Flash (Medium)", reasoning_level="Medium"),
    ModelInfo(id="gemini-3.7-flash-low", name="Gemini 3.7 Flash (Low)", reasoning_level="Low"),
    ModelInfo(id="gemini-3.6-flash-high", name="Gemini 3.6 Flash (High)", reasoning_level="High"),
    ModelInfo(id="gemini-3.6-flash-medium", name="Gemini 3.6 Flash (Medium)", reasoning_level="Medium"),
    ModelInfo(id="gemini-3.6-flash-low", name="Gemini 3.6 Flash (Low)", reasoning_level="Low"),
    ModelInfo(id="gemini-3.1-pro-high", name="Gemini 3.1 Pro (High)", reasoning_level="High"),
    ModelInfo(id="gemini-3.1-pro-low", name="Gemini 3.1 Pro (Low)", reasoning_level="Low"),
    ModelInfo(id="claude-sonnet-4-6", name="Claude Sonnet 4.6 (Thinking)", reasoning_level="Dynamic Thinking"),
    ModelInfo(id="claude-opus-4-6-thinking", name="Claude Opus 4.6 (Thinking)", reasoning_level="Dynamic Thinking"),
    ModelInfo(id="gpt-oss-120b-medium", name="GPT-OSS 120B (Medium)", reasoning_level="Medium"),
]

def determine_reasoning_level(name: str) -> Optional[str]:
    name_lower = name.lower()
    if "(high)" in name_lower:
        return "High"
    if "(medium)" in name_lower:
        return "Medium"
    if "(low)" in name_lower:
        return "Low"
    if "(thinking)" in name_lower:
        return "Dynamic Thinking"
    return None

async def get_available_models(force_refresh: bool = False) -> list[ModelInfo]:
    global _models_cache, _models_cache_time
    now = time.time()
    if not force_refresh and _models_cache and (now - _models_cache_time < _CACHE_TTL_SECONDS):
        return _models_cache

    try:
        proc = await asyncio.create_subprocess_exec(
            settings.agy_bin,
            "models",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=10.0)
        lines = stdout.decode("utf-8", errors="ignore").splitlines()
        parsed: list[ModelInfo] = []
        for line in lines:
            line = line.strip()
            if not line:
                continue
            parts = re.split(r"\s{2,}", line, maxsplit=1)
            if len(parts) == 2:
                model_id, name = parts[0].strip(), parts[1].strip()
            else:
                tokens = line.split(None, 1)
                if len(tokens) == 2:
                    model_id, name = tokens[0].strip(), tokens[1].strip()
                else:
                    model_id = line
                    name = line

            level = determine_reasoning_level(name)
            parsed.append(ModelInfo(id=model_id, name=name, reasoning_level=level))

        if parsed:
            _models_cache = parsed
            _models_cache_time = now
            return _models_cache
    except Exception as e:
        logger.warning(f"Failed to fetch models from `agy models`: {e}")

    if _models_cache:
        return _models_cache
    return DEFAULT_MODELS
