from __future__ import annotations

import os
from pathlib import Path
from dataclasses import dataclass, field

BASE_DIR = Path(__file__).resolve().parent.parent
ENV_PATH = Path(os.environ.get("ENV_PATH", str(BASE_DIR / ".env")))

def load_env_file(path: Path) -> dict[str, str]:
    env_vars: dict[str, str] = {}
    if path.is_file():
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, val = line.split("=", 1)
                    env_vars[key.strip()] = val.strip().strip("'\"")
    return env_vars

_file_env = load_env_file(ENV_PATH)

def get_env(key: str, default: str = "") -> str:
    return os.environ.get(key, _file_env.get(key, default))

@dataclass(frozen=True)
class Settings:
    auth_token: str = field(default_factory=lambda: get_env("AUTH_TOKEN", ""))
    host: str = field(default_factory=lambda: get_env("HOST", "127.0.0.1"))
    port: int = field(default_factory=lambda: int(get_env("PORT", "8765")))
    max_concurrent_runs: int = field(default_factory=lambda: int(get_env("MAX_CONCURRENT_RUNS", "2")))
    db_path: str = field(default_factory=lambda: get_env("DB_PATH", str(BASE_DIR / "gateway.db")))
    conversation_summaries_db: str = field(
        default_factory=lambda: get_env("CONVERSATION_SUMMARIES_DB", "/root/.gemini/antigravity-cli/conversation_summaries.db")
    )
    brain_dir: str = field(
        default_factory=lambda: get_env("BRAIN_DIR", "/root/.gemini/antigravity-cli/brain")
    )
    uploads_dir: str = field(
        default_factory=lambda: get_env("UPLOADS_DIR", "/root/agy-uploads")
    )
    upload_max_size_bytes: int = field(
        default_factory=lambda: int(get_env("UPLOAD_MAX_SIZE_BYTES", str(50 * 1024 * 1024)))
    )
    allowed_workspace_roots: list[str] = field(
        default_factory=lambda: [
            p.strip() for p in get_env("ALLOWED_WORKSPACE_ROOTS", "/root/agy-workspaces,/root/agy-gateway/testws,/root/.gemini/antigravity-cli/brain,/root,/root/agy-uploads").split(",") if p.strip()
        ]
    )
    file_max_size_bytes: int = field(
        default_factory=lambda: int(get_env("FILE_MAX_SIZE_BYTES", str(5 * 1024 * 1024)))
    )
    agy_bin: str = field(default_factory=lambda: get_env("AGY_BIN", "/root/.local/bin/agy"))

settings = Settings()
os.makedirs(settings.uploads_dir, exist_ok=True)
