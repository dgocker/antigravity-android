from __future__ import annotations

import secrets
from typing import Optional
from fastapi import Header, HTTPException, Query, WebSocket, status
from app.config import settings
from app.db import verify_and_touch_token

def verify_token(token: Optional[str]) -> bool:
    if not token:
        return False
    clean_token = token.strip()
    if settings.auth_token and secrets.compare_digest(clean_token, settings.auth_token.strip()):
        return True
    return verify_and_touch_token(clean_token)

async def require_auth(authorization: Optional[str] = Header(None)) -> str:
    if not authorization:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing Authorization header",
            headers={"WWW-Authenticate": "Bearer"},
        )
    parts = authorization.strip().split()
    if len(parts) != 2 or parts[0].lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid Authorization header format. Expected 'Bearer <token>'",
            headers={"WWW-Authenticate": "Bearer"},
        )
    token = parts[1]
    if not verify_token(token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or unauthorized token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return token

async def verify_websocket_auth(
    websocket: WebSocket,
    token: Optional[str] = Query(None),
) -> bool:
    # First check query parameter `token`
    if token and verify_token(token):
        return True

    # Next check Authorization header
    auth_header = websocket.headers.get("authorization")
    if auth_header:
        parts = auth_header.strip().split()
        if len(parts) == 2 and parts[0].lower() == "bearer" and verify_token(parts[1]):
            return True

    # Also check Sec-WebSocket-Protocol if clients send token there
    protocols = websocket.headers.get("sec-websocket-protocol", "")
    if protocols:
        for proto in [p.strip() for p in protocols.split(",")]:
            if verify_token(proto):
                return True

    # Unauthorized: raise HTTP 401 so the handshake is rejected with 401
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Unauthorized WebSocket connection",
        headers={"WWW-Authenticate": "Bearer"},
    )

